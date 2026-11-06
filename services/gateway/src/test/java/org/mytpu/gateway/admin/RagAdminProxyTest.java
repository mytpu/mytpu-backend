package org.mytpu.gateway.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.McpKeyFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class RagAdminProxyTest {
    private HttpServer rag;
    private AdminAuditLog audit;
    private RagAdminProxy proxy;
    private ApiClient admin;

    @BeforeEach
    void setUp() throws Exception {
        rag = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rag.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        rag.start();
        audit = mock(AdminAuditLog.class);
        proxy = new RagAdminProxy("http://127.0.0.1:" + rag.getAddress().getPort(), "rag-key", audit,
                mock(RagPageFetcher.class), JsonMapper.builder().build());
        admin = mock(ApiClient.class);
        when(admin.id()).thenReturn("cli_admin");
    }

    @AfterEach
    void tearDown() {
        rag.stop(0);
    }

    private MockHttpServletRequest request(String method, String path, String json) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/admin/api/rag" + path);
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        request.setAttribute(McpKeyFilter.CLIENT_ATTRIBUTE, admin);
        return request;
    }

    @Test
    void playgroundSearchIsNotAnAdminAction() throws Exception {
        var response = proxy.proxy(request("POST", "/playground", "{\"query\":\"бассейн\",\"access\":\"public\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void documentChangeIsRecorded() throws Exception {
        proxy.proxy(request("PUT", "/documents/42", "{\"status\":\"disabled\"}"));

        verify(audit).record(eq(admin), eq("rag.put"), eq("rag"), eq("/documents/42"), any(), any());
    }
}
