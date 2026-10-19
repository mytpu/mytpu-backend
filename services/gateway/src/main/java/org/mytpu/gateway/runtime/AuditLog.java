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

    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(CallContext context, String tool, String argsHash, ToolResult<?> result, boolean cacheHit, long millis) {
        try {
            jdbc.sql("""
                    INSERT INTO audit (request_id, user_id, client_id, channel, tool, args_hash, outcome, error_code, cache_hit, duration_ms)
                    VALUES (:request, :user, :client, :channel, :tool, :hash, :outcome, :code, :cacheHit, :ms)
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
                    .update();
        } catch (RuntimeException e) {
            log.warn("Не удалось записать вызов в журнал: {}", e.getMessage());
        }
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
