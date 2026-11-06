package org.mytpu.assistant.verify;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mytpu.assistant.llm.ToolTrace;
import org.springframework.stereotype.Component;

/**
 * Проверка ответа модели по данным инструментов (specs/assistant/spec.md, шаг 10). Ссылки — белый список: любое имя
 * хоста в ответе (со схемой, без неё, в почте, в {@code //host}) должно быть {@code tpu.ru} или его поддоменом и
 * встречаться в данных инструментов; опасные схемы ({@code javascript:} и т. п.) отвергаются. Время, номера телефонов
 * и прочие числа должны встречаться в данных (числа — ещё и в вопросе: группа, аудитория). Расхождение — шаблон.
 */
@Component
public class AnswerVerifier {
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern URL = Pattern.compile("[a-z][a-z0-9+.-]*://[^\\s<>\"'«»()\\[\\]]+", FLAGS);
    /** Имя хоста с любой зоной, в том числе punycode; метки только латиницей — «т.е.» и «справка.pdf» сюда не попадут. */
    private static final Pattern HOST = Pattern.compile("(?<![a-z0-9-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})(?![a-z0-9-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_SCHEME = Pattern.compile("(?<![a-z])(?:javascript|data|vbscript|file|tg|intent):", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b", FLAGS);
    /** Номер из цифр с пробелами, скобками и дефисами (телефон, счёт): сверяется целиком, а не по группам цифр. */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\+?\\d[\\d\\s()\\-]{5,}\\d", FLAGS);
    private static final Pattern NUMBER = Pattern.compile("\\d+", FLAGS);
    /** Номер пункта списка в начале строки («1.», «2)») — оформление ответа, а не факт. */
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*\\d{1,2}[.)]\\s", FLAGS);

    public Optional<String> problem(String answer, ToolTrace trace, String question) {
        String data = data(trace);
        String dataLower = data.toLowerCase(Locale.ROOT);

        if (DANGEROUS_SCHEME.matcher(answer).find()) {
            return Optional.of("foreign_link");
        }
        Matcher url = URL.matcher(answer);
        while (url.find()) {
            String link = trimPunctuation(url.group());
            if (!isTpu(link)) {
                return Optional.of("foreign_link");
            }
            if (!dataLower.contains(link.toLowerCase(Locale.ROOT))) {
                return Optional.of("link_not_in_data");
            }
        }
        // Хосты — в тексте без уже проверенных ссылок: иначе «file.pdf» в пути ссылки сочтётся чужим хостом
        Matcher host = HOST.matcher(URL.matcher(answer).replaceAll(" "));
        while (host.find()) {
            String name = host.group().toLowerCase(Locale.ROOT);
            if (!isTpuHost(name)) {
                return Optional.of("foreign_link");
            }
            if (!dataLower.contains(name)) {
                return Optional.of("link_not_in_data");
            }
        }

        String text = URL.matcher(answer).replaceAll(" ");
        Matcher time = TIME.matcher(text);
        while (time.find()) {
            if (!data.contains(time.group())) {
                return Optional.of("time_not_in_data");
            }
        }

        Set<String> dataLongNumbers = longNumbers(data);
        Matcher longNumber = LONG_NUMBER.matcher(LIST_MARKER.matcher(text).replaceAll(" "));
        while (longNumber.find()) {
            String digits = longNumber.group().replaceAll("\\D", "");
            if (digits.length() >= 7 && dataLongNumbers.stream().noneMatch(d -> d.contains(digits))) {
                return Optional.of("number_not_in_data");
            }
        }

        Set<String> known = numbers(data + "\n" + (question == null ? "" : question));
        Matcher number = NUMBER.matcher(LIST_MARKER.matcher(text).replaceAll(" "));
        while (number.find()) {
            if (!known.contains(stripZeros(number.group()))) {
                return Optional.of("number_not_in_data");
            }
        }
        return Optional.empty();
    }

    /** Только данные инструментов: вопрос пользователя не доказывает ни ссылку, ни телефон. */
    private static String data(ToolTrace trace) {
        StringBuilder sb = new StringBuilder();
        for (ToolTrace.Call call : trace.calls()) {
            sb.append('\n').append(call.answer().summary());
            if (call.answer().data() != null) {
                sb.append('\n').append(call.answer().data().toString());
            }
            call.answer().sources().forEach(s -> sb.append('\n').append(s.title()).append(' ').append(s.url()));
        }
        return sb.toString();
    }

    private static Set<String> longNumbers(String data) {
        Set<String> out = new HashSet<>();
        Matcher m = LONG_NUMBER.matcher(data);
        while (m.find()) {
            out.add(m.group().replaceAll("\\D", ""));
        }
        Matcher plain = NUMBER.matcher(data);
        while (plain.find()) {
            out.add(plain.group());
        }
        return out;
    }

    private static Set<String> numbers(String evidence) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER.matcher(evidence);
        while (m.find()) {
            out.add(stripZeros(m.group()));
        }
        return out;
    }

    private static String stripZeros(String digits) {
        return digits.replaceFirst("^0+(?=\\d)", "");
    }

    private static String trimPunctuation(String link) {
        return link.replaceAll("[.,;:!?]+$", "");
    }

    public static boolean isTpu(String link) {
        try {
            URI uri = URI.create(link);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && isTpuHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isTpuHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("tpu.ru") || h.endsWith(".tpu.ru");
    }
}
