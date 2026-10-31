package org.mytpu.gateway.access;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Мастер-ключ клиента: что ему открыто (docs/SECURITY.md, раздел 14). Сам ключ не хранится — только хэш и префикс.
 *
 * @param scopes          включённые интеграции, например {@code rasp.read}, {@code docs.read}
 * @param roles           роли консоли: {@code console.admin}, {@code console.viewer}
 * @param deniedTools     выключенные инструменты внутри включённых интеграций
 * @param rateLimits      свои лимиты вызовов в минуту: инструмент → число
 * @param readOnly        только чтение: инструменты записи недоступны
 * @param kind            {@code person} — человек; {@code server} — интеграция с постоянным адресом (сети обязательны); {@code demo} — показ, хакатон
 * @param allowedNetworks сети CIDR, с которых ключ принимается; пусто — любые
 * @param suspendedAt     ключ приостановлен (подозрение на утечку) — вызовы отвергаются до снятия
 * @param decoy           ключ-ловушка: прав нет, любое использование — событие {@code key.decoy_used}
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
        Instant expiresAt,
        Kind kind,
        List<String> allowedNetworks,
        Instant suspendedAt,
        boolean decoy
) {
    public static final String CONSOLE_ADMIN = "console.admin";
    public static final String CONSOLE_VIEWER = "console.viewer";

    public enum Kind {
        PERSON, SERVER, DEMO;

        public String db() {
            return name().toLowerCase();
        }

        public static Kind fromDb(String value) {
            return valueOf(value.toUpperCase());
        }
    }

    public boolean activeAt(Instant now) {
        return enabled && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean suspended() {
        return suspendedAt != null;
    }

    public boolean hasConsoleRole() {
        return roles.contains(CONSOLE_ADMIN) || roles.contains(CONSOLE_VIEWER);
    }
}
