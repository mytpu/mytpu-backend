package org.mytpu.assistant.conversation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Состояние диалога: последние до 6 сообщений и накопленные слоты контекста (specs/assistant/spec.md, шаг 7).
 */
public record ConversationState(
        List<ConversationMessage> messages,
        Map<String, String> slots
) {
    public static final int MAX_MESSAGES = 6;

    public ConversationState {
        messages = messages == null ? List.of() : List.copyOf(messages);
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }

    public static ConversationState empty() {
        return new ConversationState(List.of(), Map.of());
    }

    public ConversationState withMessage(String role, String content) {
        List<ConversationMessage> updated = new ArrayList<>(this.messages);
        updated.add(new ConversationMessage(role, content));
        if (updated.size() > MAX_MESSAGES) {
            updated = updated.subList(updated.size() - MAX_MESSAGES, updated.size());
        }
        return new ConversationState(updated, this.slots);
    }

    public ConversationState withSlots(Map<String, String> newSlots) {
        if (newSlots == null || newSlots.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new HashMap<>(this.slots);
        merged.putAll(newSlots);
        return new ConversationState(this.messages, merged);
    }
}
