package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class KeyLookupBusyTest {
    private final ApiClientStore store = mock(ApiClientStore.class);
    private final ClientAddress addresses = mock(ClientAddress.class);
    private final AuthFailureLimiter failures = new AuthFailureLimiter(30, 300);
    private final McpKeyFilter filter = new McpKeyFilter(store, failures, addresses, mock(KeyEvents.class),
            mock(KeyNetworkWatch.class), mock(GeoPolicy.class), FilterTestSupport.messages(), FilterTestSupport.json(),
            mock(DecoyGroups.class), "");

    private MockHttpServletResponse call(String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + key);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void busyLookupIs503AndDoesNotCountAsFailure() throws Exception {
        when(addresses.of(any())).thenReturn(InetAddress.getByName("203.0.113.7"));
        when(store.lookup(anyString())).thenReturn(KeyLookup.BUSY);
        String key = ApiKeys.generate();
        for (int i = 0; i < 40; i++) {
            MockHttpServletResponse response = call(key);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            assertThat(response.getContentAsString()).contains("key_lookup_busy");
        }
        assertThat(failures.blocked("203.0.113.7", "203.0.113.0/24")).isFalse();
    }

    @Test
    void cachedKeyPassesSubnetBlock() throws Exception {
        // Сосед по NAT выбрал лимит подсети; настоящий ключ из кэша всё равно проходит проверку блокировки
        when(addresses.of(any())).thenReturn(InetAddress.getByName("198.51.100.20"));
        for (int i = 0; i < 300; i++) {
            failures.recordFailure("198.51.100." + (i % 200), "198.51.100.0/24");
        }
        String key = ApiKeys.generate();
        when(store.cached(key)).thenReturn(true);
        when(store.lookup(key)).thenReturn(KeyLookup.NOT_FOUND);
        assertThat(call(key).getStatus()).isEqualTo(401);
        assertThat(call(ApiKeys.generate()).getStatus()).isEqualTo(429);
    }

    @Test
    void unknownKeyIsStill401() throws Exception {
        when(addresses.of(any())).thenReturn(InetAddress.getByName("203.0.113.9"));
        when(store.lookup(anyString())).thenReturn(KeyLookup.NOT_FOUND);
        MockHttpServletResponse response = call(ApiKeys.generate());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid_token");
    }
}
