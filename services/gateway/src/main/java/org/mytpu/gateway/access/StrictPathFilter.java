package org.mytpu.gateway.access;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Первым в цепочке отклоняет (400) адреса, которые разные слои понимают по-разному: {@code ;параметры}, сегменты
 * {@code .}/{@code ..}, закодированные точка, слеш, обратный слеш и {@code ;}, двойной слеш. Пример: {@code /mcp/..;/admin/api/me}
 * Caddy считает путём {@code /mcp*}, Tomcat — {@code /admin/api/me}, а фильтры консоли видят {@code /mcp/../admin/api/me}
 * и пропускают проверку сети. Своих адресов такого вида у шлюза нет, поэтому отказ ничего не ломает.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StrictPathFilter extends OncePerRequestFilter {
    private static final Pattern DOT_SEGMENT = Pattern.compile("(^|/)\\.{1,2}(/|$)");
    private static final String[] FORBIDDEN = {";", "\\", "//", "%2e", "%2f", "%5c", "%3b", "%25"};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (suspicious(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean suspicious(String rawUri) {
        if (rawUri == null || rawUri.isEmpty()) {
            return true;
        }
        String uri = rawUri.toLowerCase(Locale.ROOT);
        for (String bad : FORBIDDEN) {
            if (uri.contains(bad)) {
                return true;
            }
        }
        return DOT_SEGMENT.matcher(uri).find();
    }
}
