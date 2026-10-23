package org.mytpu.assistant.dto;

/** Событие {@code error} потока чата: код и текст для пользователя; после него всегда приходит {@code done}. */
public record ErrorEvent(
        String code,
        String message
) {
}
