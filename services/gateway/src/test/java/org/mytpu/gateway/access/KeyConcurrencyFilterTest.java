package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class KeyConcurrencyFilterTest {
    private final KeyConcurrencyFilter filter = new KeyConcurrencyFilter(2, FilterTestSupport.messages(), FilterTestSupport.json());
    private final ApiClient client = FilterTestSupport.client("cli_test");

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setAsyncSupported(true);
        request.setAttribute(McpKeyFilter.CLIENT_ATTRIBUTE, client);
        return request;
    }

    /** Цепочка, которая начинает потоковый ответ и не завершает его — как долгий SSE. */
    private static final FilterChain STREAMING = (req, res) -> req.startAsync();

    @Test
    void synchronousRequestsReleaseThePermit() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("/mcp"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void streamingRequestsHoldPermitsUntilComplete() throws Exception {
        MockHttpServletRequest first = request("/mcp");
        filter.doFilter(first, new MockHttpServletResponse(), STREAMING);
        filter.doFilter(request("/mcp"), new MockHttpServletResponse(), STREAMING);

        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(request("/mcp"), third, new MockFilterChain());
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isEqualTo("1");
        assertThat(third.getContentAsString()).contains("too_many_concurrent");

        ((MockAsyncContext) first.getAsyncContext()).complete();
        MockHttpServletResponse fourth = new MockHttpServletResponse();
        filter.doFilter(request("/mcp"), fourth, new MockFilterChain());
        assertThat(fourth.getStatus()).isEqualTo(200);
    }

    @Test
    void errorAndCompleteReleaseOnlyOnce() throws Exception {
        MockHttpServletRequest first = request("/mcp");
        filter.doFilter(first, new MockHttpServletResponse(), STREAMING);
        filter.doFilter(request("/mcp"), new MockHttpServletResponse(), STREAMING);
        assertThat(filter.inFlight("cli_test")).isEqualTo(2);

        KeyConcurrencyFilter.Release release = new KeyConcurrencyFilter.Release(() -> { });
        int[] calls = {0};
        KeyConcurrencyFilter.Release counted = new KeyConcurrencyFilter.Release(() -> calls[0]++);
        counted.onError(null);
        counted.onComplete(null);
        counted.onTimeout(null);
        assertThat(calls[0]).isEqualTo(1);
        release.run();

        ((MockAsyncContext) first.getAsyncContext()).complete();
        assertThat(filter.inFlight("cli_test")).isEqualTo(1);
    }

    @Test
    void lastReleaseRemovesTheCounter() throws Exception {
        MockHttpServletRequest only = request("/mcp");
        filter.doFilter(only, new MockHttpServletResponse(), STREAMING);
        ((MockAsyncContext) only.getAsyncContext()).complete();
        assertThat(filter.inFlight("cli_test")).isZero();
    }

    @Test
    void consoleAndUnauthenticatedRequestsAreNotLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(request("/admin/api/me"), new MockHttpServletResponse(), STREAMING);
        }
        MockHttpServletRequest anonymous = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(anonymous, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
