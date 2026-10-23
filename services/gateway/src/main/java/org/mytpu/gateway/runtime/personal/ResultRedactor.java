package org.mytpu.gateway.runtime.personal;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.personal.DisclosurePolicy.Decision;
import org.mytpu.gateway.runtime.personal.FieldClassifier.Field;
import org.mytpu.gateway.runtime.personal.FieldClassifier.Schema;
import org.mytpu.tool.api.DisclosureMode;
import org.mytpu.tool.api.Sensitivity;
import org.mytpu.tool.api.ToolAnswer;
import org.mytpu.tool.api.ToolDescriptor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Вырезание и маски ответа личного инструмента по решению {@link DisclosurePolicy} — только это, без решений и журнала.
 * Работает по JSON-дереву ответа и схеме {@link FieldClassifier}: публичные поля — как есть, личные — по режиму,
 * чувствительные — по режиму для чувствительного; списки обрезаются до {@code maxItems}. Объект или массив без схемы
 * и поле, которого нет в схеме, уходят как {@code null} (закрыто по умолчанию). Если хоть одно поле ушло не полностью,
 * фраза {@code summary} заменяется безопасной ({@code publicSummary}), подсказка {@code hint} отбрасывается.
 */
@Component
public class ResultRedactor {

    /** Итог вырезания: ответ и что ушло — пути полей (с маской) и число личных записей. */
    public record Redacted(ToolAnswer<?> answer, List<String> disclosedFields, int records) {
    }

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final JsonMapper json;
    private final Messages messages;
    private final PersonalProperties properties;

    public ResultRedactor(JsonMapper json, Messages messages, PersonalProperties properties) {
        this.json = json;
        this.messages = messages;
        this.properties = properties;
    }

    /** @param maxItems до скольких элементов обрезаются списки: предел инструмента или меньший резерв суточного лимита */
    public Redacted apply(ToolAnswer<?> answer, ToolDescriptor tool, Schema schema, Decision decision, int maxItems) {
        boolean full = decision.personal() == DisclosureMode.FULL && decision.sensitive() == DisclosureMode.FULL;
        if (decision.personal() == DisclosureMode.REFERENCE) {
            return new Redacted(new ToolAnswer<>(safeSummary(answer), null, referenceHint(), null), List.of(), 0);
        }
        Walk walk = new Walk(decision, Math.min(maxItems, tool.maxItems()));
        JsonNode data = walk.object(json.valueToTree(answer.data()), schema, "");
        String hint = full ? answer.hint() : null;
        if (!walk.truncated.isEmpty()) {
            String shown = String.join(" ", walk.truncated);
            hint = hint == null ? shown : hint + " " + shown;
        }
        return new Redacted(new ToolAnswer<>(full ? answer.summary() : safeSummary(answer), data, hint, null),
                List.copyOf(walk.disclosed), Math.max(1, walk.records));
    }

    private String safeSummary(ToolAnswer<?> answer) {
        return answer.publicSummary() != null ? answer.publicSummary() : messages.text("personal.public_summary_default");
    }

    private String referenceHint() {
        return properties.cardUrl().isBlank()
                ? messages.text("personal.reference_hint_no_url")
                : messages.text("personal.reference_hint", properties.cardUrl());
    }

    private final class Walk {
        private final Decision decision;
        private final int maxItems;
        private final List<String> disclosed = new ArrayList<>();
        private final List<String> truncated = new ArrayList<>();
        private int records;

        Walk(Decision decision, int maxItems) {
            this.decision = decision;
            this.maxItems = maxItems;
        }

        /** Запись по схеме; не объект или пустая схема — {@code null}. */
        JsonNode object(JsonNode node, Schema schema, String path) {
            if (!(node instanceof ObjectNode object) || schema == null || schema.fields().isEmpty()) {
                return NODES.nullNode();
            }
            ObjectNode out = NODES.objectNode();
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                Field field = schema.fields().get(entry.getKey());
                String fieldPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                out.set(entry.getKey(), field == null ? NODES.nullNode() : field(entry.getValue(), field, fieldPath));
            }
            return out;
        }

        private JsonNode field(JsonNode value, Field field, String path) {
            DisclosureMode mode = switch (field.sensitivity()) {
                case PUBLIC -> DisclosureMode.FULL;
                case PERSONAL -> decision.personal();
                case SENSITIVE -> decision.sensitive();
            };
            if (mode == DisclosureMode.MASKED) {
                return mask(value, field, path);
            }
            if (mode != DisclosureMode.FULL) {
                return NODES.nullNode();
            }
            if (field.sensitivity() != Sensitivity.PUBLIC) {
                disclosed.add(path);
            }
            return field.nested() == null ? leaves(value, field) : nested(value, field, path);
        }

        /** Простое значение или список простых значений; список — с обрезкой и учётом записей. */
        private JsonNode leaves(JsonNode value, Field field) {
            if (!(value instanceof ArrayNode array)) {
                return value;
            }
            ArrayNode out = NODES.arrayNode();
            for (int i = 0; i < Math.min(array.size(), maxItems); i++) {
                out.add(array.get(i));
            }
            if (array.size() > maxItems) {
                truncated.add(messages.text("personal.shown_of", maxItems, array.size()));
            }
            if (field.sensitivity() != Sensitivity.PUBLIC) {
                records = Math.max(records, out.size());
            }
            return out;
        }

        /** Вложенная запись или список записей; у списка — обрезка и учёт записей, если внутри ушло личное. */
        private JsonNode nested(JsonNode value, Field field, String path) {
            if (!(value instanceof ArrayNode array)) {
                return field.list() ? NODES.nullNode() : object(value, field.nested(), path);
            }
            int before = disclosed.size();
            ArrayNode out = NODES.arrayNode();
            for (int i = 0; i < Math.min(array.size(), maxItems); i++) {
                out.add(object(array.get(i), field.nested(), path));
            }
            if (array.size() > maxItems) {
                truncated.add(messages.text("personal.shown_of", maxItems, array.size()));
            }
            if (field.sensitivity() != Sensitivity.PUBLIC || disclosed.size() > before) {
                records = Math.max(records, out.size());
            }
            return out;
        }

        private JsonNode mask(JsonNode value, Field field, String path) {
            JsonNode masked = switch (field.mask()) {
                case HIDE -> NODES.nullNode();
                case INITIALS -> value != null && value.isString() ? NODES.stringNode(initials(value.asString())) : NODES.nullNode();
                case COUNT -> value instanceof ArrayNode array ? NODES.numberNode(array.size()) : NODES.nullNode();
                case COARSE -> value != null && value.isString() ? coarse(value.asString()) : NODES.nullNode();
            };
            if (!masked.isNull()) {
                disclosed.add(path + "(" + field.mask().name().toLowerCase() + ")");
            }
            return masked;
        }
    }

    private static String initials(String text) {
        return Arrays.stream(text.trim().split("[\\s.]+"))
                .filter(part -> !part.isEmpty())
                .map(part -> part.substring(0, 1).toUpperCase() + ".")
                .collect(Collectors.joining(" "));
    }

    /** Дата или момент времени → месяц; не дата — скрывается. */
    private static JsonNode coarse(String text) {
        if (text.length() >= 7) {
            try {
                return NODES.stringNode(YearMonth.parse(text.substring(0, 7)).toString());
            } catch (DateTimeParseException e) {
                return NODES.nullNode();
            }
        }
        return NODES.nullNode();
    }
}
