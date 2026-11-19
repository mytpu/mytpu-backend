package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Проверка мастер-ключа (docs/SECURITY.md, раздел 14): ловушка, приостановка, сети ключа, «только из России», ключи v1.
 */
class McpKeyFilterTest {
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String CLIENT_SUBNET = "203.0.113.0/24";

    private ApiClientStore store;
    private AuthFailureLimiter failures;
    private ClientAddress addresses;
    private KeyEvents events;
    private GeoPolicy geoPolicy;
    private DecoyGroups decoys;

    @BeforeEach
    void setUp() {
        store = mock(ApiClientStore.class);
        failures = mock(AuthFailureLimiter.class);
        addresses = mock(ClientAddress.class);
        events = mock(KeyEvents.class);
        geoPolicy = mock(GeoPolicy.class);
        decoys = mock(DecoyGroups.class);
        InetAddress client = InetAddress.ofLiteral(CLIENT_IP);
        when(addresses.of(any())).thenReturn(client);
        when(addresses.known(any())).thenReturn(Optional.of(client));
        when(store.lookup(anyString())).thenReturn(KeyLookup.NOT_FOUND);
        when(geoPolicy.check(any(ApiClient.class), org.mockito.ArgumentMatchers.<Optional<InetAddress>>any()))
                .thenReturn(GeoPolicy.Decision.ALLOW);
    }

    private McpKeyFilter filter(String v1KeysUntil) {
        return new McpKeyFilter(store, failures, addresses, events, mock(KeyNetworkWatch.class), geoPolicy,
                FilterTestSupport.messages(), FilterTestSupport.json(), decoys, v1KeysUntil);
    }

    private ApiClient register(String key, String id) {
        ApiClient client = mock(ApiClient.class);
        when(client.id()).thenReturn(id);
        when(client.roles()).thenReturn(Set.of());
        when(client.activeAt(any(Instant.class))).thenReturn(true);
        when(client.allowedNetworks()).thenReturn(List.of());
        when(store.cached(key)).thenReturn(true);
        when(store.lookup(key)).thenReturn(KeyLookup.found(client));
        return client;
    }

    private record Result(MockHttpServletResponse response, MockFilterChain chain) {
        boolean passed() {
            return chain.getRequest() != null;
        }
    }

    private Result execute(McpKeyFilter filter, String method, String path, String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (key != null) {
            request.addHeader("Authorization", "Bearer " + key);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain);
    }

    private Result execute(String key) throws Exception {
        return execute(filter(""), "POST", "/mcp", key);
    }

    @Test
    void decoyAnswersByteForByteAsInvalidKey() throws Exception {
        String decoyKey = ApiKeys.generate();
        ApiClient decoy = register(decoyKey, "cli_decoy");
        when(decoy.decoy()).thenReturn(true);

        Result unknown = execute(ApiKeys.generate());
        Result trapped = execute(decoyKey);

        assertThat(unknown.response().getStatus()).isEqualTo(401);
        assertThat(unknown.passed()).isFalse();
        assertThat(trapped.response().getStatus()).isEqualTo(unknown.response().getStatus());
        assertThat(trapped.response().getHeaderNames()).containsExactlyInAnyOrderElementsOf(unknown.response().getHeaderNames());
        for (String header : unknown.response().getHeaderNames()) {
            assertThat(trapped.response().getHeaders(header)).as(header).isEqualTo(unknown.response().getHeaders(header));
        }
        assertThat(trapped.response().getHeader("WWW-Authenticate"))
                .isEqualTo(unknown.response().getHeader("WWW-Authenticate"))
                .isEqualTo("Bearer realm=\"mytpu\", error=\"invalid_token\"");
        assertThat(trapped.response().getContentType()).isEqualTo(unknown.response().getContentType());
        assertThat(trapped.response().getContentAsByteArray()).isEqualTo(unknown.response().getContentAsByteArray());
        assertThat(trapped.passed()).isFalse();
        verify(events).record("cli_decoy", KeyEvents.DECOY_USED, CLIENT_SUBNET, null);
        verify(decoys).tripLater("cli_decoy", CLIENT_SUBNET);
    }

    @Test
    void revokedDecoyStillTrips() throws Exception {
        // SECURITY.md, раздел 14: «Отозванная ловушка тоже срабатывает»
        String decoyKey = ApiKeys.generate();
        ApiClient decoy = register(decoyKey, "cli_decoy_old");
        when(decoy.decoy()).thenReturn(true);
        when(decoy.activeAt(any(Instant.class))).thenReturn(false);

        Result trapped = execute(decoyKey);

        assertThat(trapped.response().getStatus()).isEqualTo(401);
        verify(events).record("cli_decoy_old", KeyEvents.DECOY_USED, CLIENT_SUBNET, null);
        verify(decoys).tripLater("cli_decoy_old", CLIENT_SUBNET);
    }

    @Test
    void expiredKeyCannotReachHandler() throws Exception {
        String key = ApiKeys.generate();
        ApiClient client = new ApiClient("cli_expired", "test", "fixture", Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), true, true, Instant.EPOCH, ApiClient.Kind.PERSON, List.of(), null,
                false, ApiClient.GeoPolicy.RU_ONLY, null);
        when(store.lookup(key)).thenReturn(KeyLookup.found(client));

        Result result = execute(key);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentAsString()).contains("\"error\":\"invalid_token\"");
        assertThat(result.passed()).isFalse();
        verify(store, never()).touch("cli_expired");
    }

    @Test
    void suspendedKeyGets401KeySuspended() throws Exception {
        String key = ApiKeys.generate();
        ApiClient client = register(key, "cli_s");
        when(client.suspended()).thenReturn(true);

        Result result = execute(key);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getHeader("WWW-Authenticate")).contains("error=\"key_suspended\"");
        assertThat(result.response().getContentAsString()).contains("\"error\":\"key_suspended\"");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void callOutsideKeyNetworksGets403AndCountsFailure() throws Exception {
        String key = ApiKeys.generate();
        ApiClient client = register(key, "cli_net");
        when(client.allowedNetworks()).thenReturn(List.of("10.0.0.0/8"));

        Result result = execute(key);

        assertThat(result.response().getStatus()).isEqualTo(403);
        assertThat(result.response().getContentAsString()).contains("\"error\":\"network_not_allowed\"");
        assertThat(result.passed()).isFalse();
        verify(failures).recordFailure(CLIENT_IP, CLIENT_SUBNET);
        verify(events).record("cli_net", KeyEvents.NETWORK_DENIED, CLIENT_SUBNET, null);
    }

    @Test
    void callInsideKeyNetworksPasses() throws Exception {
        String key = ApiKeys.generate();
        ApiClient client = register(key, "cli_net_ok");
        when(client.allowedNetworks()).thenReturn(List.of("203.0.113.0/24"));

        Result result = execute(key);

        assertThat(result.passed()).isTrue();
        verify(failures, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void foreignCountrySuspendsKey() throws Exception {
        String key = ApiKeys.generate();
        register(key, "cli_geo");
        when(geoPolicy.check(any(ApiClient.class), org.mockito.ArgumentMatchers.<Optional<InetAddress>>any()))
                .thenReturn(new GeoPolicy.Decision.Foreign("US"));

        Result result = execute(key);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentAsString()).contains("\"error\":\"key_suspended\"");
        assertThat(result.passed()).isFalse();
        verify(store).suspend("cli_geo", "foreign:US");
        verify(events).record("cli_geo", KeyEvents.FOREIGN_COUNTRY, CLIENT_SUBNET, "country=US");
    }

    @Test
    void unknownCountryPasses() throws Exception {
        String key = ApiKeys.generate();
        ApiClient client = register(key, "cli_geo_unknown");
        when(client.geo()).thenReturn(ApiClient.GeoPolicy.RU_ONLY);
        CountryLookup lookup = mock(CountryLookup.class);
        when(lookup.lookup(InetAddress.ofLiteral(CLIENT_IP))).thenReturn(CountryLookup.Result.UNKNOWN);
        KeyLifecycleProperties properties = mock(KeyLifecycleProperties.class);
        when(properties.geo()).thenReturn(new KeyLifecycleProperties.Geo(true, null, null, null, null));
        geoPolicy = new GeoPolicy(properties, lookup, events, addresses);

        assertThat(execute(key).passed()).isTrue();
        assertThat(execute(key).passed()).isTrue();
        verify(events).record("cli_geo_unknown", KeyEvents.GEO_UNKNOWN, CLIENT_SUBNET, null);
        verify(store, never()).suspend(anyString(), anyString());
    }

    @Test
    void lookupFailureCannotReachHandler() {
        String key = ApiKeys.generate();
        var failure = new DataAccessResourceFailureException("fixture");
        when(store.lookup(key)).thenThrow(failure);
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + key);
        var chain = new MockFilterChain();

        assertThatThrownBy(() -> filter("").doFilter(request, new MockHttpServletResponse(), chain)).isSameAs(failure);
        assertThat(chain.getRequest()).isNull();
        assertThat(request.getAttribute(McpKeyFilter.CLIENT_ATTRIBUTE)).isNull();
    }

    @Test
    void busyLookupCannotReachHandler() throws Exception {
        String key = ApiKeys.generate();
        when(store.lookup(key)).thenReturn(KeyLookup.BUSY);

        Result result = execute(key);

        assertThat(result.response().getStatus()).isEqualTo(503);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void healthIsNotFiltered() throws Exception {
        Result health = execute(filter(""), "GET", "/actuator/health", null);
        Result liveness = execute(filter(""), "GET", "/actuator/health/liveness", null);

        assertThat(health.passed()).isTrue();
        assertThat(liveness.passed()).isTrue();
        verify(store, never()).lookup(anyString());
    }

    @Test
    void otherActuatorPathsNeedKey() throws Exception {
        Result result = execute(filter(""), "GET", "/actuator/prometheus", null);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void v1KeyAfterDeadlineGets401WithoutDatabase() throws Exception {
        String v1 = "mtpu_" + "Ab-_".repeat(10) + "xyz";
        assertThat(ApiKeys.isV1(v1)).isTrue();
        register(v1, "cli_v1");

        Result result = execute(filter("2000-01-01"), "POST", "/mcp", v1);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getContentAsString()).contains("\"error\":\"invalid_token\"");
        assertThat(result.passed()).isFalse();
        verify(store, never()).lookup(anyString());
        verify(store, never()).cached(anyString());
    }

    @Test
    void v1KeyBeforeDeadlineOrWithoutDeadlinePasses() throws Exception {
        String v1 = "mtpu_" + "Ab-_".repeat(10) + "xyz";
        register(v1, "cli_v1");

        assertThat(execute(filter("2999-12-31"), "POST", "/mcp", v1).passed()).isTrue();
        assertThat(execute(filter(""), "POST", "/mcp", v1).passed()).isTrue();
    }

    @Test
    void malformedKeyGets401WithoutDatabase() throws Exception {
        String good = ApiKeys.generate();
        String typo = good.substring(0, good.length() - 1) + (good.endsWith("A") ? "B" : "A");

        Result result = execute(typo);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.passed()).isFalse();
        verify(store, never()).lookup(anyString());
        verify(failures).recordFailure(eq(CLIENT_IP), eq(CLIENT_SUBNET));
    }

    @Test
    void validKeyPassesAndExposesClient() throws Exception {
        String key = ApiKeys.generate();
        ApiClient client = register(key, "cli_ok");

        Result result = execute(key);

        assertThat(result.passed()).isTrue();
        assertThat(result.chain().getRequest().getAttribute(McpKeyFilter.CLIENT_ATTRIBUTE)).isSameAs(client);
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {ApiClient.CONSOLE_ADMIN, ApiClient.CONSOLE_VIEWER})
    void consoleKeyWithMcpScopeCannotReachMcpHandler(String role) throws Exception {
        String key = ApiKeys.generate();
        ApiClient client = new ApiClient("cli_console", "test", "fixture", Set.of("docs.read"), Set.of(role), Set.of(),
                Map.of(), Map.of(), true, true, null, ApiClient.Kind.PERSON, List.of(), null,
                false, ApiClient.GeoPolicy.ANY, null);
        when(store.lookup(key)).thenReturn(KeyLookup.found(client));

        for (String path : List.of("/mcp", "/mcp/key/rotate")) {
            Result result = execute(filter(""), "POST", path, key);
            assertThat(result.response().getStatus()).as(path).isEqualTo(403);
            assertThat(result.response().getContentAsString()).contains("\"error\":\"insufficient_scope\"");
            assertThat(result.passed()).as(path).isFalse();
        }
        verify(store, never()).touch(client.id());
    }
}
