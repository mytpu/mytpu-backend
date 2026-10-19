package org.mytpu.tool.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Кто и откуда вызывает инструмент. Собирается дверью (MCP, чат, hub) и передаётся исполнителю.
 *
 * @param userId    наш id пользователя; {@code null} — вызов без пользователя (мастер-ключ клиента)
 * @param roles     роли пользователя: студент, сотрудник, администратор
 * @param clientId  кто вызывает: id мастер-ключа, {@code mytpu-web}, {@code mytpu-mobile}
 * @param scopes    разрешённые группы инструментов
 * @param requestId id запроса для журнала
 */
public record CallContext(
        String userId,
        Set<String> roles,
        String clientId,
        Channel channel,
        Set<String> scopes,
        String requestId
) {
    public CallContext {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(requestId, "requestId");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public Optional<String> user() {
        return Optional.ofNullable(userId);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
