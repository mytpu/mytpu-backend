package org.mytpu.assistant.tools;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.mytpu.assistant.dto.CardEvent;
import org.mytpu.assistant.dto.Source;

/**
 * Результат прямого ответа без модели (specs/assistant/spec.md, шаг 8).
 *
 * @param message итоговый текст ответа
 * @param card    опциональная карточка инструмента
 * @param sources список источников
 */
public record DirectResult(
        String message,
        Optional<CardEvent> card,
        List<Source> sources
) {
    public DirectResult {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(card, "card");
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static DirectResult textOnly(String message) {
        return new DirectResult(message, Optional.empty(), List.of());
    }
}
