package org.mytpu.gateway.runtime.personal;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.mytpu.tool.api.Classified;
import org.mytpu.tool.api.Mask;
import org.mytpu.tool.api.Sensitivity;
import org.mytpu.tool.api.ToolDescriptor;
import org.springframework.stereotype.Component;

/**
 * Классы полей ответа инструмента по пометкам {@link Classified} (specs/personal-data-for-agents). Разбирается один раз
 * на инструмент. У личного и чувствительного инструмента каждое поле — включая вложенные записи и элементы списков —
 * обязано иметь пометку, а тип поля — быть простым значением (строка, число, дата, перечисление…), записью или списком
 * их; {@code Map}, {@code Optional}, {@code Object}, списки списков и рекурсивные записи не принимаются — их нельзя
 * проверить по полям. Иначе {@link IllegalStateException}, и шлюз не стартует. Поле без пометки получает класс инструмента.
 */
@Component
public class FieldClassifier {

    /** Поле ответа: имя в JSON, класс, маска; {@code nested} — схема вложенной записи (или элемента списка). */
    public record Field(String name, Sensitivity sensitivity, Mask mask, Schema nested, boolean list) {
    }

    public record Schema(Map<String, Field> fields) {
        static final Schema EMPTY = new Schema(Map.of());
    }

    private final Map<String, Schema> byTool = new ConcurrentHashMap<>();

    public Schema schema(ToolDescriptor descriptor) {
        return byTool.computeIfAbsent(descriptor.name() + '|' + descriptor.outputType().getName(), key -> {
            String name = descriptor.name();
            boolean strict = descriptor.sensitivity() != Sensitivity.PUBLIC;
            if (strict && !descriptor.outputType().isRecord()) {
                throw new IllegalStateException("Личный инструмент " + name + ": ответ должен быть record с пометками полей");
            }
            return build(descriptor.outputType(), descriptor.sensitivity(), strict, name, "", new HashSet<>());
        });
    }

    private Schema build(Class<?> type, Sensitivity fallback, boolean strict, String tool, String path, Set<Class<?>> visiting) {
        if (!type.isRecord()) {
            return Schema.EMPTY;
        }
        if (!visiting.add(type)) {
            if (strict) {
                throw new IllegalStateException("Личный инструмент " + tool + ": рекурсивная запись " + type.getSimpleName() + " в " + path);
            }
            return Schema.EMPTY;
        }
        Map<String, Field> fields = new LinkedHashMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            String fieldPath = path.isEmpty() ? component.getName() : path + "." + component.getName();
            Classified mark = component.getAnnotation(Classified.class);
            if (mark == null && strict) {
                throw new IllegalStateException("Личный инструмент " + tool + ": у поля " + fieldPath
                        + " нет пометки @Classified (PUBLIC, PERSONAL или SENSITIVE)");
            }
            boolean list = Collection.class.isAssignableFrom(component.getType()) || component.getType().isArray();
            Class<?> element = list ? elementType(component) : component.getType();
            if (strict && (element == null || !(element.isRecord() || leaf(element)))) {
                throw new IllegalStateException("Личный инструмент " + tool + ": поле " + fieldPath + " имеет тип "
                        + component.getGenericType().getTypeName() + " — нужен простой тип, запись или список записей");
            }
            Schema nested = element != null && element.isRecord()
                    ? build(element, fallback, strict, tool, fieldPath, visiting) : null;
            fields.put(jsonName(component), new Field(jsonName(component),
                    mark == null ? fallback : mark.value(), mark == null ? Mask.HIDE : mark.mask(), nested, list));
        }
        visiting.remove(type);
        return new Schema(Map.copyOf(fields));
    }

    /** Простые значения: их можно показать, скрыть или замаскировать целиком. */
    private static boolean leaf(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || CharSequence.class.isAssignableFrom(type) || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class || type == java.util.UUID.class
                || java.time.temporal.Temporal.class.isAssignableFrom(type);
    }

    private static Class<?> elementType(RecordComponent component) {
        if (component.getType().isArray()) {
            return component.getType().getComponentType();
        }
        Type generic = component.getGenericType();
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return null;
    }

    private static String jsonName(RecordComponent component) {
        JsonProperty property = component.getAnnotation(JsonProperty.class);
        return property != null && !property.value().isEmpty() ? property.value() : component.getName();
    }
}
