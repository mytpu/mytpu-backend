package org.mytpu.tool.api;

import java.util.Objects;

/**
 * Ответ инструмента, готовый к выдаче даже слабой модели (docs/LLM.md, раздел 2).
 *
 * @param summary готовая фраза на русском: её можно отдать пользователю как есть
 * @param data    структурированные данные для карточки
 * @param hint    что делать дальше; может быть {@code null}
 */
public record ToolAnswer<T>(String summary, T data, String hint) {
    public ToolAnswer {
        Objects.requireNonNull(summary, "summary");
    }

    public static <T> ToolAnswer<T> of(String summary, T data) {
        return new ToolAnswer<>(summary, data, null);
    }
}
