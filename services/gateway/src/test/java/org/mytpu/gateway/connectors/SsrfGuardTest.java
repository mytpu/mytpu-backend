package org.mytpu.gateway.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Защита коннекторов от SSRF (docs/SECURITY.md, раздел 15): только https:443 и имя хоста с точкой; адреса, в которые
 * резолвится имя, — только публичный интернет; IPv6 с вложенным IPv4 проверяется по вложенному адресу.
 */
class SsrfGuardTest {
    private final SsrfGuard guard = new SsrfGuard(List.of(""));

    /**
     * Адрес как его отдаст резолвер. {@code ::ffff:a.b.c.d} Java превращает в IPv4 — здесь строится именно
     * {@link Inet6Address}, чтобы проверялась ветка вложенного IPv4, а не обычная IPv4.
     */
    private static InetAddress address(String literal) {
        InetAddress parsed = InetAddress.ofLiteral(literal);
        if (parsed instanceof Inet4Address && literal.contains(":")) {
            byte[] v4 = parsed.getAddress();
            byte[] v6 = new byte[16];
            v6[10] = (byte) 0xFF;
            v6[11] = (byte) 0xFF;
            System.arraycopy(v4, 0, v6, 12, 4);
            try {
                return Inet6Address.getByAddress(null, v6, -1);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
        return parsed;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.255.255.254", "::1",                                  // loopback
            "0.0.0.0", "::",                                                       // «любой»
            "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1",             // частные
            "169.254.169.254", "fe80::1",                                          // link-local, метаданные облака
            "100.64.0.1", "100.127.255.254",                                       // CGNAT 100.64/10
            "192.0.2.1", "198.51.100.1", "203.0.113.1", "2001:db8::1",              // документационные
            "198.18.0.1", "192.0.0.8", "240.0.0.1", "255.255.255.255",             // зарезервированные
            "224.0.0.1", "ff02::1",                                                // multicast
            "fc00::1", "fd12:3456:789a::1",                                        // ULA fc00::/7
            "2001:0:4136:e378:8000:63bf:3fff:fdd2",                                // Teredo 2001::/32
            "100::1"                                                               // discard 100::/64
    })
    void rejectsNonPublicAddresses(String literal) {
        assertThat(guard.allowed(address(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254",     // IPv4-mapped
            "::127.0.0.1", "::10.0.0.1",                                           // IPv4-compatible
            "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe",                               // NAT64 64:ff9b::/96
            "2002:7f00:1::", "2002:a00:1::", "2002:a9fe:a9fe::"                    // 6to4 2002::/16
    })
    void rejectsIpv6WithEmbeddedNonPublicIpv4(String literal) {
        InetAddress embedded = address(literal);
        assertThat(embedded).as(literal).isInstanceOf(Inet6Address.class);
        assertThat(guard.allowed(embedded)).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "93.184.215.14", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "192.0.3.1",
            "2a00:1450:4010:c05::8a", "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:808:808::"})
    void allowsPublicAddresses(String literal) {
        assertThat(guard.allowed(address(literal))).as(literal).isTrue();
    }

    @Test
    void nat64ExtractionRequiresAll96PrefixBits() {
        byte[] standard = InetAddress.ofLiteral("64:ff9b::").getAddress();
        assertThat(SsrfGuard.embeddedIpv4(standard)).isEqualTo(InetAddress.ofLiteral("0.0.0.0"));

        byte[] local = InetAddress.ofLiteral("64:ff9b:1::").getAddress();
        assertThat(SsrfGuard.embeddedIpv4(local)).isNull();
        assertThat(guard.allowed(InetAddress.ofLiteral("64:ff9b:1::"))).isFalse();
    }

    @Test
    void deniedNetworksAreRejected() {
        SsrfGuard withDenied = new SsrfGuard(List.of("8.8.8.0/24", " 2a00:1450::/32 "));

        assertThat(withDenied.allowed(address("8.8.8.8"))).isFalse();
        assertThat(withDenied.allowed(address("2a00:1450:4010:c05::8a"))).isFalse();
        assertThat(withDenied.allowed(address("::ffff:8.8.8.8"))).isFalse();
        assertThat(withDenied.allowed(address("8.8.4.4"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "not-a-network", "::/129"})
    void invalidDeniedNetworkFailsAtCreation(String network) {
        assertThatThrownBy(() -> new SsrfGuard(List.of(network))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "169.254.169.254", "::1", "[::1]", "localhost", "rag", "intranet", "LOCALHOST",
            "", "example.com.", "-bad.example.com", "2130706433", "0x7f.0.0.1"})
    void resolverRejectsNonNamesWithoutDns(String host) {
        // Эти имена отсекаются до обращения к DNS, поэтому тест не зависит от сети
        assertThatThrownBy(() -> guard.resolve(host)).isInstanceOf(UnknownHostException.class);
        assertThat(guard.resolvesPublic(host)).isFalse();
    }

    @Test
    void resolverRejectsNullHost() {
        assertThatThrownBy(() -> guard.resolve(null)).isInstanceOf(UnknownHostException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "http://api.example.com/v1, url_not_https",
            "ftp://api.example.com/v1, url_not_https",
            "//api.example.com/v1, url_not_https",
            "https://api.example.com:8443/v1, url_port",
            "https://api.example.com:80/v1, url_port",
            "https://127.0.0.1/v1, url_host",
            "https://[::1]/v1, url_host",
            "https://[::ffff:127.0.0.1]/v1, url_host",
            "https://localhost/v1, url_host",
            "https://rag/v1, url_host",
            "https://rag:443/v1, url_host",
            "https://user:pass@api.example.com/v1, url_extra_parts",
            "https://api.example.com/v1?x=1, url_extra_parts",
            "https://api.example.com/v1#x, url_extra_parts",
            "https://api example.com/, url_invalid"
    })
    void urlProblems(String url, String problem) {
        assertThat(SsrfGuard.urlProblem(url)).isEqualTo(problem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.example.com/v1", "https://api.example.com:443/v1", "HTTPS://Api.Example.COM/v1"})
    void urlAccepted(String url) {
        assertThat(SsrfGuard.urlProblem(url)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.example.com/v1", "https://api.example.com:8443/v1", "https://127.0.0.1/v1",
            "https://localhost/v1", "https://rag/v1", "https://u@api.example.com/v1", "https://[::1]/v1"})
    void requestRejected(String url) {
        assertThat(SsrfGuard.requestAllowed(URI.create(url))).isFalse();
    }

    @Test
    void requestWithQueryAccepted() {
        assertThat(SsrfGuard.requestAllowed(URI.create("https://api.example.com/v1?q=x"))).isTrue();
        assertThat(SsrfGuard.requestAllowed(null)).isFalse();
    }
}
