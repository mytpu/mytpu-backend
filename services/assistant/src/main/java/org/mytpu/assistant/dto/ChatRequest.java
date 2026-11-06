package org.mytpu.assistant.dto;

/**
 * Запрос к чату ассистента.
 *
 * @param message        текст сообщения пользователя
 * @param conversationId необязательный UUID диалога для продолжения контекста
 */
public record ChatRequest(
        String message,
        String conversationId
) {
    public ChatRequest(String message) {
        this(message, null);
    }
}
