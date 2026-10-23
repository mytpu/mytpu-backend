package org.mytpu.assistant.limits;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mytpu.assistant.config.ChatProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Тело запроса к чату — не больше {@code mytpu.chat.max-body-bytes}: больше → 413, без длины (chunked) → 411.
 * Иначе гигантское тело читалось бы в память целиком.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class BodySizeFilter extends OncePerRequestFilter {
    private final ChatProperties properties;

    public BodySizeFilter(ChatProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Любой POST, а не только /api/**: сырой путь можно записать так, что сравнение по префиксу его пропустит
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length < 0) {
            response.sendError(HttpServletResponse.SC_LENGTH_REQUIRED);
            return;
        }
        if (length > properties.maxBodyBytes()) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(request, response);
    }
}
