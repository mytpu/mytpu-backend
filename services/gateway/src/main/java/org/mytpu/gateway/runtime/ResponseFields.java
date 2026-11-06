package org.mytpu.gateway.runtime;

import java.util.Set;

import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Вырезает выключенные у ключа поля из {@code data} ответа — до выдачи клиенту и до журнала
 * (specs/console-and-connectors, шаг 7а). Фразы {@code summary} и {@code hint} собраны инструментом и могут содержать
 * значение вырезанного поля: {@code summary} заменяется на {@code publicSummary} (фраза без личного) или общую фразу,
 * {@code hint} — общей фразой. Вопрос с вариантами (подписи вариантов — тоже значения полей) при выключенных полях
 * не отдаётся: клиент получает «уточнение в приложении», как в маскированном режиме.
 */
@Component
public class ResponseFields {

    private final JsonMapper json;
    private final Messages messages;

    public ResponseFields(JsonMapper json, Messages messages) {
        this.json = json;
        this.messages = messages;
    }

    public ToolResult<?> strip(ToolResult<?> result, Set<String> denied) {
        if (denied.isEmpty()) {
            return result;
        }
        if (result instanceof ToolResult.NeedsChoice<?>) {
            return ToolResult.ok(new ToolAnswer<>(messages.text("personal.choice_in_app"), null, null));
        }
        if (!(result instanceof ToolResult.Ok<?> ok) || ok.answer().data() == null) {
            return result;
        }
        JsonNode data = json.valueToTree(ok.answer().data());
        if (!(data instanceof ObjectNode object) || denied.stream().noneMatch(object::has)) {
            return result;
        }
        ObjectNode trimmed = object.deepCopy();
        denied.forEach(trimmed::remove);
        String publicSummary = ok.answer().publicSummary();
        String summary = publicSummary != null ? publicSummary : messages.text("fields.summary_hidden");
        String hint = ok.answer().hint() == null ? null : messages.text("fields.hint_hidden");
        return ToolResult.ok(new ToolAnswer<>(summary, trimmed, hint, publicSummary));
    }
}
