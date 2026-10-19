package org.mytpu.gateway.access;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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

    public ApiClientStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<ApiClient> findByKey(String key) {
        byte[] hash = ApiKeys.hash(key);
        String hex = HexFormat.of().formatHex(hash);
        ApiClient cached = byHash.getIfPresent(hex);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (misses.getIfPresent(hex) != null) {
            return Optional.empty();
        }
        Optional<ApiClient> found = jdbc.sql("SELECT * FROM api_clients WHERE key_hash = :hash")
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
                INSERT INTO api_clients (id, owner, key_prefix, key_hash, scopes, roles, denied_tools, rate_limits, read_only, expires_at)
                VALUES (:id, :owner, :prefix, :hash, :scopes, :roles, :denied, CAST(:limits AS jsonb), :readOnly, :expires)
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
                .update();
        return new IssuedKey(id, key);
    }

    private ApiClient map(ResultSet rs, int row) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
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
                expires == null ? null : expires.toInstant());
    }

    private static Set<String> strings(Array array) throws SQLException {
        return array == null ? Set.of() : Set.of((String[]) array.getArray());
    }

    public record NewClient(String owner, Set<String> scopes, Set<String> roles, Set<String> deniedTools,
                            Map<String, Integer> rateLimits, boolean readOnly, Instant expiresAt) {
    }

    public record IssuedKey(String clientId, String key) {
    }
}
