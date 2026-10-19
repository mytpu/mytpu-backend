package org.mytpu.tool.api;

import java.util.List;
import java.util.Objects;

/** Итог вызова. Двери превращают его в свой формат (MCP, SSE чата, JSON hub). */
public sealed interface ToolResult<T> {

    record Ok<T>(ToolAnswer<T> answer) implements ToolResult<T> {
        public Ok {
            Objects.requireNonNull(answer, "answer");
        }
    }

    /** Неоднозначный запрос: 2–5 вариантов и готовый вопрос человеку. */
    record NeedsChoice<T>(String question, List<Choice> options) implements ToolResult<T> {
        public NeedsChoice {
            Objects.requireNonNull(question, "question");
            options = List.copyOf(options);
        }
    }

    /** Отказ до вызова интеграции: права, лимиты, аргументы, режим. */
    record Denied<T>(ErrorCode code, String message) implements ToolResult<T> {
    }

    /** Ошибка во время вызова: внешний сервис недоступен, таймаут, не найдено. */
    record Failed<T>(ErrorCode code, String message) implements ToolResult<T> {
    }

    static <T> ToolResult<T> ok(String summary, T data) {
        return new Ok<>(ToolAnswer.of(summary, data));
    }

    static <T> ToolResult<T> ok(ToolAnswer<T> answer) {
        return new Ok<>(answer);
    }

    static <T> ToolResult<T> needsChoice(String question, List<Choice> options) {
        return new NeedsChoice<>(question, options);
    }

    static <T> ToolResult<T> denied(ErrorCode code, String message) {
        return new Denied<>(code, message);
    }

    static <T> ToolResult<T> failed(ErrorCode code, String message) {
        return new Failed<>(code, message);
    }
}
