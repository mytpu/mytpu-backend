package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Консоль — только на своём порту (specs/console-port/spec.md): на основном порту {@code /admin/api/**} и
 * {@code /internal/**} — 404 при любых заголовках; на порту консоли решает адрес соединения, а не {@code X-Forwarded-For}.
 */
class ConsolePortTest {
    private static final int MAIN = 8080;
    private static final int CONSOLE = 8090;
    // Адрес шлюза сети edge: с него приходят и Caddy (на основной порт), и туннель (на порт консоли)
    private static final String EDGE_GATEWAY = "172.31.240.1";

    private final AdminNetworkFilter filter = new AdminNetworkFilter(List.of(EDGE_GATEWAY + "/32", "127.0.0.1/32"), CONSOLE, MAIN);

    private record Result(MockHttpServletResponse response, MockFilterChain chain) {
        boolean passed() {
            return chain.getRequest() != null;
        }
    }

    private Result execute(int port, String path, String remote, String... forwarded) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
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

    @ParameterizedTest
    @ValueSource(strings = {"/admin/api", "/admin/api/me", "/admin/api/keys/cli_1/suspend", "/internal/catalog"})
    void consolePathsOnMainPortAre404EvenWithForwardedLocalhost(String path) throws Exception {
        for (Result result : List.of(
                execute(MAIN, path, EDGE_GATEWAY, "127.0.0.1"),
                execute(MAIN, path, "127.0.0.1"),
                execute(MAIN, path, "127.0.0.1", "127.0.0.1"))) {
            assertThat(result.response().getStatus()).isEqualTo(404);
            assertThat(result.passed()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mcp", "/actuator/health", "/chat/tools/x"})
    void otherPathsOnMainPortAreNotTouched(String path) throws Exception {
        Result result = execute(MAIN, path, "203.0.113.7");

        assertThat(result.passed()).isTrue();
        assertThat(result.response().getHeader("Content-Security-Policy")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/api/me", "/internal/catalog"})
    void consoleOnConsolePortFromAllowedConnectionPasses(String path) throws Exception {
        Result result = execute(CONSOLE, path, EDGE_GATEWAY);

        assertThat(result.passed()).isTrue();
        assertThat(result.response().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(result.response().getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(result.response().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void consolePortIgnoresForwardedFor() throws Exception {
        // Чужой контейнер сети edge не станет «localhost» заголовком
        Result spoofed = execute(CONSOLE, "/admin/api/me", "172.31.240.5", "127.0.0.1");
        assertThat(spoofed.response().getStatus()).isEqualTo(404);
        assertThat(spoofed.passed()).isFalse();

        // А разрешённое соединение не теряет доступ из-за заголовка
        Result allowed = execute(CONSOLE, "/admin/api/me", EDGE_GATEWAY, "203.0.113.7");
        assertThat(allowed.passed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/api/me", "/actuator/health"})
    void consolePortFromForeignConnectionIs404(String path) throws Exception {
        Result result = execute(CONSOLE, path, "203.0.113.7");

        assertThat(result.response().getStatus()).isEqualTo(404);
        assertThat(result.passed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mcp", "/chat/tools/x", "/admin", "/admin/apix", "/actuator/env", "/"})
    void consolePortServesOnlyConsoleAndHealth(String path) throws Exception {
        Result result = execute(CONSOLE, path, EDGE_GATEWAY);

        assertThat(result.response().getStatus()).isEqualTo(404);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void healthOnConsolePortPassesForTunnelCheck() throws Exception {
        Result result = execute(CONSOLE, "/actuator/health", EDGE_GATEWAY);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void consolePortEqualToMainPortFailsAtCreation() {
        assertThatThrownBy(() -> new AdminNetworkFilter(List.of("127.0.0.1/32"), MAIN, MAIN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void connectorIsAddedOnConsolePortWithLimits() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        new ConsolePortConfig().consoleConnector(CONSOLE).customize(factory);

        assertThat(factory.getAdditionalConnectors()).hasSize(1);
        Connector connector = factory.getAdditionalConnectors().getFirst();
        assertThat(connector.getPort()).isEqualTo(CONSOLE);
        assertThat(connector.getProperty("connectionTimeout")).isEqualTo(5000);
        assertThat(connector.getProperty("maxConnections")).isEqualTo(20);
    }
}
