package org.mytpu.gateway.mcp;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.runtime.CallerPolicy;
import org.mytpu.tool.api.CallContext;

/**
 * Кто вызывает MCP: сейчас — мастер-ключ клиента; со спецификацией identity рядом встанет JWT агента (30 мин),
 * и дверь при этом не изменится.
 */
public interface McpCallerResolver {

    Optional<Caller> resolve(HttpServletRequest request, String requestId);

    record Caller(CallContext context, CallerPolicy policy) {
    }
}
