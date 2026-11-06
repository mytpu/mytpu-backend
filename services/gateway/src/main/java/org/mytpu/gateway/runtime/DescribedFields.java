package org.mytpu.gateway.runtime;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mytpu.tool.api.Classified;
import org.mytpu.tool.api.Sensitivity;
import org.mytpu.tool.api.ToolDescriptor;
import org.springframework.stereotype.Component;

/**
 * Поля ответа инструмента, которые можно выключить у ключа (specs/console-and-connectors, шаг 7а): поля верхнего
 * уровня выходной записи с {@link Classified} не {@code PUBLIC}. Берутся из описания инструмента, поэтому контракт
 * {@code shared/tool-api} не меняется, а новый личный инструмент получает выбор полей без правок шлюза.
 */
@Component
public class DescribedFields {

    /** Поле ответа и его чувствительность — для консоли. */
    public record Field(String name, Sensitivity sensitivity) {
    }

    private final Map<Class<?>, List<Field>> byType = new ConcurrentHashMap<>();

    public List<Field> of(ToolDescriptor descriptor) {
        Class<?> output = descriptor.outputType();
        if (output == null || !output.isRecord()) {
            return List.of();
        }
        return byType.computeIfAbsent(output, DescribedFields::scan);
    }

    public boolean has(ToolDescriptor descriptor, String field) {
        return of(descriptor).stream().anyMatch(f -> f.name().equals(field));
    }

    private static List<Field> scan(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            Classified classified = component.getAnnotation(Classified.class);
            if (classified != null && classified.value() != Sensitivity.PUBLIC) {
                fields.add(new Field(component.getName(), classified.value()));
            }
        }
        return List.copyOf(fields);
    }
}
