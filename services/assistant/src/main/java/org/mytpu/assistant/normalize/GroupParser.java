package org.mytpu.assistant.normalize;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Распознавание номера группы ТПУ и приведение к каноническому виду
 * (в верхнем регистре кириллицей).
 */
public final class GroupParser {

    private static final int FLAGS =
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern GROUP_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{1,3})\\s*-?\\s*([а-яёa-z])\\s*-?\\s*"
            + "(?:(\\d{2})|(\\d)-?([а-яёa-z])|(\\d))(?![\\p{L}\\p{N}])",
            FLAGS
    );

    private GroupParser() {}

    public static Optional<String> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = GROUP_PATTERN.matcher(text);
        if (matcher.find()) {
            String digits1 = matcher.group(1);
            char letter1 = toCyrillicUpper(matcher.group(2).charAt(0));

            StringBuilder canonical = new StringBuilder()
                    .append(digits1)
                    .append(letter1);

            if (matcher.group(3) != null) {
                canonical.append(matcher.group(3));
            } else if (matcher.group(4) != null) {
                canonical.append(matcher.group(4));
                if (matcher.group(5) != null && !matcher.group(5).isBlank()) {
                    canonical.append(toCyrillicUpper(matcher.group(5).charAt(0)));
                }
            } else if (matcher.group(6) != null) {
                canonical.append(matcher.group(6));
            }

            return Optional.of(canonical.toString());
        }

        return Optional.empty();
    }

    private static char toCyrillicUpper(char c) {
        return switch (c) {
            case 'A', 'a', 'А', 'а' -> 'А';
            case 'B', 'b', 'В', 'в' -> 'В';
            case 'E', 'e', 'Е', 'е' -> 'Е';
            case 'K', 'k', 'К', 'к' -> 'К';
            case 'M', 'm', 'М', 'м' -> 'М';
            case 'H', 'h', 'Н', 'н' -> 'Н';
            case 'O', 'o', 'О', 'о' -> 'О';
            case 'P', 'p', 'Р', 'р' -> 'Р';
            case 'C', 'c', 'С', 'с' -> 'С';
            case 'T', 't', 'Т', 'т' -> 'Т';
            case 'X', 'x', 'Х', 'х' -> 'Х';
            default -> Character.toUpperCase(c);
        };
    }
}
