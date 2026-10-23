package org.mytpu.assistant.errors;

import org.springframework.http.HttpStatus;

/** Отказ чата с кодом; текст — {@code chat.error.<код>} в {@code messages.properties}, ответ — JSON {@code {error, message}}. */
public class ChatException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ChatException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
