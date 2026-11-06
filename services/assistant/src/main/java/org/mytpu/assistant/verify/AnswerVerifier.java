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
 * Проверка ответа модели по данным инструментов (specs/assistant/spec.md, шаг 10): каждое время, дата, аудитория и
 * число в ответе должно встречаться в данных инструментов или в самом вопросе; ссылка — только из данных и только
 * на {@code tpu.ru} и его поддомены. Нашлось расхождение — ответ заменяется шаблоном из данных.
 */
@Component
public class AnswerVerifier {
    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'«»()\\[\\]]+", FLAGS);
    private static final Pattern BARE_DOMAIN = Pattern.compile("\\b(?:[a-z0-9-]+\\.)+(?:ru|com|org|net|io|рф)\\b", FLAGS);
    private static final Pattern TIME = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b", FLAGS);
    private static final Pattern NUMBER = Pattern.compile("\\d+", FLAGS);
    /** Номер пункта списка в начале строки («1.», «2)») — оформление ответа, а не факт. */
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*\\d{1,2}[.)]\\s", FLAGS);

    public Optional<String> problem(String answer, ToolTrace trace, String question) {
        String evidence = evidence(trace, question);
        String evidenceLower = evidence.toLowerCase(Locale.ROOT);
        String text = URL.matcher(answer).replaceAll(m -> " ");

        Matcher url = URL.matcher(answer);
        while (url.find()) {
            String link = trimPunctuation(url.group());
            if (!isTpu(link)) {
                return Optional.of("foreign_link");
            }
            if (!evidenceLower.contains(link.toLowerCase(Locale.ROOT))) {
                return Optional.of("link_not_in_data");
            }
        }
        Matcher domain = BARE_DOMAIN.matcher(text);
        while (domain.find()) {
            String host = domain.group().toLowerCase(Locale.ROOT);
            if (!isTpuHost(host) || !evidenceLower.contains(host)) {
                return Optional.of("link_not_in_data");
            }
        }

        Matcher time = TIME.matcher(text);
        while (time.find()) {
            if (!evidence.contains(time.group())) {
                return Optional.of("time_not_in_data");
            }
        }

        Set<String> known = numbers(evidence);
        Matcher number = NUMBER.matcher(LIST_MARKER.matcher(text).replaceAll(" "));
        while (number.find()) {
            if (!known.contains(stripZeros(number.group()))) {
                return Optional.of("number_not_in_data");
            }
        }
        return Optional.empty();
    }

    private static String evidence(ToolTrace trace, String question) {
        StringBuilder sb = new StringBuilder(question == null ? "" : question);
        for (ToolTrace.Call call : trace.calls()) {
            sb.append('\n').append(call.answer().summary());
            if (call.answer().data() != null) {
                sb.append('\n').append(call.answer().data().toString());
            }
            call.answer().sources().forEach(s -> sb.append('\n').append(s.title()).append(' ').append(s.url()));
        }
        return sb.toString();
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
        String s = digits.replaceFirst("^0+(?=\\d)", "");
        return s;
    }

    private static String trimPunctuation(String link) {
        return link.replaceAll("[.,;:!?]+$", "");
    }

    static boolean isTpu(String link) {
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
