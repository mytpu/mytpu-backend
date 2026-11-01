package org.mytpu.gateway.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpOriginFilterTest {
    private final McpOriginFilter filter = new McpOriginFilter(List.of("https://console.example"),
            FilterTestSupport.messages(), FilterTestSupport.json());

    private MockHttpServletResponse call(String path, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void foreignOriginOnMcpIsForbidden() throws Exception {
        MockHttpServletResponse response = call("/mcp", "https://evil.example");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("origin_not_allowed");
    }

    @Test
    void noOriginAndAllowedOriginPass() throws Exception {
        assertThat(call("/mcp", null).getStatus()).isEqualTo(200);
        assertThat(call("/mcp", "HTTPS://Console.Example").getStatus()).isEqualTo(200);
    }

    @Test
    void otherPathsAreNotChecked() throws Exception {
        assertThat(call("/actuator/health", "https://evil.example").getStatus()).isEqualTo(200);
        assertThat(call("/mcpx", "https://evil.example").getStatus()).isEqualTo(200);
    }

    @Test
    void emptyListAllowsNoOrigin() throws Exception {
        McpOriginFilter closed = new McpOriginFilter(List.of(""), FilterTestSupport.messages(), FilterTestSupport.json());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/key/rotate");
        request.addHeader("Origin", "https://console.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        closed.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }
}
