package org.mytpu.assistant.normalize;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomParserTest {

    @ParameterizedTest(name = "{0} -> building={1}, room={2}")
    @DisplayName("Распознавание аудитории и корпуса")
    @CsvSource(value = {
            "ауд 403б | null | 403б",
            "к.10 ауд 402 | 10 | 402",
            "10 корпус 101 | 10 | 101",
            "403б | null | 403б",
            "10-й корпус ауд 205 | 10 | 205",
            "10-го корпуса ауд. 101 | 10 | 101",
            "ауд. 101 10-го корпуса | 10 | 101",
            "корпус 2 ауд 301 | 2 | 301",
            "в 10 корпусе 101 | 10 | 101",
            "к 19 ауд 225 | 19 | 225",
            "ауд 402 | null | 402",
            "аудитория 204 | null | 204",
            "403b | null | 403б",
            "403Б | null | 403б",
            "403a | null | 403а",
            "205-а | null | 205а",
            "ГК ауд 101 | ГК | 101",
            "где находится ауд 403б? | null | 403б",
            "как пройти в к.10 ауд 402 | 10 | 402"
    }, delimiter = '|')
    void shouldParseRoomAndBuilding(String input, String expectedBuilding, String expectedRoom) {
        String building = "null".equals(expectedBuilding) ? null : expectedBuilding;
        Optional<Room> result = RoomParser.parse(input);
        assertTrue(result.isPresent(), "Ожидалось совпадение для: " + input);
        assertEquals(building, result.get().building());
        assertEquals(expectedRoom, result.get().room());
    }

    @ParameterizedTest
    @DisplayName("Отрицательные случаи: не аудитории")
    @ValueSource(strings = {
            "8",
            "8К31",
            "сегодня",
            "2026-10-19",
            "привет",
            "",
            "   "
    })
    void shouldReturnEmptyForNonRooms(String input) {
        assertTrue(RoomParser.parse(input).isEmpty());
    }
}
