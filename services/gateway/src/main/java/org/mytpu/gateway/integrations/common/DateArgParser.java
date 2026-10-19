package org.mytpu.gateway.integrations.common;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Дата из человеческого аргумента: «сегодня», «завтра», «послезавтра», «вчера», дни недели («в пятницу», «пн»),
 * «19.10», «19.10.2026», «19 октября», ISO «2026-10-19». Модель не считает даты сама (docs/LLM.md, раздел 2).
 * День недели — ближайший такой день, начиная с сегодняшнего. Часовой пояс — Томск.
 */
public final class DateArgParser {
    public static final ZoneId TOMSK = ZoneId.of("Asia/Tomsk");

    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DOTTED = Pattern.compile("(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?");
    private static final Pattern WORDS = Pattern.compile("(\\d{1,2})\\s+([а-я]+)(?:\\s+(\\d{4}))?");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("январ", 1), Map.entry("феврал", 2), Map.entry("март", 3), Map.entry("апрел", 4),
            Map.entry("ма", 5), Map.entry("июн", 6), Map.entry("июл", 7), Map.entry("август", 8),
            Map.entry("сентябр", 9), Map.entry("октябр", 10), Map.entry("ноябр", 11), Map.entry("декабр", 12));
    private static final Map<String, DayOfWeek> DAYS = Map.ofEntries(
            Map.entry("понедельник", DayOfWeek.MONDAY), Map.entry("пн", DayOfWeek.MONDAY),
            Map.entry("вторник", DayOfWeek.TUESDAY), Map.entry("вт", DayOfWeek.TUESDAY),
            Map.entry("сред", DayOfWeek.WEDNESDAY), Map.entry("ср", DayOfWeek.WEDNESDAY),
            Map.entry("четверг", DayOfWeek.THURSDAY), Map.entry("чт", DayOfWeek.THURSDAY),
            Map.entry("пятниц", DayOfWeek.FRIDAY), Map.entry("пт", DayOfWeek.FRIDAY),
            Map.entry("суббот", DayOfWeek.SATURDAY), Map.entry("сб", DayOfWeek.SATURDAY),
            Map.entry("воскресень", DayOfWeek.SUNDAY), Map.entry("вс", DayOfWeek.SUNDAY));

    private final Clock clock;

    public DateArgParser(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(TOMSK));
    }

    /** Пусто или {@code null} — сегодня; не разобрали — {@link Optional#empty()}. */
    public Optional<LocalDate> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(today());
        }
        String text = value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        LocalDate today = today();
        switch (text) {
            case "сегодня": return Optional.of(today);
            case "завтра": return Optional.of(today.plusDays(1));
            case "послезавтра": return Optional.of(today.plusDays(2));
            case "вчера": return Optional.of(today.minusDays(1));
            default: break;
        }
        try {
            Matcher m = ISO.matcher(text);
            if (m.matches()) {
                return Optional.of(LocalDate.of(num(m, 1), num(m, 2), num(m, 3)));
            }
            m = DOTTED.matcher(text);
            if (m.matches()) {
                return Optional.of(LocalDate.of(year(m.group(3), today), num(m, 2), num(m, 1)));
            }
            m = WORDS.matcher(text);
            if (m.matches()) {
                Integer month = month(m.group(2));
                return month == null ? Optional.empty() : Optional.of(LocalDate.of(year(m.group(3), today), month, num(m, 1)));
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        return weekday(text).map(day -> today.with(TemporalAdjusters.nextOrSame(day)));
    }

    private static Optional<DayOfWeek> weekday(String text) {
        String word = text.replaceFirst("^(в|во|на)\\s+", "").replaceFirst("^(следующ\\S*|ближайш\\S*)\\s+", "");
        return DAYS.entrySet().stream()
                .filter(e -> e.getKey().length() <= 2 ? word.equals(e.getKey()) : word.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static Integer month(String word) {
        // «мая» и «май» — короткий корень; остальные корни длиннее и не пересекаются
        if (word.equals("мая") || word.equals("май")) {
            return 5;
        }
        return MONTHS.entrySet().stream().filter(e -> e.getKey().length() > 2 && word.startsWith(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static int year(String value, LocalDate today) {
        if (value == null) {
            return today.getYear();
        }
        int year = Integer.parseInt(value);
        return year < 100 ? 2000 + year : year;
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }
}
