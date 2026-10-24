package org.mytpu.gateway.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mytpu.gateway.connectors.ConnectorSpec.Field;
import org.mytpu.gateway.connectors.ConnectorSpec.Result;
import org.mytpu.gateway.connectors.ConnectorSpec.ResultMode;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ответ стороннего API → данные инструмента по описанию: JSON Pointer на объект или список, из каждого — только
 * перечисленные поля, не больше {@code maxItems} элементов, длинные строки обрезаются. Остальное в ответ не попадает.
 */
public final class ResponseMapper {
    public static final int DEFAULT_MAX_ITEMS = 20;
    public static final int MAX_ITEMS = 50;
    private static final int MAX_TEXT = 2000;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Данные инструмента и значения для шаблона фразы (поля объекта или первого элемента, {@code count}, {@code total}). */
    public record Mapped(JsonNode data, Map<String, String> values) {
    }

    private ResponseMapper() {
    }

    public static Mapped map(JsonNode root, Result result) {
        JsonNode node = root.at(JsonPointer.compile(result.pointer() == null ? "" : result.pointer()));
        ResultMode mode = result.mode() == null ? ResultMode.OBJECT : result.mode();
        if (mode == ResultMode.OBJECT) {
            ObjectNode out = fields(node, result.fields());
            return new Mapped(out, values(out, 1, 1));
        }
        List<JsonNode> items = new ArrayList<>();
        if (mode == ResultMode.LIST && node instanceof ArrayNode array) {
            array.forEach(items::add);
        } else if (mode == ResultMode.VALUES && node instanceof ObjectNode object) {
            object.properties().forEach(e -> items.add(e.getValue()));
        }
        int max = result.maxItems() == null ? DEFAULT_MAX_ITEMS : Math.min(result.maxItems(), MAX_ITEMS);
        ArrayNode list = NODES.arrayNode();
        items.stream().limit(max).forEach(item -> list.add(fields(item, result.fields())));
        ObjectNode out = NODES.objectNode();
        out.set("items", list);
        out.put("total", items.size());
        Map<String, String> values = list.isEmpty() ? new LinkedHashMap<>() : values((ObjectNode) list.get(0), list.size(), items.size());
        values.put("count", String.valueOf(list.size()));
        values.put("total", String.valueOf(items.size()));
        return new Mapped(out, values);
    }

    private static ObjectNode fields(JsonNode source, List<Field> fields) {
        ObjectNode out = NODES.objectNode();
        for (Field field : fields) {
            JsonNode value = source.at(JsonPointer.compile(field.pointer()));
            out.set(field.name(), value.isMissingNode() ? NODES.nullNode() : clip(value));
        }
        return out;
    }

    private static JsonNode clip(JsonNode value) {
        if (value.isString() && value.asString().length() > MAX_TEXT) {
            return NODES.stringNode(value.asString().substring(0, MAX_TEXT) + "…");
        }
        return value;
    }

    private static Map<String, String> values(ObjectNode object, int count, int total) {
        Map<String, String> values = new LinkedHashMap<>();
        object.properties().forEach(e -> values.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asString() : e.getValue().toString()));
        values.put("count", String.valueOf(count));
        values.put("total", String.valueOf(total));
        return values;
    }
}
