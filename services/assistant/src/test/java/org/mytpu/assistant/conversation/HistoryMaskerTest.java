package org.mytpu.assistant.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HistoryMaskerTest {

    private final HistoryMasker masker = new HistoryMasker();

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("Маскирование персональных данных и сохранение нейтрального текста")
    @CsvSource(delimiter = '|', value = {
            "+7 (913) 123-45-67 | [ТЕЛЕФОН]",
            "89131234567 | [ТЕЛЕФОН]",
            "+79131234567 | [ТЕЛЕФОН]",
            "8 (913) 123-45-67 | [ТЕЛЕФОН]",
            "79131234567 | [ТЕЛЕФОН]",
            "звоните на +7 (913) 123-45-67! | звоните на [ТЕЛЕФОН]!",
            "ivan.petrov@tpu.ru | [ПОЧТА]",
            "user.name@example.com | [ПОЧТА]",
            "напиши на ivan.petrov@tpu.ru срочно | напиши на [ПОЧТА] срочно",
            "паспорт 6912 345678 | паспорт [ПАСПОРТ]",
            "6912 345678 | [ПАСПОРТ]",
            "паспорт 6912345678 | паспорт [ПАСПОРТ]",
            "паспорта № 6912 345678 | паспорта № [ПАСПОРТ]",
            "номер зачётки 12345678 | номер зачётки [ЗАЧЁТКА]",
            "зачетка № 8123456 | зачетка № [ЗАЧЁТКА]",
            "зачётка 123456 | зачётка [ЗАЧЁТКА]",
            "зачетка: 87654321 | зачетка: [ЗАЧЁТКА]",
            "группа 8К31 | группа 8К31",
            "ауд. 101 10 корпус | ауд. 101 10 корпус",
            "12.11.2026 в 9:00 | 12.11.2026 в 9:00",
            "справка 2025 года | справка 2025 года",
            "приказ 1234 | приказ 1234",
            "телефон +7 (913) 123-45-67, почта ivan.petrov@tpu.ru | телефон [ТЕЛЕФОН], почта [ПОЧТА]",
            "паспорт 6912 345678 и зачетка № 8123456 | паспорт [ПАСПОРТ] и зачетка № [ЗАЧЁТКА]"
    })
    void shouldMaskPersonalData(String input, String expected) {
        assertEquals(expected, masker.mask(input));
    }

    @Test
    @DisplayName("Возврат null при передаче null")
    void shouldReturnNullForNullInput() {
        assertNull(masker.mask(null));
    }

    @Test
    @DisplayName("Сохранение пустой строки")
    void shouldReturnEmptyForEmptyInput() {
        assertEquals("", masker.mask(""));
    }
}
