package org.mytpu.gateway.access;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mytpu.gateway.runtime.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /mcp} и {@code /chat/**}: запрос с заголовком {@code Origin} не из списка {@code mytpu.gateway.mcp.allowed-origins} — 403
 * (требование спецификации MCP против DNS rebinding: страница в браузере жертвы не должна говорить со шлюзом).
 * Без {@code Origin} — как раньше: так ходят Claude Code и серверные клиенты. Стоит до проверки ключа.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class McpOriginFilter extends OncePerRequestFilter {
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final Set<String> allowed;
    private final Messages messages;
    private final JsonMapper json;

    public McpOriginFilter(@Value("${mytpu.gateway.mcp.allowed-origins:}") List<String> origins,
                           Messages messages, JsonMapper json) {
        this.allowed = origins.stream().map(String::trim).filter(o -> !o.isEmpty())
                .map(o -> o.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.messages = messages;
        this.json = json;
    }

    static boolean mcpPath(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return PathRoles.isMcp(path);
    }

    static boolean isProtected(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return PathRoles.isMcp(path) || PathRoles.isChat(path);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isProtected(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !allowed.contains(origin.trim().toLowerCase(Locale.ROOT))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(json.writeValueAsString(
                    Map.of("error", "origin_not_allowed", "message", messages.text("access.origin_not_allowed"))));
            return;
        }
        chain.doFilter(request, response);
    }
}
