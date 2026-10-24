package org.mytpu.gateway.connectors;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.DnsResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Защита от SSRF для коннекторов: только {@code https} на порт 443, хост — имя с точкой (не IP, не {@code rag},
 * не {@code localhost}), и каждый адрес, в который имя резолвится, — публичный интернет. Проверка стоит в
 * {@link DnsResolver} HTTP-клиента: соединение идёт ровно к проверенным адресам, подмена DNS между проверкой
 * и соединением (DNS rebinding) не проходит. Сети Docker, если они вне частных диапазонов, — {@code mytpu.connectors.denied-networks}.
 */
@Component
public class SsrfGuard implements DnsResolver {
    private static final Pattern HOST = Pattern.compile("(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    private final List<Network> denied;

    public SsrfGuard(@Value("${mytpu.connectors.denied-networks:}") List<String> denied) {
        this.denied = denied.stream().filter(n -> !n.isBlank()).map(Network::parse).toList();
    }

    /** Адрес коннектора допустим (без обращения к DNS): https, порт 443, хост — имя, без логина в адресе. */
    public static String urlProblem(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "url_invalid";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "url_not_https";
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "url_extra_parts";
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return "url_port";
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return HOST.matcher(host).matches() ? null : "url_host";
    }

    /** Запрос коннектора (с параметрами): https, порт 443, без логина в адресе, хост — имя. */
    public static boolean requestAllowed(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getHost() != null && HOST.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches();
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        String name = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (!HOST.matcher(name).matches()) {
            throw new UnknownHostException("коннектору разрешены только имена хостов в интернете");
        }
        InetAddress[] all = InetAddress.getAllByName(name);
        for (InetAddress address : all) {
            if (!allowed(address)) {
                throw new UnknownHostException("адрес коннектора вне публичного интернета");
            }
        }
        return all;
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    /** Проверка при сохранении: все адреса имени публичные. */
    public boolean resolvesPublic(String host) {
        try {
            resolve(host);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    boolean allowed(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int a0 = b[0] & 0xFF, a1 = b[1] & 0xFF, a2 = b[2] & 0xFF;
            if (a0 == 0 || a0 >= 240                              // «этот» сеть, зарезервированные и широковещательный
                    || (a0 == 100 && a1 >= 64 && a1 <= 127)        // CGNAT 100.64/10
                    || (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) // 192.0.0/24, документация 192.0.2/24
                    || (a0 == 198 && (a1 == 18 || a1 == 19))       // замеры 198.18/15
                    || (a0 == 198 && a1 == 51 && a2 == 100)        // документация
                    || (a0 == 203 && a1 == 0 && a2 == 113)) {      // документация
                return false;
            }
        } else if (address instanceof Inet6Address) {
            // IPv6 с IPv4 внутри (::ffff:a.b.c.d, ::a.b.c.d, 64:ff9b::/96, 6to4 2002::/16): решает вложенный IPv4 —
            // иначе ::ffff:127.0.0.1 прошёл бы как «публичный IPv6», а сокет соединился бы с localhost
            InetAddress embedded = embeddedIpv4(b);
            if (embedded != null) {
                return allowed(embedded);
            }
            int h0 = b[0] & 0xFF, h1 = b[1] & 0xFF, h2 = b[2] & 0xFF, h3 = b[3] & 0xFF;
            if ((h0 & 0xFE) == 0xFC                                // уникальные локальные fc00::/7
                    || h0 == 0x00                                  // остаток ::/8 (зарезервировано)
                    || (h0 == 0x01 && h1 == 0x00 && h2 == 0 && h3 == 0) // discard 100::/64
                    || (h0 == 0x20 && h1 == 0x01 && h2 == 0x0D && h3 == 0xB8) // документация 2001:db8::/32
                    || (h0 == 0x20 && h1 == 0x01 && h2 == 0x00 && h3 == 0x00) // Teredo 2001::/32
                    || (h0 == 0x00 && h1 == 0x64 && h2 == 0xFF && h3 == 0x9B && (b[4] & 0xFF) == 0x00 && (b[5] & 0xFF) == 0x01)) {
                return false;                                      // локальный NAT64 64:ff9b:1::/48
            }
        }
        return denied.stream().noneMatch(n -> n.contains(address));
    }

    /** IPv4, вложенный в IPv6: IPv4-mapped, IPv4-compatible, NAT64 64:ff9b::/96, 6to4 2002::/16; иначе {@code null}. */
    static InetAddress embeddedIpv4(byte[] b) {
        boolean zeros80 = true;
        for (int i = 0; i < 10; i++) {
            zeros80 &= b[i] == 0;
        }
        int at = -1;
        if (zeros80 && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            at = 12;                                               // ::ffff:a.b.c.d
        } else if (zeros80 && b[10] == 0 && b[11] == 0 && ((b[12] | b[13] | b[14]) != 0 || (b[15] & 0xFF) > 1)) {
            at = 12;                                               // ::a.b.c.d (кроме :: и ::1)
        } else if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            at = 12;                                               // 64:ff9b::a.b.c.d
        } else if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            at = 2;                                                // 2002:aabb:ccdd::
        }
        if (at < 0) {
            return null;
        }
        try {
            return InetAddress.getByAddress(new byte[]{b[at], b[at + 1], b[at + 2], b[at + 3]});
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Сеть CIDR из настройки. */
    record Network(byte[] network, int prefix) {
        static Network parse(String value) {
            String text = value.trim();
            int slash = text.indexOf('/');
            InetAddress address = InetAddress.ofLiteral(slash < 0 ? text : text.substring(0, slash));
            int bits = address.getAddress().length * 8;
            int prefix = slash < 0 ? bits : Integer.parseInt(text.substring(slash + 1));
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Неверная сеть в mytpu.connectors.denied-networks: " + value);
            }
            return new Network(mask(address.getAddress(), prefix), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            return bytes.length == network.length && java.util.Arrays.equals(mask(bytes, prefix), network);
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
}
