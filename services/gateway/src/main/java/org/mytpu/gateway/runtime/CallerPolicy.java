package org.mytpu.gateway.runtime;

import java.util.Map;
import java.util.Set;

/**
 * Ограничения вызывающего сверх scopes: из мастер-ключа или политики клиента.
 *
 * @param deniedTools выключенные инструменты внутри разрешённых интеграций
 * @param rateLimits  свои лимиты в минуту: инструмент → число (иначе — лимит из описания инструмента)
 * @param readOnly    инструменты записи недоступны
 */
public record CallerPolicy(Set<String> deniedTools, Map<String, Integer> rateLimits, boolean readOnly) {

    public CallerPolicy {
        deniedTools = Set.copyOf(deniedTools);
        rateLimits = Map.copyOf(rateLimits);
    }

    public static CallerPolicy unrestricted() {
        return new CallerPolicy(Set.of(), Map.of(), false);
    }
}
