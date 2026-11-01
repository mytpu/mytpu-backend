package org.mytpu.gateway.access;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Неудачные попытки предъявить ключ — по адресу клиента (IPv4 или IPv6 /64) и по широкой подсети (/24 или /48).
 * Больше порога за минуту — 429 без обращения к БД: перебор ключей не нагружает пул соединений и не вытесняет
 * настоящие ключи из кэша. Подсеть считается отдельно: у кого IPv6 /48, у того 65 536 «адресов» /64.
 */
@Component
public class AuthFailureLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int maxPerMinute;
    private final int maxPerSubnetPerMinute;
    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(20_000)
            .build();
    private final Cache<String, AtomicInteger> subnetFailures = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(20_000)
            .build();

    public AuthFailureLimiter(@Value("${mytpu.gateway.access.max-failures-per-minute:30}") int maxPerMinute,
                              @Value("${mytpu.gateway.access.max-failures-per-subnet-per-minute:300}") int maxPerSubnetPerMinute) {
        this.maxPerMinute = maxPerMinute;
        this.maxPerSubnetPerMinute = maxPerSubnetPerMinute;
    }

    public boolean blocked(String ip, String subnet) {
        return over(failures.getIfPresent(ip), maxPerMinute) || over(subnetFailures.getIfPresent(subnet), maxPerSubnetPerMinute);
    }

    /** Для {@code Retry-After}: окно счёта, после него адрес снова может пробовать. */
    public long retryAfterSeconds() {
        return WINDOW.toSeconds();
    }

    public void recordFailure(String ip, String subnet) {
        failures.get(ip, k -> new AtomicInteger()).incrementAndGet();
        subnetFailures.get(subnet, k -> new AtomicInteger()).incrementAndGet();
    }

    private static boolean over(AtomicInteger count, int limit) {
        return count != null && count.get() >= limit;
    }
}
