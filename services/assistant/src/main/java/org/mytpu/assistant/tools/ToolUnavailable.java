package org.mytpu.assistant.tools;

import org.mytpu.assistant.errors.ChatException;
import org.springframework.http.HttpStatus;

/**
 * Исключение недоступности или сбоя инструмента шлюза (specs/assistant/spec.md, шаг 8).
 */
public class ToolUnavailable extends ChatException {

    public ToolUnavailable(String code) {
        super("upstream_timeout".equals(code) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                code != null ? code : "tool_unavailable");
    }

    public ToolUnavailable(String code, String message) {
        super("upstream_timeout".equals(code) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                code != null ? code : "tool_unavailable");
    }
}
