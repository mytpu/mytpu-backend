package org.mytpu.assistant.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mytpu.assistant.errors.ChatErrorHandler;
import org.mytpu.assistant.limits.ChatLimits;
import org.mytpu.assistant.tools.GatewayToolsClient;
import org.mytpu.assistant.tools.ToolAnswer;
import org.mytpu.assistant.tools.ToolUnavailable;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class ScheduleSearchTest {
    private static final String TOKEN = "A".repeat(43);
    private static final String COOKIE = "__Host-mytpu_session";
    private static final String QUERY = "8К31";
    private final JsonMapper json = new JsonMapper();
    private final GatewayToolsClient gateway = mock(GatewayToolsClient.class);
    private HttpServer identity;
    private MockMvc mvc;

    @BeforeEach
    void startIdentity() throws Exception {
        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/api/auth/session", exchange -> {
            try (exchange) {
                var headers = exchange.getRequestHeaders();
                if (("Bearer " + TOKEN).equals(headers.getFirst("Authorization"))
                        || (COOKIE + "=" + TOKEN).equals(headers.getFirst("Cookie"))) {
                    byte[] body = "{\"id\":\"demo\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    exchange.sendResponseHeaders(401, -1);
                }
            }
        });
        identity.start();
        mvc = setup("http://127.0.0.1:" + identity.getAddress().getPort());
    }

    @AfterEach
    void stopIdentity() {
        identity.stop(0);
    }

    @Test
    void acceptsBothSessionsAndReturnsStructuredResults() throws Exception {
        String url = "https://rasp.tpu.ru/redirect/kalendar.html?hash=demo";
        answer("""
                {"total":1,"items":[{"name":"8К31","kind":"Группа","hint":null,"url":"%s"}]}
                """.formatted(url));
        for (var request : new MockHttpServletRequestBuilder[]{
                query("{\"query\":\" \u00a08К31\u202f \"}").cookie(new Cookie(COOKIE, TOKEN)),
                authenticated()}) {
            mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value(QUERY))
                    .andExpect(jsonPath("$.items[0].kind").value("Группа")).andExpect(jsonPath("$.items[0].url").value(url));
        }
    }

    @Test
    void deniesMissingAndRevokedSessionBeforeCallingGateway() throws Exception {
        answer("{\"total\":0,\"items\":[]}");
        mvc.perform(query("{\"query\":\"8К31\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("session_required"));
        mvc.perform(query("{\"query\":\"8К31\"}").header("Authorization", "Bearer " + "B".repeat(43)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("session_required"));
        setup("").perform(authenticated()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("identity_unavailable"));
        verifyNoInteractions(gateway);
    }

    @Test
    void rejectsInvalidQueryBeforeCallingGateway() throws Exception {
        for (String body : new String[]{"", "{", "{}", "{\"query\":42}", "{\"query\":\"  \u00a0\u202f \"}",
                "{\"query\":\"" + "A".repeat(101) + "\"}"}) {
            mvc.perform(query(body).cookie(new Cookie(COOKIE, TOKEN))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_query"));
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void distinguishesEmptyResponseMalformedResponseAndGatewayErrors() throws Exception {
        answer("{\"total\":0,\"items\":[]}");
        mvc.perform(authenticated()).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        answer("{\"total\":1,\"items\":[]}");
        mvc.perform(authenticated()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("schedule_unavailable"));
        when(gateway.call("rasp_find", Map.of("query", QUERY)))
                .thenThrow(new ToolUnavailable("upstream_timeout"), new ToolUnavailable("tool_unavailable"));
        mvc.perform(authenticated()).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value("schedule_timeout"));
        mvc.perform(authenticated()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("schedule_unavailable"));
    }

    private MockMvc setup(String identityUrl) {
        ChatLimits limits = mock(ChatLimits.class);
        when(limits.acquire(any())).thenReturn(mock(ChatLimits.Permit.class));
        var errors = new ChatErrorHandler(new StaticMessageSource());
        var controller = new ScheduleSearchController(new ChatSession(identityUrl, json), limits, gateway, errors);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(errors).build();
    }

    private void answer(String body) {
        when(gateway.call("rasp_find", Map.of("query", QUERY)))
                .thenReturn(new ToolAnswer("", json.readTree(body), null, true, null));
    }

    private static MockHttpServletRequestBuilder authenticated() {
        return query("{\"query\":\"8К31\"}").header("Authorization", "Bearer " + TOKEN);
    }

    private static MockHttpServletRequestBuilder query(String body) {
        return post("/api/ai/schedule/search").contentType("application/json").content(body);
    }
}
