package org.mytpu.gateway.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.McpKeyFilter;
import org.mytpu.gateway.runtime.CallRequest;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.ToolExecutor;
import org.mytpu.tool.api.Channel;
import org.mytpu.tool.api.Choice;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolResult;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ChatToolsControllerTest {
    private ToolExecutor executor;
    private Messages messages;
    private JsonMapper json;
    private ChatToolsController controller;
    private ApiClient client;

    @BeforeEach
    void setUp() {
        executor = mock(ToolExecutor.class);
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.setUseCodeAsDefaultMessage(true);
        messages = new Messages(messageSource);
        json = JsonMapper.builder().build();
        controller = new ChatToolsController(executor, messages, json, 65536);

        client = mock(ApiClient.class);
        when(client.id()).thenReturn("cli_chat");
        when(client.roles()).thenReturn(Set.of(ApiClient.CHAT_SERVICE));
        when(client.scopes()).thenReturn(Set.of("docs.read"));
        when(client.deniedTools()).thenReturn(Set.of());
        when(client.rateLimits()).thenReturn(Map.of());
        when(client.readOnly()).thenReturn(false);
    }

    private MockHttpServletRequest request(String content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat/tools/docs_search");
        request.setAttribute(McpKeyFilter.CLIENT_ATTRIBUTE, client);
        if (content != null) {
            request.setContent(content.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    @Test
    void successfulExecutionReturnsSummaryDataHintAndUsesChatChannel() throws Exception {
        Map<String, Object> data = Map.of("found", true, "hits", List.of(Map.of("title", "Справка")));
        ToolAnswer<Map<String, Object>> answer = new ToolAnswer<>("Нашлось в документах", data, "Перескажи ответ");
        doReturn(ToolResult.ok(answer)).when(executor).execute(any());

        ResponseEntity<ObjectNode> response = controller.call("docs_search", request("{\"query\":\"справка\"}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ObjectNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("summary").asString()).isEqualTo("Нашлось в документах");
        assertThat(body.path("data").path("found").asBoolean()).isTrue();
        assertThat(body.path("hint").asString()).isEqualTo("Перескажи ответ");

        ArgumentCaptor<CallRequest> captor = ArgumentCaptor.forClass(CallRequest.class);
        verify(executor).execute(captor.capture());
        CallRequest executed = captor.getValue();
        assertThat(executed.toolName()).isEqualTo("docs_search");
        assertThat(executed.context().channel()).isEqualTo(Channel.CHAT);
        assertThat(executed.context().clientId()).isEqualTo("cli_chat");
        assertThat(executed.context().scopes()).containsExactly("docs.read");
        assertThat(executed.arguments().path("query").asString()).isEqualTo("справка");
    }

    @Test
    void needsChoiceReturnsStructuredChoice() throws Exception {
        ToolResult.NeedsChoice<?> choice = new ToolResult.NeedsChoice<>("Уточните группу",
                List.of(new Choice("8К31", "g1"), new Choice("8К32", "g2")));
        doReturn(choice).when(executor).execute(any());

        ResponseEntity<ObjectNode> response = controller.call("rasp_find", request("{\"query\":\"8К3\"}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ObjectNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("needs_choice").asBoolean()).isTrue();
        assertThat(body.path("question").asString()).isEqualTo("Уточните группу");
        assertThat(body.path("options").size()).isEqualTo(2);
    }

    @Test
    void nonObjectBodyReturnsBadRequest() throws Exception {
        ResponseEntity<ObjectNode> response = controller.call("docs_search", request("[1, 2, 3]"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("error").asString()).isEqualTo("invalid_arguments");
    }

    @Test
    void deniedReturnsMappedHttpStatusAndErrorJson() throws Exception {
        doReturn(ToolResult.denied(ErrorCode.TOOL_NOT_ALLOWED, "Инструмент не разрешён")).when(executor).execute(any());

        ResponseEntity<ObjectNode> response = controller.call("secret_tool", request("{}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ObjectNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("error").asString()).isEqualTo("tool_not_allowed");
        assertThat(body.path("message").asString()).isEqualTo("Инструмент не разрешён");
    }

    @Test
    void rateLimitedReturns429() throws Exception {
        doReturn(ToolResult.denied(ErrorCode.RATE_LIMITED, "Слишком много вызовов")).when(executor).execute(any());

        ResponseEntity<ObjectNode> response = controller.call("docs_search", request("{\"query\":\"тест\"}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().path("error").asString()).isEqualTo("rate_limited");
    }
}
