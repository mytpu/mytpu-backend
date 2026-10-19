package org.mytpu.assistant.dto;

public record MetaEvent(
        String conversation_id,
        String message_id,
        String request_id
) {
}
