package org.mytpu.assistant.dto;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * SSE-событие карточки инструмента (specs/assistant/spec.md, шаг 8).
 *
 * @param tool    имя вызванного инструмента (например, rasp_find)
 * @param summary готовая фраза с кратким итогом
 * @param data    структурированные данные для отображения в интерфейсе
 * @param hint    подсказка дальнейших действий (может быть null)
 */
public record CardEvent(
        String tool,
        String summary,
        JsonNode data,
        String hint
) {
    public CardEvent {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(summary, "summary");
    }
}
