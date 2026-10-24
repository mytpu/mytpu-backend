package org.mytpu.gateway.mcp;

import java.util.List;

import org.mytpu.gateway.mcp.McpCallerResolver.Caller;
import org.mytpu.gateway.runtime.CallRequest;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.gateway.runtime.ToolExecutor;
import org.mytpu.tool.api.ToolDescriptor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Методы MCP без состояния: {@code initialize}, {@code ping}, {@code tools/list}, {@code tools/call}.
 * Список инструментов строится на каждый запрос по правам вызывающего — поэтому у каждого мастер-ключа он свой.
 */
@Component
public class McpProtocol {
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18");
    static final List<String> METHODS = List.of("initialize", "ping", "tools/list", "tools/call");

    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private final ToolExecutor executor;
    private final InputSchemas schemas;
    private final McpResults results;
    private final Messages messages;
    private final String version;

    public McpProtocol(ToolExecutor executor, InputSchemas schemas, McpResults results, Messages messages,
                       @org.springframework.beans.factory.annotation.Value("${mytpu.gateway.version:0.1.0}") String version) {
        this.executor = executor;
        this.schemas = schemas;
        this.results = results;
        this.messages = messages;
        this.version = version;
    }

    /** @return результат метода или ошибка JSON-RPC ({@link RpcError}) */
    public JsonNode handle(String method, JsonNode params, Caller caller) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> JsonNodeFactory.instance.objectNode();
            case "tools/list" -> listTools(caller);
            case "tools/call" -> callTool(params, caller);
            default -> throw new RpcError(METHOD_NOT_FOUND, messages.text("mcp.method_not_found", method));
        };
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params == null ? null : params.path("protocolVersion").asString(null);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(1));
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo").put("name", "mytpu-gateway").put("title", messages.text("mcp.server_title")).put("version", version);
        result.put("instructions", messages.text("mcp.instructions"));
        return result;
    }

    private ObjectNode listTools(Caller caller) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode tools = result.putArray("tools");
        for (RegisteredTool tool : executor.visible(new CallRequest(caller.context(), caller.policy(), null, null))) {
            ToolDescriptor descriptor = tool.descriptor();
            ObjectNode entry = tools.addObject();
            entry.put("name", descriptor.name());
            entry.put("description", description(descriptor));
            entry.set("inputSchema", schemas.schema(tool));
            entry.set("annotations", annotations(descriptor));
        }
        return result;
    }

    private ObjectNode callTool(JsonNode params, Caller caller) {
        String name = params == null ? null : params.path("name").asString(null);
        if (name == null || name.isBlank()) {
            throw new RpcError(INVALID_PARAMS, messages.text("mcp.tool_name_missing"));
        }
        JsonNode arguments = params.get("arguments");
        if (arguments != null && !arguments.isObject() && !arguments.isNull()) {
            throw new RpcError(INVALID_PARAMS, messages.text("mcp.arguments_not_object"));
        }
        return results.toCallResult(executor.execute(new CallRequest(caller.context(), caller.policy(), name, arguments)));
    }

    /** Подсказки клиенту MCP (он может спросить подтверждение); защита от них не зависит — права решает шлюз. */
    private ObjectNode annotations(ToolDescriptor descriptor) {
        boolean read = descriptor.kind() == org.mytpu.tool.api.Kind.READ;
        ObjectNode annotations = JsonNodeFactory.instance.objectNode();
        String title = messages.optional(descriptor.name() + ".title");
        if (title != null) {
            annotations.put("title", title);
        }
        annotations.put("readOnlyHint", read);
        annotations.put("destructiveHint", descriptor.kind() == org.mytpu.tool.api.Kind.WRITE);
        annotations.put("idempotentHint", read);
        annotations.put("openWorldHint", descriptor.openWorld());
        return annotations;
    }

    private String description(ToolDescriptor descriptor) {
        if (descriptor.description() != null) {
            return descriptor.description();
        }
        String text = messages.optional(descriptor.name() + ".description");
        return text == null ? descriptor.name() : text;
    }

    /** Ошибка уровня JSON-RPC (не инструмента): неизвестный метод, неверные параметры. */
    public static final class RpcError extends RuntimeException {
        private final int code;

        public RpcError(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }
}
