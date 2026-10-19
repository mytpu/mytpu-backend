package org.mytpu.gateway.access;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Мастер-ключ клиента: что ему открыто (docs/SECURITY.md, раздел 14). Сам ключ не хранится — только хэш и префикс.
 *
 * @param scopes      включённые интеграции, например {@code rasp.read}, {@code docs.read}
 * @param roles       роли консоли: {@code console.admin}, {@code console.viewer}
 * @param deniedTools выключенные инструменты внутри включённых интеграций
 * @param rateLimits  свои лимиты вызовов в минуту: инструмент → число
 * @param readOnly    только чтение: инструменты записи недоступны
 */
public record ApiClient(
        String id,
        String owner,
        String keyPrefix,
        Set<String> scopes,
        Set<String> roles,
        Set<String> deniedTools,
        Map<String, Integer> rateLimits,
        boolean readOnly,
        boolean enabled,
        Instant expiresAt
) {
    public static final String CONSOLE_ADMIN = "console.admin";
    public static final String CONSOLE_VIEWER = "console.viewer";

    public boolean activeAt(Instant now) {
        return enabled && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean hasConsoleRole() {
        return roles.contains(CONSOLE_ADMIN) || roles.contains(CONSOLE_VIEWER);
    }
}
