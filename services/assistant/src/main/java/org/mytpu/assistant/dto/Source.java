package org.mytpu.assistant.dto;

import java.util.Objects;

/**
 * Источник информации для ответа пользователю (specs/assistant/spec.md, шаг 8).
 *
 * @param title заголовок или наименование объекта
 * @param url   ссылка на портал или расписание ТПУ
 */
public record Source(String title, String url) {
    public Source {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
    }
}
