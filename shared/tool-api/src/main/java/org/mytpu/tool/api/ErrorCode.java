package org.mytpu.tool.api;

/** Коды отказов и ошибок, которые видят клиенты. Тексты-инструкции к ним — в ресурсах шлюза. */
public enum ErrorCode {
    TOOL_NOT_FOUND,
    TOOL_DISABLED,
    TOOL_NOT_ALLOWED,
    GATEWAY_MODE,
    RATE_LIMITED,
    INVALID_ARGUMENTS,
    NOT_FOUND,
    UPSTREAM_UNAVAILABLE,
    UPSTREAM_TIMEOUT,
    UPSTREAM_ERROR,
    INTERNAL_ERROR,
    /** Нет согласия пользователя на личные данные для этого клиента или оно истекло. */
    NEEDS_CONSENT,
    /** Исчерпан суточный лимит личных записей для пары «пользователь — клиент». */
    PERSONAL_QUOTA;

    /** Код в ответе: {@code tool_disabled}, {@code rate_limited} и т. д. */
    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
