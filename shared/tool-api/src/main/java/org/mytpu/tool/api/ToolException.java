package org.mytpu.tool.api;

import java.util.Objects;

/**
 * Ожидаемый сбой внутри инструмента (внешний сервис ответил ошибкой, ничего не найдено).
 * Исполнитель превращает его в {@link ToolResult.Failed} с этим кодом; сообщение уходит клиенту, поэтому без подробностей реализации.
 */
public class ToolException extends RuntimeException {
    private final ErrorCode code;

    public ToolException(ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ToolException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }
}
