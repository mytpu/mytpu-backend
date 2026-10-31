package org.mytpu.gateway.access;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Адрес клиента. {@code X-Forwarded-For} учитывается только если запрос пришёл от доверенного прокси
 * ({@code mytpu.gateway.trusted-proxies}, по умолчанию никого): берётся самый правый адрес, который не прокси.
 * Иначе заголовок подделывается любым клиентом и обходит ограничение по сетям и лимит неудачных попыток.
 */
@Component
public class ClientAddress {
    private final List<Cidr> trusted;

    public ClientAddress(@Value("${mytpu.gateway.trusted-proxies:}") List<String> proxies) {
        this.trusted = proxies.stream().filter(p -> !p.isBlank()).map(Cidr::parse).toList();
    }

    public InetAddress of(HttpServletRequest request) {
        InetAddress remote = InetAddress.ofLiteral(request.getRemoteAddr());
        // Прокси может добавить адрес и отдельной строкой заголовка — склеиваем все по порядку
        String forwarded = request.getHeaders("X-Forwarded-For").hasMoreElements()
                ? String.join(",", java.util.Collections.list(request.getHeaders("X-Forwarded-For")))
                : null;
        if (forwarded == null || !trusted(remote)) {
            return remote;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            InetAddress hop = literal(hops[i].trim());
            if (hop == null) {
                return remote;
            }
            if (!trusted(hop)) {
                return hop;
            }
        }
        return remote;
    }

    /**
     * Строгий вариант для консоли: запрос от доверенного прокси без разборчивого {@code X-Forwarded-For} — адрес клиента
     * неизвестен (пусто), а не адрес самого прокси: иначе прокси на localhost открыл бы консоль всем, кто дошёл до прокси.
     */
    public Optional<InetAddress> known(HttpServletRequest request) {
        InetAddress remote = InetAddress.ofLiteral(request.getRemoteAddr());
        if (!trusted(remote)) {
            return Optional.of(remote);
        }
        InetAddress client = of(request);
        return client.equals(remote) || trusted(client) ? Optional.empty() : Optional.of(client);
    }

    public boolean trustedEmpty() {
        return trusted.isEmpty();
    }

    public boolean hasTrusted() {
        return !trusted.isEmpty();
    }

    public boolean isTrusted(InetAddress address) {
        return address != null && trusted(address);
    }

    private boolean trusted(InetAddress address) {
        return trusted.stream().anyMatch(c -> c.contains(address));
    }

    private static InetAddress literal(String value) {
        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
