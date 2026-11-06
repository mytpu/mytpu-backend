package org.mytpu.assistant.conversation;

import java.util.Objects;

/**
 * Сообщение в истории диалога (specs/assistant/spec.md, шаг 7).
 *
 * @param role    роль автора ("user", "assistant", "system", "tool")
 * @param content текст сообщения
 */
public record ConversationMessage(String role, String content) {
    public ConversationMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
