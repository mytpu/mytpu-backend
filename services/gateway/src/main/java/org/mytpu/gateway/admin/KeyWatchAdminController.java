package org.mytpu.gateway.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Контроллер консоли администратора для отслеживания истекающих мастер-ключей и событий безопасности
 * (specs/key-lifecycle-and-decoys, шаг 4). Доступен только для чтения (GET) администраторам и наблюдателям.
 */
@RestController
public class KeyWatchAdminController {

    /** Представление истекающего ключа для консоли. */
    public record ExpiringKeyView(
            String id,
            String owner,
            String keyPrefix,
            String kind,
            Instant expiresAt,
            Instant lastUsedAt,
            boolean suspended
    ) {
    }

    /** Представление события мастер-ключа из журнала для консоли. */
    public record KeyEventView(
            long id,
            Instant at,
            String clientId,
            String owner,
            String event,
            String network,
            String detail
    ) {
    }

    private final JdbcClient jdbc;

    public KeyWatchAdminController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/api/keys/expiring")
    public List<ExpiringKeyView> expiringKeys(@RequestParam(defaultValue = "14") int days) {
        if (days < 1 || days > 365) {
            throw AdminException.badRequest("days_range", 365);
        }
        return jdbc.sql("""
                SELECT id, owner, key_prefix, kind, expires_at, last_used_at, suspended_at IS NOT NULL AS suspended
                FROM api_clients
                WHERE enabled
                  AND NOT decoy
                  AND expires_at > now()
                  AND expires_at <= now() + make_interval(days => :days)
                ORDER BY expires_at ASC
                LIMIT 200
                """)
                .param("days", days)
                .query((rs, rowNum) -> new ExpiringKeyView(
                        rs.getString("id"),
                        rs.getString("owner"),
                        rs.getString("key_prefix"),
                        rs.getString("kind"),
                        instant(rs.getTimestamp("expires_at")),
                        instant(rs.getTimestamp("last_used_at")),
                        rs.getBoolean("suspended")
                ))
                .list();
    }

    @GetMapping("/admin/api/key-events")
    public List<KeyEventView> keyEvents(
            @RequestParam(required = false) String event,
            @RequestParam(required = false) String client,
            @RequestParam(defaultValue = "100") int limit
    ) {
        if (limit < 1 || limit > 500) {
            throw AdminException.badRequest("limit_range", 500);
        }

        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.at, e.client_id, c.owner, e.event, e.network::text AS network, e.detail
                FROM key_events e
                LEFT JOIN api_clients c ON c.id = e.client_id
                WHERE 1 = 1
                """);

        boolean hasEvent = event != null && !event.isBlank();
        boolean hasClient = client != null && !client.isBlank();
        if (hasEvent) {
            sql.append(" AND e.event = :event");
        }
        if (hasClient) {
            sql.append(" AND e.client_id = :client");
        }
        sql.append(" ORDER BY e.at DESC, e.id DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (hasEvent) {
            spec = spec.param("event", event.strip());
        }
        if (hasClient) {
            spec = spec.param("client", client.strip());
        }

        return spec.query((rs, rowNum) -> new KeyEventView(
                rs.getLong("id"),
                rs.getTimestamp("at").toInstant(),
                rs.getString("client_id"),
                rs.getString("owner"),
                rs.getString("event"),
                rs.getString("network"),
                rs.getString("detail")
        )).list();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
