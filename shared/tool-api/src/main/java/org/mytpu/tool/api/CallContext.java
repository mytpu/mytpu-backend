package org.mytpu.tool.api;

import java.util.Map;
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
 * @param requestId         id запроса для журнала
 * @param trust             доверие к модели, которая увидит ответ (для MCP — уровень клиента)
 * @param senderConstrained токен привязан к ключу устройства (DPoP)
 * @param consents          согласия пользователя по scope для этого клиента
 */
public record CallContext(
        String userId,
        Set<String> roles,
        String clientId,
        Channel channel,
        Set<String> scopes,
        String requestId,
        Trust trust,
        boolean senderConstrained,
        Map<String, Consent> consents
) {
    public CallContext {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(requestId, "requestId");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        trust = trust == null ? Trust.EXTERNAL : trust;
        consents = consents == null ? Map.of() : Map.copyOf(consents);
    }

    /** Без пользовательских согласий: внешний уровень доверия, токен без привязки к устройству. */
    public CallContext(String userId, Set<String> roles, String clientId, Channel channel, Set<String> scopes, String requestId) {
        this(userId, roles, clientId, channel, scopes, requestId, Trust.EXTERNAL, false, Map.of());
    }

    public Optional<Consent> consent(String scope) {
        return Optional.ofNullable(consents.get(scope));
    }

    public Optional<String> user() {
        return Optional.ofNullable(userId);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
