package org.mytpu.assistant.llm;

/**
 * Путь с моделью прерван: неверные аргументы дважды, слишком много вызовов, таймаут или сбой модели.
 * Ответ тогда собирается шаблоном из данных (specs/assistant/spec.md, шаг 10).
 */
public class ModelGaveUp extends RuntimeException {
    private final String reason;

    public ModelGaveUp(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    public ModelGaveUp(String reason, Throwable cause) {
        super(reason, cause, false, false);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
