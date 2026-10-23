package org.mytpu.assistant.limits;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.assistant.config.ChatProperties;
import org.mytpu.assistant.errors.ChatException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Лимиты чата: запросов с одного адреса в минуту ({@code per-ip-per-minute}) и одновременных вызовов модели
 * ({@code max-concurrent}). Без входа пользователя это единственное, что не даёт одному клиенту сжечь токены модели
 * и занять все потоки. Сверх лимита — 429 с {@code Retry-After}.
 */
@Component
public class ChatLimits {

    /** Место в очереди к модели; освобождается один раз — по окончании ответа или потока (в том числе при обрыве). */
    public static final class Permit {
        private final Semaphore slots;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore slots) {
            this.slots = slots;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
        }
    }

    private final ChatProperties properties;
    private final ClientAddress addresses;
    private final Semaphore slots;
    private final Cache<String, AtomicInteger> perAddress = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(50_000)
            .build();

    public ChatLimits(ChatProperties properties, ClientAddress addresses) {
        this.properties = properties;
        this.addresses = addresses;
        this.slots = new Semaphore(properties.maxConcurrent());
    }

    public Permit acquire(HttpServletRequest request) {
        AtomicInteger used = perAddress.get(addresses.of(request), k -> new AtomicInteger());
        if (used.incrementAndGet() > properties.perIpPerMinute()) {
            throw new ChatException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        if (!slots.tryAcquire()) {
            throw new ChatException(HttpStatus.TOO_MANY_REQUESTS, "busy");
        }
        return new Permit(slots);
    }
}
