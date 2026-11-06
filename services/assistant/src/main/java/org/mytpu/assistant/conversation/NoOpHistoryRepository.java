package org.mytpu.assistant.conversation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Заглушка истории: ничего не делает, пока история выключена (по умолчанию).
 */
@Component
@ConditionalOnProperty(name = "mytpu.assistant.history.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpHistoryRepository implements HistoryRepository {

    @Override
    public void save(String conversationId, String messageId, String role, String content) {
        // Хранение в БД выключено настройкой mytpu.assistant.history.enabled=false
    }
}
