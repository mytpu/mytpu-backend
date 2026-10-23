package org.mytpu.gateway.access;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ограничение ключа по сетям: пустой список — любые адреса; иначе адрес клиента должен входить в одну из сетей.
 * Разобранные сети запоминаются: их столько, сколько выдано администратором, разбирать их на каждый вызов незачем.
 */
final class NetworkPolicy {
    private static final Map<String, Cidr> PARSED = new ConcurrentHashMap<>();

    private NetworkPolicy() {
    }

    static boolean allows(ApiClient client, InetAddress address) {
        return client.allowedNetworks().isEmpty()
                || client.allowedNetworks().stream().map(n -> PARSED.computeIfAbsent(n, Cidr::parse)).anyMatch(c -> c.contains(address));
    }
}
