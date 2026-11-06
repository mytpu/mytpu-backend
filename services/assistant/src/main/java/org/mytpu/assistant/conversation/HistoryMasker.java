package org.mytpu.assistant.conversation;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Маскирование персональных данных пользователя перед сохранением в историю диалогов (спецификация 5, шаг 7).
 */
@Component
public class HistoryMasker {

    private static final int FLAGS =
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
            FLAGS
    );

    private static final Pattern GRADEBOOK_PATTERN = Pattern.compile(
            "(?<=\\b)(зач[её]тк\\p{L}*(?:\\s*(?:№|N|#|номер))?[\\s:]*)(\\d{6,8})(?!\\d)",
            FLAGS
    );

    private static final Pattern PASSPORT_PREFIX_PATTERN = Pattern.compile(
            "(?<=\\b)(паспорт\\p{L}*(?:\\s*(?:серия|номер|№|N))?[\\s:]*)(\\d{4}\\s?\\d{6})(?!\\d)",
            FLAGS
    );

    private static final Pattern PASSPORT_POSTFIX_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{4}\\s?\\d{6})([\\s,]+(?:\\(?\\s*)паспорт\\p{L}*)",
            FLAGS
    );

    private static final Pattern PASSPORT_SPACED_PATTERN = Pattern.compile(
            "(?<!\\d)\\b\\d{4}\\s\\d{6}\\b(?!\\d)",
            FLAGS
    );

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}+])(?:\\+7|[78])(?:[\\s()\\-]*\\d){10}(?![\\p{L}\\p{N}])",
            FLAGS
    );

    public String mask(String text) {
        if (text == null) {
            return null;
        }
        if (text.isBlank()) {
            return text;
        }

        String result = EMAIL_PATTERN.matcher(text).replaceAll("[ПОЧТА]");
        result = GRADEBOOK_PATTERN.matcher(result).replaceAll("$1[ЗАЧЁТКА]");
        result = PASSPORT_PREFIX_PATTERN.matcher(result).replaceAll("$1[ПАСПОРТ]");
        result = PASSPORT_POSTFIX_PATTERN.matcher(result).replaceAll("[ПАСПОРТ]$2");
        result = PASSPORT_SPACED_PATTERN.matcher(result).replaceAll("[ПАСПОРТ]");
        result = PHONE_PATTERN.matcher(result).replaceAll("[ТЕЛЕФОН]");

        return result;
    }
}
