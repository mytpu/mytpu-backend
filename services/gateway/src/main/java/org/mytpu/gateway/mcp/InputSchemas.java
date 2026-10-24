package org.mytpu.gateway.mcp;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.mytpu.gateway.runtime.DescribedInput;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.ToolDescriptor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON Schema аргументов из record инструмента. Обязательные поля — с {@code @NotNull/@NotBlank/@NotEmpty};
 * описания полей — из {@code tools/descriptions.properties} ({@code <инструмент>.arg.<поле>}).
 */
@Component
public class InputSchemas {
    private final Messages messages;
    private final Map<String, ObjectNode> cache = new ConcurrentHashMap<>();

    public InputSchemas(Messages messages) {
        this.messages = messages;
    }

    /** Схема инструмента: своя у {@link DescribedInput} (коннектор), иначе — из record аргументов. */
    public ObjectNode schema(RegisteredTool tool) {
        return tool.tool() instanceof DescribedInput described ? described.inputSchema() : schema(tool.descriptor());
    }

    public ObjectNode schema(ToolDescriptor descriptor) {
        return cache.computeIfAbsent(descriptor.name() + '|' + descriptor.inputType().getName(), k -> build(descriptor));
    }

    private ObjectNode build(ToolDescriptor descriptor) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = JsonNodeFactory.instance.arrayNode();
        RecordComponent[] components = descriptor.inputType().getRecordComponents();
        for (RecordComponent component : components == null ? new RecordComponent[0] : components) {
            ObjectNode property = properties.putObject(component.getName());
            describeType(property, component.getGenericType());
            String text = messages.optional(descriptor.name() + ".arg." + component.getName());
            if (text != null) {
                property.put("description", text);
            }
            // Bean Validation не размечает сам компонент record — аннотации оказываются на методе-аксессоре
            var accessor = component.getAccessor();
            Size size = accessor.getAnnotation(Size.class);
            if (size != null && component.getType() == String.class && size.max() < Integer.MAX_VALUE) {
                property.put("maxLength", size.max());
            }
            if (accessor.isAnnotationPresent(NotNull.class) || accessor.isAnnotationPresent(NotBlank.class)
                    || accessor.isAnnotationPresent(NotEmpty.class)) {
                required.add(component.getName());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    private static void describeType(ObjectNode property, Type type) {
        Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
        if (raw == String.class) {
            property.put("type", "string");
        } else if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            property.put("type", "integer");
        } else if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class) {
            property.put("type", "number");
        } else if (raw == boolean.class || raw == Boolean.class) {
            property.put("type", "boolean");
        } else if (raw == LocalDate.class) {
            property.put("type", "string").put("format", "date");
        } else if (raw.isEnum()) {
            ArrayNode values = property.put("type", "string").putArray("enum");
            Arrays.stream(raw.getEnumConstants()).map(Object::toString).forEach(values::add);
        } else if (Collection.class.isAssignableFrom(raw) && type instanceof ParameterizedType p) {
            describeType(property.put("type", "array").putObject("items"), p.getActualTypeArguments()[0]);
        } else {
            property.put("type", "object");
        }
    }
}
