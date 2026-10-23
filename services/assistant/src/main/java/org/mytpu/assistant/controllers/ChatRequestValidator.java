package org.mytpu.assistant.controllers;

import org.mytpu.assistant.config.ChatProperties;
import org.mytpu.assistant.dto.ChatRequest;
import org.mytpu.assistant.errors.ChatException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Сообщение обязательно, не пустое и не длиннее {@code mytpu.chat.max-message-chars}. */
@Component
public class ChatRequestValidator {
    private final ChatProperties properties;

    public ChatRequestValidator(ChatProperties properties) {
        this.properties = properties;
    }

    public void validate(ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "message_required");
        }
        if (request.message().length() > properties.maxMessageChars()) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "message_too_long");
        }
    }
}
