package org.mytpu.gateway.runtime;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Общий потолок вызовов одного клиента в минуту — до любых проверок: иначе вызовы неизвестных или запрещённых
 * инструментов шли бы без лимита и каждый писал бы строку в журнал.
 */
@Component
public class ClientRateLimit {
    private final int perMinute;
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(20_000)
            .build();

    public ClientRateLimit(@Value("${mytpu.gateway.limits.client-calls-per-minute:600}") int perMinute) {
        this.perMinute = perMinute;
    }

    public boolean allow(String clientId) {
        long minute = System.currentTimeMillis() / 60_000;
        return counters.get(clientId + '|' + minute, k -> new AtomicInteger()).incrementAndGet() <= perMinute;
    }
}
