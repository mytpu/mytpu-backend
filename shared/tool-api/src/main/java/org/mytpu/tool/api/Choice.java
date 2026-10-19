package org.mytpu.tool.api;

/**
 * Вариант, из которого выбирает человек, когда запрос неоднозначен.
 *
 * @param label что показать («8К31 (ИШИТР)»)
 * @param value что подставить в повторный вызов
 */
public record Choice(String label, String value) {
}
