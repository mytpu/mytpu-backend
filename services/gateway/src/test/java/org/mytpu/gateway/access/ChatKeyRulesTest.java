package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mytpu.gateway.catalog.IntegrationCatalog;
import org.mytpu.gateway.runtime.ToolRegistry;

class ChatKeyRulesTest {
    private KeyRules rules;

    @BeforeEach
    void setUp() {
        IntegrationCatalog catalog = mock(IntegrationCatalog.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        KeyLifecycleProperties properties = new KeyLifecycleProperties(null, null, null, null, null, null, null, null, null, null, null);

        when(catalog.knownScopes()).thenReturn(Set.of("docs.read", "rasp.read"));
        when(registry.all()).thenReturn(List.of());

        rules = new KeyRules(catalog, registry, properties);
    }

    @Test
    void chatServiceRoleAllowedWithServerKindAndNetworks() {
        KeyRules.Request request = new KeyRules.Request(
                "Помощник чата",
                Set.of("docs.read", "rasp.read"),
                Set.of(ApiClient.CHAT_SERVICE),
                Set.of(),
                Map.of(),
                false,
                null,
                ApiClient.Kind.SERVER,
                List.of("10.0.0.0/8"),
                false
        );

        ApiClientStore.NewClient client = rules.newClient(request);
        assertThat(client.roles()).containsExactly(ApiClient.CHAT_SERVICE);
        assertThat(client.kind()).isEqualTo(ApiClient.Kind.SERVER);
        assertThat(client.allowedNetworks()).containsExactly("10.0.0.0/8");
        assertThat(client.scopes()).containsExactlyInAnyOrder("docs.read", "rasp.read");
    }

    @Test
    void chatServiceRoleRequiresServerKind() {
        KeyRules.Request request = new KeyRules.Request(
                "Помощник чата",
                Set.of("docs.read"),
                Set.of(ApiClient.CHAT_SERVICE),
                Set.of(),
                Map.of(),
                false,
                null,
                ApiClient.Kind.PERSON,
                List.of("10.0.0.0/8"),
                false
        );

        assertThatThrownBy(() -> rules.newClient(request))
                .isInstanceOf(KeyRules.Violation.class)
                .hasMessage("chat_service_needs_server");
    }

    @Test
    void chatServiceRoleRequiresNetworks() {
        KeyRules.Request request = new KeyRules.Request(
                "Помощник чата",
                Set.of("docs.read"),
                Set.of(ApiClient.CHAT_SERVICE),
                Set.of(),
                Map.of(),
                false,
                null,
                ApiClient.Kind.SERVER,
                List.of(),
                false
        );

        assertThatThrownBy(() -> rules.newClient(request))
                .isInstanceOf(KeyRules.Violation.class)
                .hasMessage("server_needs_networks");
    }
}
