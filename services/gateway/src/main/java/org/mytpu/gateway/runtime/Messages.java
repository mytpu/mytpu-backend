package org.mytpu.gateway.runtime;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Тексты шлюза: ошибки-инструкции ({@code messages.properties}) и описания инструментов для модели
 * ({@code tools/descriptions.properties}). В коде — только ключи.
 */
@Component
public class Messages {
    private static final Locale RU = Locale.forLanguageTag("ru");

    private final MessageSource source;

    public Messages(MessageSource source) {
        this.source = source;
    }

    public String text(String key, Object... args) {
        return source.getMessage(key, args, key, RU);
    }

    /** Текст, если ключ есть в ресурсах, иначе {@code null}. */
    public String optional(String key) {
        return source.getMessage(key, null, null, RU);
    }
}
