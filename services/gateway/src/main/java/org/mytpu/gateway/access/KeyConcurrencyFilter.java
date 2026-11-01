package org.mytpu.gateway.access;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Не больше N одновременных запросов {@code /mcp} и {@code /chat/**} на ключ: один ключ (утёкший или зациклившийся агент)
 * не занимает все потоки шлюза. Для {@code chat.service} лимит свой (по умолчанию 64). Стоит после {@link McpKeyFilter}
 * — ключ уже проверен. Потоковый (асинхронный) ответ держит разрешение до своего завершения, ошибки или таймаута,
 * а не до выхода из фильтра.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class KeyConcurrencyFilter extends OncePerRequestFilter {
    private final int maxPerKey;
    private final int maxConcurrentChat;
    private final Messages messages;
    private final JsonMapper json;
    /**
     * Запросы в работе по ключу. Запись живёт, пока у ключа есть хоть один запрос, и удаляется на последнем: без
     * вытеснения по сроку — иначе долгий поток терял бы счётчик, и ключ получал бы новые разрешения сверх лимита.
     */
    private final ConcurrentHashMap<String, Integer> active = new ConcurrentHashMap<>();

    public KeyConcurrencyFilter(int maxPerKey, Messages messages, JsonMapper json) {
        this(maxPerKey, 64, messages, json);
    }

    public KeyConcurrencyFilter(@Value("${mytpu.gateway.access.max-concurrent-per-key:8}") int maxPerKey,
                                @Value("${mytpu.gateway.access.max-concurrent-chat:64}") int maxConcurrentChat,
                                Messages messages, JsonMapper json) {
        this.maxPerKey = maxPerKey;
        this.maxConcurrentChat = maxConcurrentChat;
        this.messages = messages;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getAttribute(McpKeyFilter.CLIENT_ATTRIBUTE) instanceof ApiClient)
                || !McpOriginFilter.isProtected(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ApiClient caller = McpKeyFilter.caller(request);
        String id = caller.id();
        int limit = caller.hasChatServiceRole() ? maxConcurrentChat : maxPerKey;
        if (!acquire(id, limit)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(json.writeValueAsString(
                    Map.of("error", "too_many_concurrent", "message", messages.text("access.too_many_concurrent"))));
            return;
        }
        Release release = new Release(() -> release(id));
        boolean async = false;
        try {
            chain.doFilter(request, response);
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(release);
                async = true;
            }
        } finally {
            if (!async) {
                release.run();
            }
        }
    }

    private boolean acquire(String id, int limit) {
        boolean[] taken = {false};
        active.compute(id, (k, n) -> {
            int current = n == null ? 0 : n;
            if (current >= limit) {
                return n;
            }
            taken[0] = true;
            return current + 1;
        });
        return taken[0];
    }

    private void release(String id) {
        active.computeIfPresent(id, (k, n) -> n <= 1 ? null : n - 1);
    }

    /** Запросов в работе у ключа — для проверок. */
    int inFlight(String id) {
        return active.getOrDefault(id, 0);
    }

    int maxPerKey() {
        return maxPerKey;
    }

    int maxConcurrentChat() {
        return maxConcurrentChat;
    }

    /** Возвращает разрешение ровно один раз: у асинхронного ответа могут прийти и ошибка, и завершение. */
    static final class Release implements AsyncListener {
        private final Runnable onRelease;
        private final AtomicBoolean done = new AtomicBoolean();

        Release(Runnable onRelease) {
            this.onRelease = onRelease;
        }

        void run() {
            if (done.compareAndSet(false, true)) {
                onRelease.run();
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            run();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            run();
        }

        @Override
        public void onError(AsyncEvent event) {
            run();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // Повторный запуск асинхронной обработки: слушатель надо подписать снова, разрешение остаётся занятым
            event.getAsyncContext().addListener(this);
        }
    }
}
