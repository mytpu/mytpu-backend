package org.mytpu.gateway.access;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Мастер-ключи в таблице {@code api_clients}. Поиск по хэшу кэшируется на 30 секунд:
 * отзыв или изменение прав ключа действуют не позже чем через 30 с.
 */
@Component
public class ApiClientStore {
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Cache<String, ApiClient> byHash = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();
    /** Промахи — отдельно и небольшим кэшем, чтобы перебор не вытеснял настоящие ключи. */
    private final Cache<String, Boolean> misses = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(2_000)
            .build();
    private final Map<String, Instant> lastTouch = new ConcurrentHashMap<>();
    /** Потолок поисков ключа в БД в секунду (не из кэша): перебор правильных по формату ключей не забирает пул. */
    private final int maxLookupsPerSecond;
    private long lookupSecond;
    private int lookups;

    public ApiClientStore(JdbcClient jdbc, JsonMapper json,
                          @org.springframework.beans.factory.annotation.Value("${mytpu.gateway.access.max-key-lookups-per-second:50}") int maxLookupsPerSecond) {
        this.jdbc = jdbc;
        this.json = json;
        this.maxLookupsPerSecond = maxLookupsPerSecond;
    }

    private synchronized boolean lookupAllowed() {
        long second = System.currentTimeMillis() / 1000;
        if (second != lookupSecond) {
            lookupSecond = second;
            lookups = 0;
        }
        return ++lookups <= maxLookupsPerSecond;
    }

    public Optional<ApiClient> findByKey(String key) {
        byte[] hash = ApiKeys.hash(key);
        String hex = HexFormat.of().formatHex(hash);
        ApiClient cached = byHash.getIfPresent(hex);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (misses.getIfPresent(hex) != null || !lookupAllowed()) {
            return Optional.empty();
        }
        Optional<ApiClient> found = jdbc.sql("SELECT *, allowed_networks::text[] AS networks_text FROM api_clients "
                        + "WHERE key_hash = :hash OR (previous_key_hash = :hash AND previous_key_until > now())")
                .param("hash", hash)
                .query(this::map)
                .optional();
        found.ifPresentOrElse(c -> byHash.put(hex, c), () -> misses.put(hex, Boolean.TRUE));
        return found;
    }

    /** Время последнего вызова — не чаще раза в минуту на ключ, чтобы не писать в БД на каждый вызов. */
    public void touch(String clientId) {
        Instant now = Instant.now();
        Instant previous = lastTouch.get(clientId);
        if (previous != null && previous.plus(TOUCH_EVERY).isAfter(now)) {
            return;
        }
        lastTouch.put(clientId, now);
        jdbc.sql("UPDATE api_clients SET last_used_at = now() WHERE id = :id").param("id", clientId).update();
    }

    /** Создаёт ключ и возвращает его открытый текст — единственный раз; в БД остаются хэш и префикс. */
    public IssuedKey issue(NewClient request) {
        String key = ApiKeys.generate();
        String id = "cli_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.sql("""
                INSERT INTO api_clients (id, owner, key_prefix, key_hash, scopes, roles, denied_tools, rate_limits, read_only,
                                         expires_at, kind, allowed_networks, decoy)
                VALUES (:id, :owner, :prefix, :hash, :scopes, :roles, :denied, CAST(:limits AS jsonb), :readOnly,
                        :expires, :kind, CAST(:networks AS cidr[]), :decoy)
                """)
                .param("id", id)
                .param("owner", request.owner())
                .param("prefix", ApiKeys.shownPrefix(key))
                .param("hash", ApiKeys.hash(key))
                .param("scopes", request.scopes().toArray(String[]::new))
                .param("roles", request.roles().toArray(String[]::new))
                .param("denied", request.deniedTools().toArray(String[]::new))
                .param("limits", json.writeValueAsString(request.rateLimits()))
                .param("readOnly", request.readOnly())
                .param("expires", request.expiresAt() == null ? null : Timestamp.from(request.expiresAt()))
                .param("kind", request.kind().db())
                .param("networks", request.allowedNetworks().toArray(String[]::new))
                .param("decoy", request.decoy())
                .update();
        return new IssuedKey(id, key);
    }

    /** Приостановка (подозрение на утечку): действует сразу на этом экземпляре, на остальных — за ≤ 30 с. */
    public boolean suspend(String clientId, String reason) {
        int changed = jdbc.sql("UPDATE api_clients SET suspended_at = now(), suspended_reason = :reason "
                        + "WHERE id = :id AND suspended_at IS NULL")
                .param("id", clientId).param("reason", reason).update();
        invalidate(clientId);
        return changed > 0;
    }

    public boolean resume(String clientId) {
        // Отсчёт неактивности — заново с момента снятия, иначе ночная уборка приостановит ключ снова
        int changed = jdbc.sql("UPDATE api_clients SET suspended_at = NULL, suspended_reason = NULL, last_used_at = now() "
                        + "WHERE id = :id AND suspended_at IS NOT NULL")
                .param("id", clientId).update();
        invalidate(clientId);
        return changed > 0;
    }

    /** Изменение ключа из консоли: на этом экземпляре сразу, на остальных — за ≤ 30 с (срок кэша). */
    void invalidate(String clientId) {
        byHash.asMap().values().removeIf(c -> c.id().equals(clientId));
        // Внутри транзакции параллельный запрос успел бы положить в кэш старую строку до commit — сброс ещё раз после него
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            byHash.asMap().values().removeIf(c -> c.id().equals(clientId));
                        }
                    });
        }
    }

    ApiClient map(ResultSet rs, int row) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        Timestamp suspended = rs.getTimestamp("suspended_at");
        return new ApiClient(
                rs.getString("id"),
                rs.getString("owner"),
                rs.getString("key_prefix"),
                strings(rs.getArray("scopes")),
                strings(rs.getArray("roles")),
                strings(rs.getArray("denied_tools")),
                json.readValue(rs.getString("rate_limits"), new TypeReference<Map<String, Integer>>() { }),
                rs.getBoolean("read_only"),
                rs.getBoolean("enabled"),
                expires == null ? null : expires.toInstant(),
                ApiClient.Kind.fromDb(rs.getString("kind")),
                List.copyOf(strings(rs.getArray("networks_text"))),
                suspended == null ? null : suspended.toInstant(),
                rs.getBoolean("decoy"));
    }

    private static Set<String> strings(Array array) throws SQLException {
        return array == null ? Set.of() : Set.of((String[]) array.getArray());
    }

    public record NewClient(String owner, Set<String> scopes, Set<String> roles, Set<String> deniedTools,
                            Map<String, Integer> rateLimits, boolean readOnly, Instant expiresAt,
                            ApiClient.Kind kind, List<String> allowedNetworks, boolean decoy) {
    }

    public record IssuedKey(String clientId, String key) {
    }
}
