package org.mytpu.assistant.normalize;

import java.time.LocalDate;

/**
 * Диапазон дат (один день: from == to; неделя: пн–вс).
 */
public record DateQuery(LocalDate from, LocalDate to) {

    public DateQuery {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from и to не должны быть null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from (" + from + ") не может быть позже to (" + to + ")");
        }
    }

    public static DateQuery ofDay(LocalDate date) {
        return new DateQuery(date, date);
    }

    public static DateQuery ofWeek(LocalDate monday) {
        return new DateQuery(monday, monday.plusDays(6));
    }

    public boolean isSingleDay() {
        return from.equals(to);
    }
}
