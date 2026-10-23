package org.mytpu.tool.api;

/** Маска личного поля в режиме {@link DisclosureMode#MASKED}. */
public enum Mask {
    /** Поле убирается (значение {@code null}). */
    HIDE,
    /** Только инициалы: «Иванов Иван Иванович» → «И. И. И.». */
    INITIALS,
    /** Список → число элементов. */
    COUNT,
    /** Дата и время → месяц ({@code 2026-10}); не дата — как {@link #HIDE}. */
    COARSE
}
