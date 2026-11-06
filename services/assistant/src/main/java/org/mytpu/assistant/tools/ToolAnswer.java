package org.mytpu.assistant.tools;

import java.util.List;
import java.util.Objects;

import org.mytpu.assistant.dto.Source;
import tools.jackson.databind.JsonNode;

/**
 * Разобранный ответ инструмента из шлюза (specs/assistant/spec.md, шаг 8).
 *
 * @param summary готовая текстовая фраза
 * @param data    сырой узел с данными ответа
 * @param hint    подсказка дальнейших действий (может быть null)
 * @param found   признак успешного нахождения данных
 * @param sources список извлечённых ссылок-источников
 */
public record ToolAnswer(
        String summary,
        JsonNode data,
        String hint,
        boolean found,
        List<Source> sources
) {
    public ToolAnswer {
        Objects.requireNonNull(summary, "summary");
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
