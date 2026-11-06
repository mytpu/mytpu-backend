package org.mytpu.gateway.access;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Мастер-ключи для консоли: список с последним вызовом и числом вызовов, смена разрешений, перевыпуск с переходным
 * сроком (старый ключ живёт ещё {@code grace}), отзыв. Правила — {@link KeyRules}; кэш ключей сбрасывается на этом
 * экземпляре сразу, на остальных изменение действует за ≤ 30 с.
 */
@Component
public class ApiClientAdmin {
    public static final Duration MAX_GRACE = Duration.ofDays(7);

    /** Строка списка ключей. */
    public record KeyRow(ApiClient client, Instant createdAt, Instant lastUsedAt, long calls30d, Instant previousKeyUntil) {
    }

    /** Что администратор меняет у выданного ключа. */
    public record Permissions(Set<String> scopes, Set<String> deniedTools, Map<String, Set<String>> deniedFields,
                              Map<String, Integer> rateLimits, boolean readOnly, List<String> networks,
                              ApiClient.GeoPolicy geo, String geoReason) {
        public Permissions(Set<String> scopes, Set<String> deniedTools, Map<String, Integer> rateLimits,
                           boolean readOnly, List<String> networks) {
            this(scopes, deniedTools, Map.of(), rateLimits, readOnly, networks, null, null);
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ApiClientStore store;
    private final KeyRules rules;

    public ApiClientAdmin(JdbcClient jdbc, JsonMapper json, ApiClientStore store, KeyRules rules) {
        this.jdbc = jdbc;
        this.json = json;
        this.store = store;
        this.rules = rules;
    }

    public List<KeyRow> list() {
        return jdbc.sql("""
                        SELECT c.*, c.allowed_networks::text[] AS networks_text, coalesce(s.calls, 0) AS calls_30d
                        FROM api_clients c
                        LEFT JOIN (SELECT client_id, sum(calls) AS calls FROM audit_minute
                                   WHERE minute > now() - interval '30 days' GROUP BY client_id) s ON s.client_id = c.id
                        ORDER BY c.created_at DESC
                        """)
                .query((rs, n) -> new KeyRow(store.map(rs, n), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("last_used_at")), rs.getLong("calls_30d"), instant(rs.getTimestamp("previous_key_until"))))
                .list();
    }

    public Optional<ApiClient> find(String id) {
        return jdbc.sql("SELECT *, allowed_networks::text[] AS networks_text FROM api_clients WHERE id = :id")
                .param("id", id).query(store::map).optional();
    }

    public ApiClient updatePermissions(ApiClient current, Permissions p) {
        rules.checkScopes(p.scopes());
        rules.checkTools(p.deniedTools(), p.rateLimits());
        rules.checkFields(p.deniedFields());
        rules.checkNetworks(current.kind(), p.networks());
        KeyRules.GeoDecision geo = rules.checkGeoUpdate(current, p.geo(), p.geoReason());
        if (current.decoy()) {
            throw new KeyRules.Violation("decoy_with_rights");
        }
        if (p.scopes().isEmpty() && current.roles().isEmpty()) {
            throw new KeyRules.Violation("no_rights");
        }
        if (p.networks().isEmpty() && current.expiresAt() != null
                && current.expiresAt().isAfter(Instant.now().plus(Duration.ofDays(rules.maxDaysWithoutNetworks())))) {
            // Убрать сети у ключа на год — обойти срок 30 дней для ключа без сетей
            throw new KeyRules.Violation("days_range_no_networks", rules.maxDaysWithoutNetworks());
        }
        jdbc.sql("""
                        UPDATE api_clients SET scopes = :scopes, denied_tools = :denied, denied_fields = CAST(:fields AS jsonb),
                               rate_limits = CAST(:limits AS jsonb),
                               read_only = :readOnly, allowed_networks = CAST(:networks AS cidr[]),
                               geo_policy = :geoPolicy, geo_reason = :geoReason
                        WHERE id = :id
                        """)
                .param("id", current.id())
                .param("scopes", p.scopes().toArray(String[]::new))
                .param("denied", p.deniedTools().toArray(String[]::new))
                .param("fields", json.writeValueAsString(p.deniedFields()))
                .param("limits", json.writeValueAsString(p.rateLimits()))
                .param("readOnly", p.readOnly())
                .param("networks", p.networks().toArray(String[]::new))
                .param("geoPolicy", geo.policy().db())
                .param("geoReason", geo.reason())
                .update();
        store.invalidate(current.id());
        return find(current.id()).orElseThrow();
    }

    /** Новый ключ того же клиента; прежний принимается ещё {@code grace} (0 — сразу перестаёт). */
    public ApiClientStore.IssuedKey reissue(ApiClient current, Duration grace) {
        if (current.decoy()) {
            throw new KeyRules.Violation("decoy_with_rights");
        }
        String key = ApiKeys.generate();
        jdbc.sql("""
                        UPDATE api_clients SET previous_key_hash = CASE WHEN :graceMs > 0 THEN key_hash END,
                               previous_key_until = CASE WHEN :graceMs > 0 THEN now() + make_interval(secs => :graceMs / 1000.0) END,
                               key_hash = :hash, key_prefix = :prefix
                        WHERE id = :id
                        """)
                .param("id", current.id())
                .param("graceMs", grace.toMillis())
                .param("hash", ApiKeys.hash(key))
                .param("prefix", ApiKeys.shownPrefix(key))
                .update();
        store.invalidate(current.id());
        return new ApiClientStore.IssuedKey(current.id(), key);
    }

    /** Отзыв: ключ (и прежний после перевыпуска) перестаёт приниматься. */
    public void revoke(ApiClient current) {
        jdbc.sql("UPDATE api_clients SET enabled = false, previous_key_until = NULL WHERE id = :id")
                .param("id", current.id()).update();
        store.invalidate(current.id());
    }

    public boolean resume(ApiClient current, ApiClient.GeoPolicy geo, String geoReason) {
        if (geo != null) {
            KeyRules.GeoDecision decision = rules.checkGeo(current.decoy(), current.roles(), geo, geoReason);
            boolean wasSuspended = current.suspended();
            boolean geoChanged = decision.policy() != current.geo()
                    || !java.util.Objects.equals(decision.reason(), current.geoReason());
            if (!wasSuspended && !geoChanged) {
                return false;
            }
            jdbc.sql("""
                    UPDATE api_clients
                    SET suspended_at = NULL, suspended_reason = NULL,
                        last_used_at = CASE WHEN suspended_at IS NOT NULL THEN now() ELSE last_used_at END,
                        geo_policy = :geoPolicy, geo_reason = :geoReason
                    WHERE id = :id
                    """)
                    .param("id", current.id())
                    .param("geoPolicy", decision.policy().db())
                    .param("geoReason", decision.reason())
                    .update();
            store.invalidate(current.id());
            return true;
        } else {
            if (!current.suspended()) {
                return false;
            }
            int updated = jdbc.sql("""
                    UPDATE api_clients
                    SET suspended_at = NULL, suspended_reason = NULL, last_used_at = now()
                    WHERE id = :id AND suspended_at IS NOT NULL
                    """)
                    .param("id", current.id())
                    .update();
            store.invalidate(current.id());
            return updated > 0;
        }
    }

    public boolean resume(ApiClient current) {
        return resume(current, null, null);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
