package org.mytpu.gateway.runtime;

import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Журнал каждого вызова (успех и отказ): кто, через какую дверь, что, итог, длительность.
 * Аргументы — только хэш (docs/SECURITY.md, раздел 10). Сбой записи не ломает вызов.
 */
@Component
public class AuditLog {
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private static final int MAX_TOOL_NAME = 64;
    /** Верхние границы корзин длительности (мс) в {@code audit_minute.buckets}; последняя корзина — дольше 30 с. */
    public static final int[] LATENCY_BUCKETS_MS = {10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000};

    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(CallContext context, String tool, String argsHash, ToolResult<?> result, boolean cacheHit, long millis) {
        try {
            // Строка журнала и её вклад в поминутный агрегат (обзор консоли) — одним запросом
            jdbc.sql("""
                    WITH row AS (
                        INSERT INTO audit (request_id, user_id, client_id, channel, tool, args_hash, outcome, error_code, cache_hit, duration_ms)
                        VALUES (:request, :user, :client, :channel, :tool, :hash, :outcome, :code, :cacheHit, :ms)
                        RETURNING at, client_id, tool, outcome, cache_hit, duration_ms
                    )
                    INSERT INTO audit_minute AS m (minute, client_id, tool, calls, errors, cache_hits, total_ms, buckets)
                    SELECT date_trunc('minute', at), client_id, tool, 1, (outcome = 'failed')::int, cache_hit::int, duration_ms,
                           array_fill(0, ARRAY[:bucket - 1]) || 1 || array_fill(0, ARRAY[:buckets - :bucket])
                    FROM row
                    ON CONFLICT (minute, client_id, tool) DO UPDATE SET
                        calls = m.calls + 1,
                        errors = m.errors + EXCLUDED.errors,
                        cache_hits = m.cache_hits + EXCLUDED.cache_hits,
                        total_ms = m.total_ms + EXCLUDED.total_ms,
                        buckets[:bucket] = m.buckets[:bucket] + 1
                    """)
                    .param("request", context.requestId())
                    .param("user", context.userId())
                    .param("client", context.clientId())
                    .param("channel", context.channel().name())
                    // Имя приходит от клиента: в журнал — не длиннее 64 символов
                    .param("tool", tool == null ? "" : tool.substring(0, Math.min(tool.length(), MAX_TOOL_NAME)))
                    .param("hash", argsHash)
                    .param("outcome", outcome(result))
                    .param("code", code(result))
                    .param("cacheHit", cacheHit)
                    .param("ms", (int) Math.min(millis, Integer.MAX_VALUE))
                    .param("buckets", LATENCY_BUCKETS_MS.length + 1)
                    .param("bucket", bucket(millis))
                    .update();
        } catch (RuntimeException e) {
            log.warn("Не удалось записать вызов в журнал: {}", e.getMessage());
        }
    }

    /** Номер корзины в массиве Postgres (с единицы). */
    static int bucket(long millis) {
        for (int i = 0; i < LATENCY_BUCKETS_MS.length; i++) {
            if (millis <= LATENCY_BUCKETS_MS[i]) {
                return i + 1;
            }
        }
        return LATENCY_BUCKETS_MS.length + 1;
    }

    private static String outcome(ToolResult<?> result) {
        return switch (result) {
            case ToolResult.Ok<?> ok -> "ok";
            case ToolResult.NeedsChoice<?> choice -> "needs_choice";
            case ToolResult.Denied<?> denied -> "denied";
            case ToolResult.Failed<?> failed -> "failed";
        };
    }

    private static String code(ToolResult<?> result) {
        return switch (result) {
            case ToolResult.Denied<?> denied -> denied.code().code();
            case ToolResult.Failed<?> failed -> failed.code().code();
            default -> null;
        };
    }
}
