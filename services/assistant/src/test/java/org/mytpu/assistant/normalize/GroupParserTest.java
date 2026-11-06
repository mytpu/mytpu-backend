package org.mytpu.assistant.normalize;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupParserTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("Распознавание и канонизация номеров групп ТПУ")
    @CsvSource(value = {
            "8К31 | 8К31",
            "8к31 | 8К31",
            "8 К 31 | 8К31",
            "8-К-31 | 8К31",
            "0В21 | 0В21",
            "4А3А | 4А3А",
            "154А1 | 154А1",
            "8k31 | 8К31",
            "8-k-31 | 8К31",
            "8-K-31 | 8К31",
            "0B21 | 0В21",
            "0b21 | 0В21",
            "4A3A | 4А3А",
            "4a3a | 4А3А",
            "154A1 | 154А1",
            "2M31 | 2М31",
            "1C21 | 1С21",
            "8T41 | 8Т41",
            "0H11 | 0Н11",
            "3P21 | 3Р21",
            "5X31 | 5Х31",
            "9E11 | 9Е11",
            "расписание для группы 8К31 на завтра | 8К31",
            "где пары у 8-к-31? | 8К31",
            "группа 0В21 ауд 402 | 0В21"
    }, delimiter = '|')
    void shouldParseCanonicalGroup(String input, String expected) {
        assertEquals(Optional.of(expected), GroupParser.parse(input));
    }

    @ParameterizedTest
    @DisplayName("Отрицательные случаи: не группы ТПУ")
    @ValueSource(strings = {
            "8",
            "31.02",
            "403б",
            "2026-10-19",
            "10 корпус",
            "привет",
            "",
            "   "
    })
    void shouldReturnEmptyForNonGroups(String input) {
        assertTrue(GroupParser.parse(input).isEmpty());
    }
}
