package org.mytpu.assistant.limits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mytpu.assistant.errors.ChatErrorHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Необязательный ключ клиента чата: если задан секрет {@code assistant_client_key} (файл в {@code /run/secrets/}), запросы к
 * любые пути, кроме {@code /actuator/health}, без заголовка {@code X-Assistant-Key} с этим значением → 401 (закрыто по
 * умолчанию: путь сравнивается после декодирования и без {@code ;параметров}, как его видит Spring MVC). Не задан — фильтр ничего не делает (фронтенд
 * на моках не ломается). До входа пользователя (спецификация identity) — единственная защита от чужих вызовов.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ClientKeyFilter extends OncePerRequestFilter {
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final byte[] expected;
    private final ChatErrorHandler errors;
    private final JsonMapper json;

    public ClientKeyFilter(@Value("${assistant_client_key:}") String key, ChatErrorHandler errors, JsonMapper json) {
        this.expected = key.isBlank() ? null : key.trim().getBytes(StandardCharsets.UTF_8);
        this.errors = errors;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (expected == null) {
            return true;
        }
        String path = PATHS.getPathWithinApplication(request);
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader("X-Assistant-Key");
        if (given == null || !MessageDigest.isEqual(expected, given.trim().getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(json.writeValueAsString(errors.body("unauthorized")));
            return;
        }
        chain.doFilter(request, response);
    }
}
