package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Тесты разграничения доступа для роли {@code chat.service} и входа {@code /chat/**} (specs/assistant/spec.md, шаг 4).
 */
class ChatAccessFilterTest {
    private ApiClientStore store;
    private AuthFailureLimiter failures;
    private ClientAddress addresses;
    private GeoPolicy geoPolicy;
    private McpKeyFilter filter;

    private final String chatKey = ApiKeys.generate();
    private final String mcpKey = ApiKeys.generate();
    private final String consoleKey = ApiKeys.generate();

    private ApiClient chatClient;
    private ApiClient mcpClient;
    private ApiClient consoleClient;

    @BeforeEach
    void setUp() {
        store = mock(ApiClientStore.class);
        failures = mock(AuthFailureLimiter.class);
        addresses = mock(ClientAddress.class);
        KeyEvents events = mock(KeyEvents.class);
        KeyNetworkWatch watch = mock(KeyNetworkWatch.class);
        geoPolicy = mock(GeoPolicy.class);
        DecoyGroups decoys = mock(DecoyGroups.class);

        when(addresses.of(any())).thenReturn(InetAddress.getLoopbackAddress());
        when(addresses.known(any())).thenReturn(Optional.of(InetAddress.getLoopbackAddress()));
        when(failures.blocked(any(), any())).thenReturn(false);
        when(geoPolicy.check(any(ApiClient.class), org.mockito.ArgumentMatchers.<Optional<InetAddress>>any())).thenReturn(GeoPolicy.Decision.ALLOW);

        chatClient = createClient("cli_chat", Set.of(ApiClient.CHAT_SERVICE));
        mcpClient = createClient("cli_mcp", Set.of());
        consoleClient = createClient("cli_console", Set.of(ApiClient.CONSOLE_ADMIN));

        registerKey(chatKey, chatClient);
        registerKey(mcpKey, mcpClient);
        registerKey(consoleKey, consoleClient);

        filter = new McpKeyFilter(store, failures, addresses, events, watch, geoPolicy,
                FilterTestSupport.messages(), FilterTestSupport.json(), decoys, "");
    }

    private void registerKey(String key, ApiClient client) {
        when(store.cached(key)).thenReturn(true);
        when(store.lookup(eq(key))).thenReturn(KeyLookup.found(client));
    }

    private ApiClient createClient(String id, Set<String> roles) {
        ApiClient client = mock(ApiClient.class);
        when(client.id()).thenReturn(id);
        when(client.roles()).thenReturn(roles);
        when(client.hasChatServiceRole()).thenReturn(roles.contains(ApiClient.CHAT_SERVICE));
        when(client.hasConsoleRole()).thenReturn(roles.contains(ApiClient.CONSOLE_ADMIN) || roles.contains(ApiClient.CONSOLE_VIEWER));
        when(client.activeAt(any(Instant.class))).thenReturn(true);
        when(client.suspended()).thenReturn(false);
        when(client.decoy()).thenReturn(false);
        when(client.allowedNetworks()).thenReturn(List.of());
        return client;
    }

    private MockHttpServletResponse execute(String method, String path, String bearerKey) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (bearerKey != null) {
            request.addHeader("Authorization", "Bearer " + bearerKey);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void chatServicePassesChatTools() throws Exception {
        MockHttpServletResponse response = execute("POST", "/chat/tools/docs_search", chatKey);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void chatServiceForbiddenOnMcpAndConsole() throws Exception {
        MockHttpServletResponse mcpResponse = execute("POST", "/mcp", chatKey);
        assertThat(mcpResponse.getStatus()).isEqualTo(403);
        assertThat(mcpResponse.getContentAsString()).contains("insufficient_scope");

        MockHttpServletResponse adminResponse = execute("GET", "/admin/api/me", chatKey);
        assertThat(adminResponse.getStatus()).isEqualTo(403);
        assertThat(adminResponse.getContentAsString()).contains("insufficient_scope");

        MockHttpServletResponse internalResponse = execute("GET", "/internal/catalog", chatKey);
        assertThat(internalResponse.getStatus()).isEqualTo(403);
        assertThat(internalResponse.getContentAsString()).contains("insufficient_scope");
    }

    @Test
    void mcpKeyAndConsoleKeyForbiddenOnChatTools() throws Exception {
        MockHttpServletResponse mcpResponse = execute("POST", "/chat/tools/x", mcpKey);
        assertThat(mcpResponse.getStatus()).isEqualTo(403);
        assertThat(mcpResponse.getContentAsString()).contains("insufficient_scope");

        MockHttpServletResponse consoleResponse = execute("POST", "/chat/tools/x", consoleKey);
        assertThat(consoleResponse.getStatus()).isEqualTo(403);
        assertThat(consoleResponse.getContentAsString()).contains("insufficient_scope");
    }

    @Test
    void missingKeyIsUnauthorized() throws Exception {
        MockHttpServletResponse response = execute("POST", "/chat/tools/docs_search", null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid_token");
    }
}
