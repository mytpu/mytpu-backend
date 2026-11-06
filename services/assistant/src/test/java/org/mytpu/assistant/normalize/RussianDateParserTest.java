package org.mytpu.assistant.normalize;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RussianDateParserTest {

    // Воскресенье 01.11.2026, 12:00 по Томску (UTC+7)
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-11-01T05:00:00Z"),
            ZoneId.of("Asia/Tomsk")
    );

    @ParameterizedTest(name = "{0} -> {1}..{2}")
    @DisplayName("Разбор дат и диапазонов относительно фиксированного времени")
    @CsvSource(value = {
            "сегодня | 2026-11-01 | 2026-11-01",
            "завтра | 2026-11-02 | 2026-11-02",
            "послезавтра | 2026-11-03 | 2026-11-03",
            "вчера | 2026-10-31 | 2026-10-31",
            "на этой неделе | 2026-10-26 | 2026-11-01",
            "на следующей неделе | 2026-11-02 | 2026-11-08",
            "в воскресенье | 2026-11-01 | 2026-11-01",
            "в понедельник | 2026-11-02 | 2026-11-02",
            "во вторник | 2026-11-03 | 2026-11-03",
            "в среду | 2026-11-04 | 2026-11-04",
            "в четверг | 2026-11-05 | 2026-11-05",
            "в пятницу | 2026-11-06 | 2026-11-06",
            "в субботу | 2026-11-07 | 2026-11-07",
            "в следующий понедельник | 2026-11-09 | 2026-11-09",
            "в следующее воскресенье | 2026-11-08 | 2026-11-08",
            "через 2 дня | 2026-11-03 | 2026-11-03",
            "через день | 2026-11-03 | 2026-11-03",
            "через 3 дня | 2026-11-04 | 2026-11-04",
            "через неделю | 2026-11-08 | 2026-11-08",
            "19.10 | 2026-10-19 | 2026-10-19",
            "19.10.2026 | 2026-10-19 | 2026-10-19",
            "2026-10-19 | 2026-10-19 | 2026-10-19",
            "19 октября | 2026-10-19 | 2026-10-19",
            "19 октября 2026 | 2026-10-19 | 2026-10-19",
            "12 ноября | 2026-11-12 | 2026-11-12",
            "1 января | 2026-01-01 | 2026-01-01",
            "15 мая 2027 года | 2027-05-15 | 2027-05-15"
    }, delimiter = '|')
    void shouldParseDate(String input, String expectedFrom, String expectedTo) {
        Optional<DateQuery> result = RussianDateParser.parse(input, FIXED_CLOCK);
        assertTrue(result.isPresent(), "Ожидалась дата для: " + input);
        assertEquals(LocalDate.parse(expectedFrom), result.get().from());
        assertEquals(LocalDate.parse(expectedTo), result.get().to());
    }

    @ParameterizedTest
    @DisplayName("Отрицательные случаи: некорректные даты или отсутствие дат")
    @ValueSource(strings = {
            "31.02",
            "31.02.2026",
            "31 февраля",
            "30 февраля",
            "8К31",
            "403б",
            "привет",
            "",
            "   "
    })
    void shouldReturnEmptyForInvalidDates(String input) {
        assertTrue(RussianDateParser.parse(input, FIXED_CLOCK).isEmpty());
    }
}
