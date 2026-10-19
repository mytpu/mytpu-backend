package org.mytpu.gateway.access;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mytpu.gateway.runtime.Messages;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Доступ по мастер-ключу, «закрыто всё»: без ключа открыт только {@code /actuator/health}.
 * {@code /internal/} — только ключ с ролью консоли (изменения — только {@code console.admin}).
 * Путь берётся декодированным и без {@code ;параметров} — так же, как его сопоставляет Spring MVC,
 * иначе {@code /%69nternal/…} или {@code /internal;x/…} проходили бы мимо проверки.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class McpKeyFilter extends OncePerRequestFilter {
    /** Атрибут запроса с найденным {@link ApiClient} — его читает определение вызывающего. */
    public static final String CLIENT_ATTRIBUTE = McpKeyFilter.class.getName() + ".client";

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final ApiClientStore store;
    private final AuthFailureLimiter failures;
    private final Messages messages;
    private final JsonMapper json;

    public McpKeyFilter(ApiClientStore store, AuthFailureLimiter failures, Messages messages, JsonMapper json) {
        this.store = store;
        this.failures = failures;
        this.messages = messages;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        if (failures.blocked(ip)) {
            reject(response, 429, "too_many_attempts", "access.too_many_attempts");
            return;
        }
        Optional<ApiClient> client = bearer(request)
                .filter(ApiKeys::wellFormed)
                .flatMap(store::findByKey)
                .filter(c -> c.activeAt(Instant.now()));
        if (client.isEmpty()) {
            failures.recordFailure(ip);
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token", "access.invalid_token");
            return;
        }
        String path = PATHS.getPathWithinApplication(request);
        if (path.startsWith("/internal/") && !allowedInternal(client.get(), request.getMethod())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "insufficient_scope", "access.console_role");
            return;
        }
        store.touch(client.get().id());
        request.setAttribute(CLIENT_ATTRIBUTE, client.get());
        chain.doFilter(request, response);
    }

    /** Читать консоль может viewer, менять — только admin. */
    private static boolean allowedInternal(ApiClient client, String method) {
        boolean readOnly = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        return readOnly ? client.hasConsoleRole() : client.roles().contains(ApiClient.CONSOLE_ADMIN);
    }

    private static Optional<String> bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(7).trim());
    }

    private void reject(HttpServletResponse response, int status, String error, String messageKey) throws IOException {
        response.setStatus(status);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"mytpu\", error=\"" + error + "\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("error", error, "message", messages.text(messageKey))));
    }
}
