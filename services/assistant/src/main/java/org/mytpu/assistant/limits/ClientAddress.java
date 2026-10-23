package org.mytpu.assistant.limits;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.assistant.config.ChatProperties;
import org.springframework.stereotype.Component;

/**
 * Адрес клиента для лимитов. {@code X-Forwarded-For} учитывается, только если соединение пришло от доверенного прокси
 * ({@code mytpu.chat.trusted-proxies}); берётся самый правый адрес, который не прокси. Иначе заголовок подделывается
 * и обходит лимит «запросов с адреса». Правило то же, что у gateway ({@code access.ClientAddress}).
 */
@Component
public class ClientAddress {
    private record Net(byte[] network, int prefix) {
        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            return bytes.length == network.length && Arrays.equals(mask(bytes, prefix), network);
        }
    }

    private final List<Net> trusted;

    public ClientAddress(ChatProperties properties) {
        this.trusted = properties.trustedProxies().stream().map(ClientAddress::parse).toList();
    }

    /** Ключ лимита: адрес IPv4 или подсеть /64 для IPv6 (у одного клиента IPv6 обычно вся /64 — перебор адресов не обходит лимит). */
    public String of(HttpServletRequest request) {
        InetAddress client = client(request);
        if (client instanceof Inet4Address) {
            return client.getHostAddress();
        }
        try {
            return InetAddress.getByAddress(mask(client.getAddress(), 64)).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private InetAddress client(HttpServletRequest request) {
        InetAddress remote = InetAddress.ofLiteral(request.getRemoteAddr());
        List<String> forwarded = Collections.list(request.getHeaders("X-Forwarded-For"));
        if (forwarded.isEmpty() || !trusted(remote)) {
            return remote;
        }
        String[] hops = String.join(",", forwarded).split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            InetAddress hop;
            try {
                hop = InetAddress.ofLiteral(hops[i].trim());
            } catch (IllegalArgumentException e) {
                return remote;
            }
            if (!trusted(hop)) {
                return hop;
            }
        }
        return remote;
    }

    private boolean trusted(InetAddress address) {
        return trusted.stream().anyMatch(n -> n.contains(address));
    }

    private static Net parse(String value) {
        String text = value.trim();
        int slash = text.indexOf('/');
        InetAddress address = InetAddress.ofLiteral(slash < 0 ? text : text.substring(0, slash));
        int bits = address.getAddress().length * 8;
        int prefix = slash < 0 ? bits : Integer.parseInt(text.substring(slash + 1));
        if (prefix < 0 || prefix > bits) {
            throw new IllegalArgumentException("Неверная сеть в mytpu.chat.trusted-proxies: " + value);
        }
        return new Net(mask(address.getAddress(), prefix), prefix);
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] result = address.clone();
        for (int i = 0; i < result.length; i++) {
            int keep = Math.max(0, Math.min(8, prefix - i * 8));
            result[i] = (byte) (result[i] & (0xFF << (8 - keep)));
        }
        return result;
    }
}
