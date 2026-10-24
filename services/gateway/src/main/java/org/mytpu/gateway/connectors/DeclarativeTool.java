package org.mytpu.gateway.connectors;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.mytpu.gateway.connectors.ConnectorHttpClient.CallFailed;
import org.mytpu.gateway.connectors.ConnectorSpec.Param;
import org.mytpu.gateway.connectors.ConnectorSpec.ToolSpec;
import org.mytpu.gateway.runtime.DescribedInput;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.Tool;
import org.mytpu.tool.api.ToolDescriptor;
import org.mytpu.tool.api.ToolException;
import org.mytpu.tool.api.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Инструмент опубликованного коннектора: только чтение, публичные данные, ответ стороннего API — недоверенный текст
 * (после него личное в сессии уходит не полностью). Схема и проверка аргументов — из описания параметров.
 */
public class DeclarativeTool implements Tool<JsonNode, JsonNode>, DescribedInput {
    private static final int MAX_STRING = 500;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final String connectorId;
    private final ConnectorSpec spec;
    private final ToolSpec tool;
    private final ConnectorCaller caller;
    private final Messages messages;
    private final ToolDescriptor descriptor;
    private final ObjectNode schema;

    public DeclarativeTool(String connectorId, ConnectorSpec spec, ToolSpec tool, ConnectorCaller caller, Messages messages) {
        this.connectorId = connectorId;
        this.spec = spec;
        this.tool = tool;
        this.caller = caller;
        this.messages = messages;
        this.descriptor = ToolDescriptor.read(tool.name(), spec.scope().name())
                .description(tool.description())
                .input(JsonNode.class)
                .output(JsonNode.class)
                .cacheTtl(Duration.ofSeconds(tool.cacheTtlSeconds() == null ? 300 : tool.cacheTtlSeconds()))
                .timeout(Duration.ofMillis(tool.timeoutMs() == null ? ConnectorCaller.DEFAULT_TIMEOUT.toMillis() : tool.timeoutMs()))
                .ratePerMinute(tool.ratePerMinute() == null ? 60 : tool.ratePerMinute())
                .openWorld(true)
                .untrustedOutput(true)
                .maxItems(tool.result().maxItems() == null ? ResponseMapper.DEFAULT_MAX_ITEMS : tool.result().maxItems())
                .build();
        this.schema = buildSchema();
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ObjectNode inputSchema() {
        return schema.deepCopy();
    }

    @Override
    public Validated validate(JsonNode arguments) {
        if (!(arguments instanceof ObjectNode given)) {
            return Validated.invalid(List.of(messages.text("connector.arg.object")));
        }
        List<String> errors = new ArrayList<>();
        Set<String> known = new HashSet<>();
        ObjectNode out = NODES.objectNode();
        for (Param param : params()) {
            known.add(param.name());
            JsonNode value = given.get(param.name());
            if (value == null || value.isNull()) {
                if (param.defaultValue() != null) {
                    out.set(param.name(), node(param.defaultValue()));
                } else if (param.mandatory()) {
                    errors.add(messages.text("connector.arg.required", param.name()));
                }
                continue;
            }
            String problem = check(param, value);
            if (problem != null) {
                errors.add(problem);
            } else {
                out.set(param.name(), value);
            }
        }
        given.propertyNames().stream().filter(n -> !known.contains(n)).forEach(n -> errors.add(messages.text("connector.arg.unknown", n)));
        return errors.isEmpty() ? Validated.ok(out) : Validated.invalid(errors);
    }

    @Override
    public ToolResult<JsonNode> call(CallContext context, JsonNode input) {
        try {
            ConnectorCaller.Outcome outcome = caller.call(connectorId, spec, tool, (ObjectNode) input);
            return ToolResult.ok(outcome.summary(), outcome.mapped().data());
        } catch (CallFailed e) {
            ErrorCode code = switch (e.code()) {
                case "timeout" -> ErrorCode.UPSTREAM_TIMEOUT;
                case "unreachable", "blocked_address" -> ErrorCode.UPSTREAM_UNAVAILABLE;
                default -> ErrorCode.UPSTREAM_ERROR;
            };
            throw new ToolException(code, messages.text("connector.call." + e.code(), e.args()));
        }
    }

    private String check(Param param, JsonNode value) {
        boolean ok = switch (param.type()) {
            case STRING -> value.isString() && value.asString().length() <= MAX_STRING;
            case INTEGER -> value.isIntegralNumber();
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
        };
        if (!ok) {
            return messages.text("connector.arg.type", param.name(), param.type().json());
        }
        if (param.enumValues() != null && !param.enumValues().contains(value.asString())) {
            return messages.text("connector.arg.enum", param.name(), param.enumValues());
        }
        return null;
    }

    private ObjectNode buildSchema() {
        ObjectNode root = NODES.objectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = NODES.arrayNode();
        for (Param param : params()) {
            ObjectNode p = properties.putObject(param.name());
            p.put("type", param.type().json());
            p.put("description", param.description());
            if (param.type() == ConnectorSpec.ParamType.STRING) {
                p.put("maxLength", MAX_STRING);
            }
            if (param.enumValues() != null) {
                ArrayNode values = p.putArray("enum");
                param.enumValues().forEach(values::add);
            }
            if (param.mandatory() && param.defaultValue() == null) {
                required.add(param.name());
            }
        }
        root.put("additionalProperties", false);
        if (!required.isEmpty()) {
            root.set("required", required);
        }
        return root;
    }

    private static JsonNode node(Object value) {
        return switch (value) {
            case Integer i -> NODES.numberNode(i);
            case Long l -> NODES.numberNode(l);
            case Number n -> NODES.numberNode(n.doubleValue());
            case Boolean b -> NODES.booleanNode(b);
            default -> NODES.stringNode(String.valueOf(value));
        };
    }

    private List<Param> params() {
        return tool.params() == null ? List.of() : tool.params();
    }
}
