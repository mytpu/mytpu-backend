package org.mytpu.tool.api;

import java.util.Objects;

/**
 * Ответ инструмента, готовый к выдаче даже слабой модели (docs/LLM.md, раздел 2).
 *
 * @param summary готовая фраза на русском: её можно отдать пользователю как есть
 * @param data    структурированные данные для карточки
 * @param hint          что делать дальше; может быть {@code null}
 * @param publicSummary та же мысль без личных данных — для внешних агентов (specs/personal-data-for-agents);
 *                      обязательна у личных инструментов, иначе внешний агент получит общую фразу
 */
public record ToolAnswer<T>(String summary, T data, String hint, String publicSummary) {
    public ToolAnswer {
        Objects.requireNonNull(summary, "summary");
    }

    public ToolAnswer(String summary, T data, String hint) {
        this(summary, data, hint, null);
    }

    public static <T> ToolAnswer<T> of(String summary, T data) {
        return new ToolAnswer<>(summary, data, null, null);
    }

    /** Ответ личного инструмента: полная фраза для своих клиентов и безопасная — для внешних агентов. */
    public static <T> ToolAnswer<T> personal(String summary, String publicSummary, T data) {
        return new ToolAnswer<>(summary, data, null, publicSummary);
    }
}
