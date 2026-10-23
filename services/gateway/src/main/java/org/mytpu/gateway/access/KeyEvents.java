package org.mytpu.gateway.access;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * События ключей ({@code gateway.key_events}) и WARN в лог — без самого ключа и полного адреса, только подсеть.
 * Пишутся в фоне ({@link BackgroundWriter}): ответ клиенту не зависит от БД, поэтому ловушку не отличить по времени или ошибке.
 * Одинаковые события ключа из одной подсети — не чаще раза в минуту (кроме ловушки — она пишется на каждый вызов);
 * на один ключ — не больше {@code mytpu.gateway.key-watch.max-events-per-minute} событий в минуту.
 */
@Component
public class KeyEvents {
    public static final String NEW_NETWORK = "key.new_network";
    public static final String NETWORK_BURST = "key.network_burst";
    public static final String SUSPENDED = "key.suspended";
    public static final String RESUMED = "key.resumed";
    public static final String DECOY_USED = "key.decoy_used";
    public static final String NETWORK_DENIED = "key.network_denied";

    private static final Logger log = LoggerFactory.getLogger(KeyEvents.class);

    private final JdbcClient jdbc;
    private final BackgroundWriter writer;
    private final int maxPerMinute;
    private final Cache<String, Boolean> recent = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();
    private final Cache<String, AtomicInteger> perClient = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();

    public KeyEvents(JdbcClient jdbc, BackgroundWriter writer,
                     @Value("${mytpu.gateway.key-watch.max-events-per-minute:20}") int maxPerMinute) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.maxPerMinute = maxPerMinute;
    }

    public void record(String clientId, String event, String subnet, String detail) {
        if (!DECOY_USED.equals(event) && recent.asMap().putIfAbsent(clientId + '|' + event + '|' + subnet, Boolean.TRUE) != null) {
            return;
        }
        if (perClient.get(clientId, k -> new AtomicInteger()).incrementAndGet() > maxPerMinute) {
            return;
        }
        log.warn("Событие ключа {}: {} подсеть={} {}", clientId, event, subnet, detail == null ? "" : detail);
        writer.submit("событие " + event, () -> jdbc
                .sql("INSERT INTO key_events (client_id, event, network, detail) VALUES (:client, :event, CAST(:network AS cidr), :detail)")
                .param("client", clientId)
                .param("event", event)
                .param("network", subnet)
                .param("detail", detail)
                .update());
    }

    /** Для команды {@code issue-key}: запись сразу, без фона — процесс сейчас завершится. */
    public void recordNow(String clientId, String event, String detail) {
        jdbc.sql("INSERT INTO key_events (client_id, event, detail) VALUES (:client, :event, :detail)")
                .param("client", clientId)
                .param("event", event)
                .param("detail", detail)
                .update();
    }
}
