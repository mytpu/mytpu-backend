package org.mytpu.assistant.conversation;

/**
 * Репозиторий долговременной истории сообщений (specs/assistant/spec.md, шаг 7).
 * До появления сервиса identity отключён настройкой {@code mytpu.assistant.history.enabled=false}.
 */
public interface HistoryRepository {

    /**
     * Сохранение записи истории.
     *
     * @param conversationId идентификатор диалога
     * @param messageId      идентификатор сообщения
     * @param role           роль автора ("user", "assistant")
     * @param content        текст сообщения
     */
    void save(String conversationId, String messageId, String role, String content);
}
