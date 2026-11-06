package org.mytpu.gateway.chat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.McpKeyFilter;
import org.mytpu.gateway.runtime.CallRequest;
import org.mytpu.gateway.runtime.CallerPolicy;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.ToolExecutor;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.Channel;
import org.mytpu.tool.api.Choice;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Вход для чат-помощника: {@code POST /chat/tools/{name}} (specs/assistant/spec.md, шаг 4).
 * Запрос с сервисным ключом помощника (роль {@code chat.service}), канал {@link Channel#CHAT}.
 * Вызов идёт через единый {@link ToolExecutor} со всеми проверками, лимитами, кэшем и журналом.
 */
@RestController
public class ChatToolsController {
    private static final Logger log = LoggerFactory.getLogger(ChatToolsController.class);

    private final ToolExecutor executor;
    private final Messages messages;
    private final JsonMapper json;
    private final int maxBodyBytes;

    public ChatToolsController(ToolExecutor executor, Messages messages, JsonMapper json,
                               @Value("${mytpu.gateway.mcp.max-body-bytes:65536}") int maxBodyBytes) {
        this.executor = executor;
        this.messages = messages;
        this.json = json;
        this.maxBodyBytes = maxBodyBytes;
    }

    @PostMapping(value = "/chat/tools/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> call(@PathVariable("name") String name, HttpServletRequest request) throws IOException {
        ApiClient caller = McpKeyFilter.caller(request);

        Optional<String> body = readLimited(request);
        if (body.isEmpty()) {
            return error(ErrorCode.INVALID_ARGUMENTS.code(), messages.text("mcp.body_too_large", maxBodyBytes),
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }

        JsonNode arguments = null;
        String raw = body.get().trim();
        if (!raw.isEmpty()) {
            try {
                arguments = json.readTree(raw);
            } catch (JacksonException e) {
                return error(ErrorCode.INVALID_ARGUMENTS.code(), messages.text("error.invalid_arguments.json"),
                        HttpStatus.BAD_REQUEST);
            }
            if (!arguments.isObject()) {
                return error(ErrorCode.INVALID_ARGUMENTS.code(), messages.text("error.invalid_arguments.json"),
                        HttpStatus.BAD_REQUEST);
            }
        }

        CallContext context = new CallContext(
                null,
                caller.roles(),
                caller.id(),
                Channel.CHAT,
                caller.scopes(),
                UUID.randomUUID().toString()
        );
        CallerPolicy policy = new CallerPolicy(caller.deniedTools(), caller.deniedFields(), caller.rateLimits(), caller.readOnly());
        CallRequest callRequest = new CallRequest(context, policy, name, arguments);

        ToolResult<?> result;
        try {
            result = executor.execute(callRequest);
        } catch (RuntimeException e) {
            log.error("Сбой вызова инструмента {} через /chat/tools: {}", name, e.getClass().getName());
            return error(ErrorCode.INTERNAL_ERROR.code(), messages.text("error.internal_error"),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return toResponse(result);
    }

    private ResponseEntity<ObjectNode> toResponse(ToolResult<?> result) {
        return switch (result) {
            case ToolResult.Ok<?> ok -> ok(ok.answer());
            case ToolResult.NeedsChoice<?> choice -> needsChoice(choice);
            case ToolResult.Denied<?> denied -> error(denied.code().code(), denied.message(), statusFor(denied.code()));
            case ToolResult.Failed<?> failed -> error(failed.code().code(), failed.message(), statusFor(failed.code()));
        };
    }

    private ResponseEntity<ObjectNode> ok(ToolAnswer<?> answer) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("summary", answer.summary());
        node.set("data", json.valueToTree(answer.data()));
        if (answer.hint() != null) {
            node.put("hint", answer.hint());
        }
        return ResponseEntity.ok(node);
    }

    private ResponseEntity<ObjectNode> needsChoice(ToolResult.NeedsChoice<?> choice) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("needs_choice", true);
        node.put("question", choice.question());
        ArrayNode options = node.putArray("options");
        for (Choice option : choice.options()) {
            options.addObject().put("label", option.label()).put("value", option.value());
        }
        return ResponseEntity.ok(node);
    }

    private ResponseEntity<ObjectNode> error(String code, String message, HttpStatus status) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("error", code);
        node.put("message", message);
        return ResponseEntity.status(status).body(node);
    }

    private Optional<String> readLimited(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            return Optional.empty();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(maxBodyBytes + 1);
            return bytes.length > maxBodyBytes ? Optional.empty() : Optional.of(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case TOOL_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_ARGUMENTS -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED, PERSONAL_QUOTA -> HttpStatus.TOO_MANY_REQUESTS;
            case TOOL_NOT_ALLOWED, NEEDS_CONSENT, TOOL_DISABLED -> HttpStatus.FORBIDDEN;
            case GATEWAY_MODE -> HttpStatus.SERVICE_UNAVAILABLE;
            case UPSTREAM_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case UPSTREAM_UNAVAILABLE, UPSTREAM_ERROR -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
