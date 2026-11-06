package org.mytpu.assistant.normalize;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Детерминированный разбор дат и интервалов на русском языке относительно
 * часового пояса Asia/Tomsk.
 */
public final class RussianDateParser {

    public static final ZoneId TOMSK_ZONE = ZoneId.of("Asia/Tomsk");

    private static final int FLAGS =
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern NEXT_WEEK = Pattern.compile(
            "(?<![\\p{L}\\p{N}])на\\s+следующей\\s+неделе(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern THIS_WEEK = Pattern.compile(
            "(?<![\\p{L}\\p{N}])на\\s+этой\\s+неделе(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern IN_WEEK = Pattern.compile(
            "(?<![\\p{L}\\p{N}])через\\s+неделю(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern IN_DAYS = Pattern.compile(
            "(?<![\\p{L}\\p{N}])через\\s+(\\d+)\\s+(?:дн[а-яё]*|день)(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern IN_ONE_DAY = Pattern.compile(
            "(?<![\\p{L}\\p{N}])через\\s+день(?![\\p{L}\\p{N}])", FLAGS);

    private static final Pattern AFTER_TOMORROW = Pattern.compile(
            "(?<![\\p{L}\\p{N}])послезавтра(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern TOMORROW = Pattern.compile(
            "(?<![\\p{L}\\p{N}])завтра(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern TODAY = Pattern.compile(
            "(?<![\\p{L}\\p{N}])сегодня(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern YESTERDAY = Pattern.compile(
            "(?<![\\p{L}\\p{N}])вчера(?![\\p{L}\\p{N}])", FLAGS);

    private static final Pattern DOW_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:в|во)?\\s*(следующ[а-яё]*\\s+)?(понедельник[а-яё]*|вторник[а-яё]*|"
            + "сред[а-яё]*|четверг[а-яё]*|пятниц[а-яё]*|суббот[а-яё]*|воскресень[а-яё]*)(?![\\p{L}\\p{N}])",
            FLAGS
    );

    private static final Pattern ISO_DATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern RU_MONTH_DATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)"
            + "(?:\\s+(\\d{4})(?:\\s*г(?:ода|\\.)?)?)?(?![\\p{L}\\p{N}])",
            FLAGS
    );
    private static final Pattern FULL_NUM_DATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern SHORT_NUM_DATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{1,2})\\.(\\d{1,2})(?![\\p{L}\\p{N}])", FLAGS);

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("января", 1), Map.entry("февраля", 2), Map.entry("марта", 3),
            Map.entry("апреля", 4), Map.entry("мая", 5), Map.entry("июня", 6),
            Map.entry("июля", 7), Map.entry("августа", 8), Map.entry("сентября", 9),
            Map.entry("октября", 10), Map.entry("ноября", 11), Map.entry("декабря", 12)
    );

    private RussianDateParser() {}

    public static Optional<DateQuery> parse(String text) {
        return parse(text, Clock.system(TOMSK_ZONE));
    }

    public static Optional<DateQuery> parse(String text, Clock clock) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        LocalDate today = LocalDate.now(clock.withZone(TOMSK_ZONE));

        if (NEXT_WEEK.matcher(text).find()) {
            LocalDate nextMon = today.plusWeeks(1).with(DayOfWeek.MONDAY);
            return Optional.of(DateQuery.ofWeek(nextMon));
        }
        if (THIS_WEEK.matcher(text).find()) {
            LocalDate mon = today.with(DayOfWeek.MONDAY);
            return Optional.of(DateQuery.ofWeek(mon));
        }
        if (IN_WEEK.matcher(text).find()) {
            return Optional.of(DateQuery.ofDay(today.plusWeeks(1)));
        }
        Matcher inDaysMatcher = IN_DAYS.matcher(text);
        if (inDaysMatcher.find()) {
            int days = Integer.parseInt(inDaysMatcher.group(1));
            return Optional.of(DateQuery.ofDay(today.plusDays(days)));
        }
        if (IN_ONE_DAY.matcher(text).find()) {
            return Optional.of(DateQuery.ofDay(today.plusDays(2)));
        }
        if (AFTER_TOMORROW.matcher(text).find()) {
            return Optional.of(DateQuery.ofDay(today.plusDays(2)));
        }
        if (TOMORROW.matcher(text).find()) {
            return Optional.of(DateQuery.ofDay(today.plusDays(1)));
        }
        if (TODAY.matcher(text).find()) {
            return Optional.of(DateQuery.ofDay(today));
        }
        if (YESTERDAY.matcher(text).find()) {
            return Optional.of(DateQuery.ofDay(today.minusDays(1)));
        }

        Matcher dowMatcher = DOW_PATTERN.matcher(text);
        if (dowMatcher.find()) {
            boolean isNext = dowMatcher.group(1) != null;
            DayOfWeek dow = parseDayOfWeek(dowMatcher.group(2));
            if (dow != null) {
                int diff = (dow.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
                LocalDate target;
                if (isNext) {
                    LocalDate closest = today.plusDays(diff);
                    target = (diff == 0 || diff == 1) ? closest.plusWeeks(1) : today.plusWeeks(1).with(dow);
                } else {
                    target = today.plusDays(diff);
                }
                return Optional.of(DateQuery.ofDay(target));
            }
        }

        Matcher isoMatcher = ISO_DATE.matcher(text);
        if (isoMatcher.find()) {
            return tryCreateDate(
                    Integer.parseInt(isoMatcher.group(1)),
                    Integer.parseInt(isoMatcher.group(2)),
                    Integer.parseInt(isoMatcher.group(3))
            );
        }

        Matcher ruMonthMatcher = RU_MONTH_DATE.matcher(text);
        if (ruMonthMatcher.find()) {
            int day = Integer.parseInt(ruMonthMatcher.group(1));
            int month = MONTHS.get(ruMonthMatcher.group(2).toLowerCase(Locale.ROOT));
            int year = ruMonthMatcher.group(3) != null
                    ? Integer.parseInt(ruMonthMatcher.group(3))
                    : today.getYear();
            return tryCreateDate(year, month, day);
        }

        Matcher fullNumMatcher = FULL_NUM_DATE.matcher(text);
        if (fullNumMatcher.find()) {
            return tryCreateDate(
                    Integer.parseInt(fullNumMatcher.group(3)),
                    Integer.parseInt(fullNumMatcher.group(2)),
                    Integer.parseInt(fullNumMatcher.group(1))
            );
        }

        Matcher shortNumMatcher = SHORT_NUM_DATE.matcher(text);
        if (shortNumMatcher.find()) {
            return tryCreateDate(
                    today.getYear(),
                    Integer.parseInt(shortNumMatcher.group(2)),
                    Integer.parseInt(shortNumMatcher.group(1))
            );
        }

        return Optional.empty();
    }

    private static Optional<DateQuery> tryCreateDate(int year, int month, int day) {
        try {
            return Optional.of(DateQuery.ofDay(LocalDate.of(year, month, day)));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static DayOfWeek parseDayOfWeek(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("понедельник")) return DayOfWeek.MONDAY;
        if (lower.startsWith("вторник")) return DayOfWeek.TUESDAY;
        if (lower.startsWith("сред")) return DayOfWeek.WEDNESDAY;
        if (lower.startsWith("четверг")) return DayOfWeek.THURSDAY;
        if (lower.startsWith("пятниц")) return DayOfWeek.FRIDAY;
        if (lower.startsWith("суббот")) return DayOfWeek.SATURDAY;
        if (lower.startsWith("воскресень")) return DayOfWeek.SUNDAY;
        return null;
    }
}
