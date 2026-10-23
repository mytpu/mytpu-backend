package org.mytpu.gateway.access;

import java.net.InetAddress;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ограничение ключа по сетям: пустой список — любые адреса; иначе адрес клиента должен входить в одну из сетей.
 * Разобранные сети запоминаются (кэш ограничен — записи в БД не раздуют память). Сеть, которая не разбирается,
 * ничего не разрешает: закрыто по умолчанию, ключ получает отказ, а в лог — WARN.
 */
final class NetworkPolicy {
    private static final Logger log = LoggerFactory.getLogger(NetworkPolicy.class);
    private static final Cache<String, Optional<Cidr>> PARSED = Caffeine.newBuilder().maximumSize(10_000).build();

    private NetworkPolicy() {
    }

    static boolean allows(ApiClient client, InetAddress address) {
        return client.allowedNetworks().isEmpty()
                || client.allowedNetworks().stream()
                .map(network -> PARSED.get(network, n -> parse(client, n)))
                .anyMatch(cidr -> cidr.isPresent() && cidr.get().contains(address));
    }

    private static Optional<Cidr> parse(ApiClient client, String network) {
        try {
            return Optional.of(Cidr.parse(network));
        } catch (RuntimeException e) {
            log.warn("Ключ {}: сеть в allowed_networks не разобрана ({}) — она ничего не разрешает", client.id(),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
