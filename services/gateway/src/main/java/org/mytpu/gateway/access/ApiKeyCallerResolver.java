package org.mytpu.gateway.access;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.mcp.McpCallerResolver;
import org.mytpu.gateway.runtime.CallerPolicy;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.Channel;
import org.springframework.stereotype.Component;

/** Вызывающий по мастер-ключу: ключ уже проверен {@link McpKeyFilter}, здесь — его права в виде контекста вызова. */
@Component
public class ApiKeyCallerResolver implements McpCallerResolver {
    private final DevCallerResolver dev;

    public ApiKeyCallerResolver(DevCallerResolver dev) {
        this.dev = dev;
    }

    @Override
    public Optional<Caller> resolve(HttpServletRequest request, String requestId) {
        if (!(request.getAttribute(McpKeyFilter.CLIENT_ATTRIBUTE) instanceof ApiClient client)) {
            return Optional.empty();
        }
        // До identity у мастер-ключа нет пользователя; на dev-стенде его можно задать заголовками (DevCallerResolver)
        CallContext context = dev.context(request, client, requestId)
                .orElseGet(() -> new CallContext(null, client.roles(), client.id(), Channel.MCP, client.scopes(), requestId));
        CallerPolicy policy = new CallerPolicy(client.deniedTools(), client.deniedFields(), client.rateLimits(), client.readOnly());
        return Optional.of(new Caller(context, policy));
    }
}
