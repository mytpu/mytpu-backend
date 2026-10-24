package org.mytpu.gateway.stats;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Журналы для консоли с фильтрами и постраничным чтением «до id» (новые сверху): вызовы ({@code audit}, аргументов нет —
 * только отпечаток) и действия администратора ({@code admin_audit}). Выгрузка CSV читает тем же запросом порциями.
 */
@Component
public class JournalQueries {
    public static final int MAX_PAGE = 200;

    public record CallFilter(Instant from, Instant to, String clientId, String tool, String outcome, Long beforeId) {
    }

    public record CallRow(long id, Instant at, String requestId, String clientId, String channel, String tool,
                          String argsHash, String outcome, String errorCode, boolean cacheHit, int durationMs) {
    }

    public record AdminFilter(Instant from, Instant to, String actorId, String objectType, String objectId, Long beforeId) {
    }

    public record AdminRow(long id, Instant at, String actorId, String actorOwner, String action, String objectType,
                           String objectId, String before, String after) {
    }

    private final JdbcClient jdbc;

    public JournalQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CallRow> calls(CallFilter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, at, request_id, client_id, channel, tool, args_hash, outcome, error_code, "
                + "cache_hit, duration_ms FROM audit WHERE at >= :from AND at < :to");
        List<Object[]> params = new ArrayList<>();
        optional(sql, params, "client_id = :client", "client", f.clientId());
        optional(sql, params, "tool = :tool", "tool", f.tool());
        optional(sql, params, "outcome = :outcome", "outcome", f.outcome());
        optional(sql, params, "id < :before", "before", f.beforeId());
        sql.append(" ORDER BY id DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("from", Timestamp.from(f.from())).param("to", Timestamp.from(f.to()))
                .param("limit", limit);
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query((rs, n) -> new CallRow(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getString("request_id"),
                rs.getString("client_id"), rs.getString("channel"), rs.getString("tool"), rs.getString("args_hash"),
                rs.getString("outcome"), rs.getString("error_code"), rs.getBoolean("cache_hit"), rs.getInt("duration_ms"))).list();
    }

    /** Все строки по фильтру порциями (для CSV), не больше {@code max}. */
    public void eachCall(CallFilter f, int max, Consumer<CallRow> sink) {
        Long before = f.beforeId();
        int sent = 0;
        while (sent < max) {
            List<CallRow> page = calls(new CallFilter(f.from(), f.to(), f.clientId(), f.tool(), f.outcome(), before),
                    Math.min(1000, max - sent));
            page.forEach(sink);
            sent += page.size();
            if (page.size() < 1000) {
                return;
            }
            before = page.getLast().id();
        }
    }

    public List<AdminRow> adminActions(AdminFilter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT id, at, actor_id, actor_owner, action, object_type, object_id, "
                + "before::text AS before, after::text AS after FROM admin_audit WHERE at >= :from AND at < :to");
        List<Object[]> params = new ArrayList<>();
        optional(sql, params, "actor_id = :actor", "actor", f.actorId());
        optional(sql, params, "object_type = :type", "type", f.objectType());
        optional(sql, params, "object_id = :object", "object", f.objectId());
        optional(sql, params, "id < :before", "before", f.beforeId());
        sql.append(" ORDER BY id DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("from", Timestamp.from(f.from())).param("to", Timestamp.from(f.to()))
                .param("limit", limit);
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query((rs, n) -> new AdminRow(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getString("actor_id"),
                rs.getString("actor_owner"), rs.getString("action"), rs.getString("object_type"), rs.getString("object_id"),
                rs.getString("before"), rs.getString("after"))).list();
    }

    private static void optional(StringBuilder sql, List<Object[]> params, String condition, String name, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            sql.append(" AND ").append(condition);
            params.add(new Object[]{name, value});
        }
    }
}
