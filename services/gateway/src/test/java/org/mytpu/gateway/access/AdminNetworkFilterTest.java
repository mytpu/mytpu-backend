package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Сеть консоли проверяется до ключа (docs/SECURITY.md, раздел 15): на порту консоли соединение вне
 * {@code mytpu.admin.allowed-networks} получает 404, ответы консоли — с {@code nosniff}, CSP и {@code no-store}.
 * Адрес — только адрес соединения; разделение портов — в {@link ConsolePortTest}.
 */
class AdminNetworkFilterTest {
    private static final int MAIN = 8080;
    private static final int CONSOLE = 8090;
    private static final String PROXY = "172.18.0.2";

    private final AdminNetworkFilter filter =
            new AdminNetworkFilter(List.of("127.0.0.1/32", "::1/128", "10.20.0.0/16"), CONSOLE, MAIN);

    private record Result(MockHttpServletResponse response, MockFilterChain chain) {
        boolean passed() {
            return chain.getRequest() != null;
        }
    }

    private Result execute(int port, String method, String path, String remote, String... forwarded) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setLocalPort(port);
        request.setRemoteAddr(remote);
        for (String line : forwarded) {
            request.addHeader("X-Forwarded-For", line);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain);
    }

    private Result console(String method, String path, String remote, String... forwarded) throws Exception {
        return execute(CONSOLE, method, path, remote, forwarded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/api", "/admin/api/me", "/admin/api/keys/cli_1/suspend", "/internal/catalog"})
    void foreignAddressGets404AndChainIsNotCalled(String path) throws Exception {
        Result result = console("GET", path, "203.0.113.7");

        assertThat(result.response().getStatus()).isEqualTo(404);
        assertThat(result.passed()).isFalse();
        assertThat(result.response().getContentAsString()).isEmpty();
    }

    @Test
    void foreignAddressGets404ForWritesToo() throws Exception {
        Result result = console("POST", "/admin/api/keys", "198.51.100.1");

        assertThat(result.response().getStatus()).isEqualTo(404);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void spoofedForwardedForFromForeignRemoteGets404() throws Exception {
        Result result = console("GET", "/admin/api/me", "203.0.113.7", "127.0.0.1");

        assertThat(result.response().getStatus()).isEqualTo(404);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void proxyWithoutHeaderGets404() throws Exception {
        Result result = console("GET", "/admin/api/me", PROXY);

        assertThat(result.response().getStatus()).isEqualTo(404);
        assertThat(result.passed()).isFalse();
    }

    // До Ш2 разрешённый клиент за доверенным прокси проходил по X-Forwarded-For; теперь заголовок на порту консоли
    // не читается — решает только адрес соединения (specs/console-port/spec.md)
    @ParameterizedTest
    @ValueSource(strings = {"10.20.0.5", "127.0.0.1", "127.0.0.1, 203.0.113.7"})
    void allowedAddressInForwardedForDoesNotOpenConsole(String forwarded) throws Exception {
        Result result = console("GET", "/internal/catalog", PROXY, forwarded);

        assertThat(result.response().getStatus()).isEqualTo(404);
        assertThat(result.passed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "10.20.3.4"})
    void allowedAddressPassesWithConsoleHeaders(String remote) throws Exception {
        Result result = console("GET", "/admin/api/me", remote);

        assertThat(result.passed()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(result.response().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.response().getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(result.response().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    // «/admin» и «/internal» без хвоста — источник — текущее поведение: обработчиков на этих путях нет
    @ParameterizedTest
    @ValueSource(strings = {"/mcp", "/admin/apix", "/admin", "/internal", "/actuator/health", "/chat/tools/x"})
    void otherPathsOnMainPortAreNotFiltered(String path) throws Exception {
        Result result = execute(MAIN, "GET", path, "203.0.113.7");

        assertThat(result.passed()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(result.response().getHeader("Content-Security-Policy")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "not-a-network", "10.0.0.0/x", "::1/129"})
    void invalidNetworkFailsAtCreation(String network) {
        assertThatThrownBy(() -> new AdminNetworkFilter(List.of(network), CONSOLE, MAIN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
