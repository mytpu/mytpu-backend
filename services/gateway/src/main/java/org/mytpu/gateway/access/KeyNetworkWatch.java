package org.mytpu.gateway.access;

import java.net.InetAddress;
import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Учёт подсетей, из которых приходит ключ, — признак утечки. Новая подсеть → {@code key.new_network};
 * больше {@code max-networks-per-hour} разных подсетей за час (по БД — общий счёт для всех экземпляров шлюза) →
 * ключ {@code server} приостанавливается, для {@code person} — только событие {@code key.network_burst}
 * (мобильный интернет часто меняет подсеть). Запись — в фоне ({@link BackgroundWriter}) и не чаще раза в 10 минут
 * на пару «ключ — подсеть»; после {@code max-networks-per-hour × 4} подсетей за час новые не записываются — таблица не раздувается.
 */
@Component
public class KeyNetworkWatch {
    private final JdbcClient jdbc;
    private final KeyEvents events;
    private final ApiClientStore store;
    private final BackgroundWriter writer;
    private final int maxPerHour;
    private final Cache<String, Boolean> recent = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(50_000)
            .build();

    public KeyNetworkWatch(JdbcClient jdbc, KeyEvents events, ApiClientStore store, BackgroundWriter writer,
                           @Value("${mytpu.gateway.key-watch.max-networks-per-hour:3}") int maxPerHour) {
        this.jdbc = jdbc;
        this.events = events;
        this.store = store;
        this.writer = writer;
        this.maxPerHour = maxPerHour;
    }

    public void record(ApiClient client, InetAddress address) {
        String subnet = Cidr.subnetOf(address);
        if (recent.asMap().putIfAbsent(client.id() + '|' + subnet, Boolean.TRUE) != null) {
            return;
        }
        writer.submit("подсеть ключа", () -> check(client, subnet));
    }

    private void check(ApiClient client, String subnet) {
        long beforeCount = lastHour(client.id());
        if (beforeCount >= (long) maxPerHour * 4) {
            burst(client, subnet, beforeCount + 1);
            return;
        }
        boolean inserted = jdbc.sql("""
                        INSERT INTO key_networks (client_id, network) VALUES (:client, CAST(:network AS cidr))
                        ON CONFLICT (client_id, network) DO UPDATE SET last_seen = now()
                        RETURNING (xmax = 0)
                        """)
                .param("client", client.id())
                .param("network", subnet)
                .query(Boolean.class)
                .single();
        if (inserted) {
            events.record(client.id(), KeyEvents.NEW_NETWORK, subnet, null);
        }
        long distinct = lastHour(client.id());
        if (distinct > maxPerHour) {
            burst(client, subnet, distinct);
        }
    }

    private long lastHour(String clientId) {
        return jdbc.sql("SELECT count(*) FROM key_networks WHERE client_id = :client AND last_seen > now() - interval '1 hour'")
                .param("client", clientId)
                .query(Long.class)
                .single();
    }

    private void burst(ApiClient client, String subnet, long distinct) {
        String detail = distinct + " подсетей за час";
        if (client.kind() == ApiClient.Kind.SERVER) {
            if (store.suspend(client.id(), detail)) {
                events.record(client.id(), KeyEvents.SUSPENDED, subnet, detail);
            }
        } else {
            events.record(client.id(), KeyEvents.NETWORK_BURST, subnet, detail);
        }
    }
}
