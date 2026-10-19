package org.mytpu.gateway.access;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Неудачные попытки предъявить ключ — по адресу клиента. Больше порога за минуту — 429 без обращения к БД:
 * перебор ключей не нагружает пул соединений и не вытесняет настоящие ключи из кэша.
 */
@Component
public class AuthFailureLimiter {
    private final int maxPerMinute;
    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(20_000)
            .build();

    public AuthFailureLimiter(@Value("${mytpu.gateway.access.max-failures-per-minute:30}") int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    public boolean blocked(String ip) {
        AtomicInteger count = failures.getIfPresent(ip);
        return count != null && count.get() >= maxPerMinute;
    }

    public void recordFailure(String ip) {
        failures.get(ip, k -> new AtomicInteger()).incrementAndGet();
    }
}
