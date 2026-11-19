package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Адрес клиента из {@code X-Forwarded-For} (docs/SECURITY.md, раздел 14: заголовок учитывается только от доверенного
 * прокси; без пригодного заголовка адрес клиента неизвестен).
 */
class ClientAddressTest {
    private static final String PROXY = "172.18.0.2";
    private final ClientAddress addresses = new ClientAddress(List.of("172.18.0.0/16", "::1/128"));

    private static MockHttpServletRequest from(String remote, String... forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp");
        request.setRemoteAddr(remote);
        for (String line : forwarded) {
            request.addHeader("X-Forwarded-For", line);
        }
        return request;
    }

    private static InetAddress ip(String literal) {
        return InetAddress.ofLiteral(literal);
    }

    @Test
    void headerFromUntrustedRemoteIsIgnored() {
        MockHttpServletRequest request = from("198.51.100.9", "127.0.0.1");

        assertThat(addresses.of(request)).isEqualTo(ip("198.51.100.9"));
        assertThat(addresses.known(request)).contains(ip("198.51.100.9"));
    }

    @Test
    void noTrustedProxiesByDefaultMeansHeaderIgnored() {
        ClientAddress none = new ClientAddress(List.of(""));

        assertThat(none.of(from(PROXY, "203.0.113.7"))).isEqualTo(ip(PROXY));
        assertThat(none.trustedEmpty()).isTrue();
    }

    @Test
    void trustedProxyGivesRightmostUntrustedHop() {
        // Клиент может вписать что угодно слева; правый недоверенный адрес дописан нашим прокси
        MockHttpServletRequest request = from(PROXY, "127.0.0.1, 203.0.113.7, 172.18.0.5");

        assertThat(addresses.of(request)).isEqualTo(ip("203.0.113.7"));
        assertThat(addresses.known(request)).contains(ip("203.0.113.7"));
    }

    @Test
    void severalHeaderLinesAreJoinedInOrder() {
        MockHttpServletRequest request = from(PROXY, "198.51.100.1", "203.0.113.7");

        assertThat(addresses.of(request)).isEqualTo(ip("203.0.113.7"));
    }

    @Test
    void severalHeaderLinesWithTrustedLastLineGiveEarlierLine() {
        MockHttpServletRequest request = from(PROXY, "203.0.113.7", "172.18.0.9");

        assertThat(addresses.of(request)).isEqualTo(ip("203.0.113.7"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "203.0.113.7:4431", "[2001:db8::1]:443", "", "203.0.113.7, garbage", "evil.example.com"})
    void garbageInChainFallsBackToProxyAndIsUnknown(String header) {
        MockHttpServletRequest request = from(PROXY, header);

        assertThat(addresses.of(request)).isEqualTo(ip(PROXY));
        assertThat(addresses.known(request)).isEmpty();
    }

    @Test
    void knownIsEmptyForProxyWithoutHeader() {
        MockHttpServletRequest request = from(PROXY);

        assertThat(addresses.of(request)).isEqualTo(ip(PROXY));
        assertThat(addresses.known(request)).isEmpty();
    }

    @Test
    void knownIsEmptyWhenAllHopsTrusted() {
        MockHttpServletRequest request = from(PROXY, "172.18.0.7, 172.18.0.8");

        assertThat(addresses.of(request)).isEqualTo(ip(PROXY));
        assertThat(addresses.known(request)).isEmpty();
    }

    @Test
    void ipv6RemoteAndHops() {
        assertThat(addresses.of(from("::1", "2001:db8:1::7"))).isEqualTo(ip("2001:db8:1::7"));
        assertThat(addresses.known(from("::1"))).isEmpty();
        assertThat(addresses.of(from("2001:db8:2::9", "203.0.113.7"))).isEqualTo(ip("2001:db8:2::9"));
        assertThat(addresses.of(from(PROXY, "2001:db8:1::7, ::1"))).isEqualTo(ip("2001:db8:1::7"));
    }

    @Test
    void isTrustedChecksConfiguredNetworks() {
        assertThat(addresses.isTrusted(ip("172.18.255.1"))).isTrue();
        assertThat(addresses.isTrusted(ip("172.19.0.1"))).isFalse();
        assertThat(addresses.isTrusted(null)).isFalse();
    }
}
