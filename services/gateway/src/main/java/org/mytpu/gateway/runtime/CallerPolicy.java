package org.mytpu.gateway.runtime;

import java.util.Map;
import java.util.Set;

/**
 * Ограничения вызывающего сверх scopes: из мастер-ключа или политики клиента.
 *
 * @param deniedTools  выключенные инструменты внутри разрешённых интеграций
 * @param deniedFields выключенные поля ответа: инструмент → поля
 * @param rateLimits  свои лимиты в минуту: инструмент → число (иначе — лимит из описания инструмента)
 * @param readOnly    инструменты записи недоступны
 */
public record CallerPolicy(Set<String> deniedTools, Map<String, Set<String>> deniedFields, Map<String, Integer> rateLimits,
                           boolean readOnly) {

    public CallerPolicy {
        deniedTools = Set.copyOf(deniedTools);
        deniedFields = deniedFields == null ? Map.of() : Map.copyOf(deniedFields);
        rateLimits = Map.copyOf(rateLimits);
    }

    public Set<String> deniedFields(String tool) {
        return deniedFields.getOrDefault(tool, Set.of());
    }

    public static CallerPolicy unrestricted() {
        return new CallerPolicy(Set.of(), Map.of(), Map.of(), false);
    }
}
