package org.mytpu.gateway.mcp;

import org.mytpu.tool.api.Choice;
import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Итог исполнителя → {@code CallToolResult} MCP. Текст начинается с готовой фразы {@code summary}: клиенты,
 * которые не читают {@code structuredContent}, всё равно получают ответ, пригодный для пересказа.
 */
@Component
public class McpResults {
    private final JsonMapper json;

    public McpResults(JsonMapper json) {
        this.json = json;
    }

    public ObjectNode toCallResult(ToolResult<?> result) {
        return switch (result) {
            case ToolResult.Ok<?> ok -> ok(ok.answer());
            case ToolResult.NeedsChoice<?> choice -> needsChoice(choice);
            case ToolResult.Denied<?> denied -> error(denied.code().code(), denied.message());
            case ToolResult.Failed<?> failed -> error(failed.code().code(), failed.message());
        };
    }

    private ObjectNode ok(ToolAnswer<?> answer) {
        ObjectNode structured = JsonNodeFactory.instance.objectNode();
        structured.put("summary", answer.summary());
        structured.set("data", json.valueToTree(answer.data()));
        if (answer.hint() != null) {
            structured.put("hint", answer.hint());
        }
        StringBuilder text = new StringBuilder(answer.summary());
        if (answer.hint() != null) {
            text.append("\n").append(answer.hint());
        }
        if (answer.data() != null) {
            text.append("\n\n").append(json.writeValueAsString(answer.data()));
        }
        return result(text.toString(), structured, false);
    }

    private ObjectNode needsChoice(ToolResult.NeedsChoice<?> choice) {
        ObjectNode structured = JsonNodeFactory.instance.objectNode();
        structured.put("needs_choice", true);
        structured.put("question", choice.question());
        ArrayNode options = structured.putArray("options");
        StringBuilder text = new StringBuilder(choice.question());
        int n = 1;
        for (Choice option : choice.options()) {
            options.addObject().put("label", option.label()).put("value", option.value());
            text.append("\n").append(n++).append(". ").append(option.label()).append(" — значение: ").append(option.value());
        }
        return result(text.toString(), structured, false);
    }

    private ObjectNode error(String code, String message) {
        ObjectNode structured = JsonNodeFactory.instance.objectNode().put("error", code).put("message", message);
        return result(message, structured, true);
    }

    private static ObjectNode result(String text, ObjectNode structured, boolean isError) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.set("structuredContent", structured);
        result.put("isError", isError);
        return result;
    }
}
