package org.mytpu.assistant.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyboardLayoutTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("Исправление английской раскладки на русскую")
    @CsvSource(value = {
            "ghbdtn | привет",
            "cghfdrf j, j,extybb | справка об обучении",
            "rfr ltkf | как дела",
            "hfcnbcfybt | расписание",
            "gfhf | пара",
            "gfhs | пары",
            "rjhgec | корпус",
            "cneltyn | студент",
            "ghtgjrfdfntkm | преподаватель",
            "fel 403 | ауд 403",
            "ghbdtn MCP | привет MCP",
            "cghfdrf j, Moodle | справка об Moodle",
            "cghfdrf j, email | справка об email"
    }, delimiter = '|')
    void shouldFixLayout(String input, String expected) {
        assertEquals(expected, KeyboardLayout.fix(input));
    }

    @ParameterizedTest
    @DisplayName("Не трогать исключения и слова с >=2 заглавными буквами")
    @ValueSource(strings = {"MCP", "email", "Moodle", "API", "TPU", "LLM", "JSON"})
    void shouldNotTouchExceptions(String word) {
        assertEquals(word, KeyboardLayout.fix(word));
    }

    @ParameterizedTest
    @DisplayName("Не трогать английский текст и запросы")
    @ValueSource(strings = {
            "English text here",
            "hello world",
            "what is this",
            "show me the schedule",
            "where is room 101",
            "how to get transcript"
    })
    void shouldNotTouchEnglishText(String text) {
        assertEquals(text, KeyboardLayout.fix(text));
    }

    @ParameterizedTest
    @DisplayName("Не трогать строки, где уже есть кириллица, цифры или пусто")
    @ValueSource(strings = {
            "привет",
            "расписание 8К31",
            "где пара?",
            "12345",
            "",
            "   "
    })
    void shouldNotTouchAlreadyCyrillicOrEmpty(String text) {
        assertEquals(text, KeyboardLayout.fix(text));
    }
}
