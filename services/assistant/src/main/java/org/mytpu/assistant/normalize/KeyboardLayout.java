package org.mytpu.assistant.normalize;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Исправление английской раскладки клавиатуры на русскую.
 * Применяется, только если в исходнике нет кириллицы, есть латиница
 * и после перевода получается правдоподобный русский текст.
 */
public final class KeyboardLayout {

    private static final String EN_KEYS =
            "`~qwertyuiop[]asdfghjkl;'zxcvbnm,."
            + "QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>";
    private static final String RU_KEYS =
            "ёЁйцукенгшщзхъфывапролджэячсмитьбю"
            + "ЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮ";

    private static final Map<Character, Character> EN_TO_RU = IntStream.range(0, EN_KEYS.length())
            .boxed()
            .collect(Collectors.toMap(EN_KEYS::charAt, RU_KEYS::charAt));

    private static final Set<String> EXCEPTIONS = Set.of(
            "mcp", "email", "moodle", "api", "tpu", "chat",
            "online", "teams", "zoom", "help", "id", "pdf"
    );

    private static final int FLAGS =
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern LATIN_PATTERN = Pattern.compile("[a-zA-Z]");
    private static final Pattern CYRILLIC_PATTERN = Pattern.compile("[а-яё]", FLAGS);
    private static final Pattern RUSSIAN_VOWELS = Pattern.compile("[аеёиоуыэюя]", FLAGS);
    private static final Pattern WORD_PATTERN = Pattern.compile("[а-яёa-z0-9]+", FLAGS);
    private static final Pattern LATIN_WORD = Pattern.compile("^[a-zA-Z]+$");
    private static final Pattern CONSECUTIVE_UPPER = Pattern.compile(".*[A-Z]{2,}.*");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z]+|[^a-zA-Z]+");

    // Частые опечатки раскладки при быстром вводе ключевых понятий (сдвиги пальцев в QWERTY)
    private static final Pattern TYPO_RASPISANIE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])растисани([а-яё]*)(?![\\p{L}\\p{N}])",
            FLAGS
    );
    private static final Pattern TYPO_PREPODAVATEL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])препокавател([а-яё]*)(?![\\p{L}\\p{N}])",
            FLAGS
    );
    private static final Pattern TYPO_ZAVTRA = Pattern.compile(
            "(?<![\\p{L}\\p{N}])заотра(?![\\p{L}\\p{N}])",
            FLAGS
    );

    // Невозможные сочетания в русских словах
    private static final Pattern[] IMPOSSIBLE = {
            Pattern.compile("^[ьъы]", FLAGS),
            Pattern.compile("[ъ]$", FLAGS),
            Pattern.compile("[аеёиоуыэюя][ь]", FLAGS),
            Pattern.compile("[^бвгджзклмнпрстфхцчшщ][ъ]", FLAGS),
            Pattern.compile("[ъ][^еёюя]", FLAGS),
            Pattern.compile("[ьъ]{2,}", FLAGS),
            Pattern.compile("[жшчщ][ы]", FLAGS),
            Pattern.compile("[чщ][яю]", FLAGS),
            Pattern.compile("[щ][бвгдзжкмпстфхцчшщ]", FLAGS),
            Pattern.compile("[гкхцчжш][щ]", FLAGS),
            Pattern.compile("^[й][бвгджзклмнпрстфхцчшщ]", FLAGS),
            Pattern.compile("[бвгджзклмнпрстфхцчшщ]{5,}", FLAGS),
            Pattern.compile("[аеёиоуыэюя]{4,}", FLAGS)
    };

    private KeyboardLayout() {}

    public static String fix(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (CYRILLIC_PATTERN.matcher(text).find() || !LATIN_PATTERN.matcher(text).find()) {
            return text;
        }

        String translated = translatePreservingExceptions(text);
        if (isValidRussianText(translated)) {
            return fixCommonTypos(translated);
        }
        return text;
    }

    private static String translatePreservingExceptions(String text) {
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            if (isLatinWord(token) && isException(token)) {
                sb.append(token);
            } else {
                for (int i = 0; i < token.length(); i++) {
                    char c = token.charAt(i);
                    sb.append(EN_TO_RU.getOrDefault(c, c));
                }
            }
        }
        return sb.toString();
    }

    private static boolean isLatinWord(String token) {
        return LATIN_WORD.matcher(token).matches();
    }

    private static boolean isException(String word) {
        return EXCEPTIONS.contains(word.toLowerCase(Locale.ROOT)) || CONSECUTIVE_UPPER.matcher(word).matches();
    }

    private static boolean isValidRussianText(String text) {
        Matcher matcher = WORD_PATTERN.matcher(text);
        int cyrillicWords = 0;
        int vowelWords = 0;

        while (matcher.find()) {
            String word = matcher.group();
            String lettersOnly = word.replaceAll("[0-9]+", "");
            if (lettersOnly.isEmpty()) {
                continue;
            }
            if (CYRILLIC_PATTERN.matcher(lettersOnly).find()) {
                cyrillicWords++;
                if (hasImpossibleCombination(lettersOnly)) {
                    return false;
                }
                if (lettersOnly.length() >= 2 && !RUSSIAN_VOWELS.matcher(lettersOnly).find()) {
                    return false;
                }
                if (RUSSIAN_VOWELS.matcher(lettersOnly).find()) {
                    vowelWords++;
                }
            }
        }

        if (cyrillicWords == 0) {
            return false;
        }
        return ((double) vowelWords / cyrillicWords) >= 0.60;
    }

    private static boolean hasImpossibleCombination(String word) {
        for (Pattern p : IMPOSSIBLE) {
            if (p.matcher(word).find()) {
                return true;
            }
        }
        return false;
    }

    private static String fixCommonTypos(String text) {
        String res = replacePattern(text, TYPO_RASPISANIE, "расписани");
        res = replacePattern(res, TYPO_PREPODAVATEL, "преподавател");
        res = replacePattern(res, TYPO_ZAVTRA, "завтра");
        return res;
    }

    private static String replacePattern(String text, Pattern pattern, String stemReplacement) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        do {
            String matched = m.group();
            String suffix = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : "";
            String repStem = matchCase(matched, stemReplacement);
            m.appendReplacement(sb, Matcher.quoteReplacement(repStem + suffix));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static String matchCase(String template, String target) {
        if (template.isEmpty()) {
            return target;
        }
        if (template.length() >= 2 && template.equals(template.toUpperCase(Locale.ROOT))) {
            return target.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(template.charAt(0))) {
            return Character.toUpperCase(target.charAt(0)) + target.substring(1);
        }
        return target.toLowerCase(Locale.ROOT);
    }
}
