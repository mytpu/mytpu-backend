package org.mytpu.gateway.connectors;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Фраза {@code summary} коннектора: {@code {поле}} заменяется значением поля (объекта или первого элемента списка),
 * {@code {count}} и {@code {total}} — числом показанных и всех элементов. Значение — не длиннее 200 символов,
 * без управляющих символов; неизвестных подстановок нет — их отвергает {@link ConnectorValidator}.
 */
public final class SummaryTemplate {
    public static final Set<String> BUILT_IN = Set.of("count", "total");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]{0,31})}");
    private static final int MAX_VALUE = 200;

    private SummaryTemplate() {
    }

    public static Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template == null ? "" : template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    public static String render(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.getOrDefault(m.group(1), "—");
            value = value == null || "null".equals(value) ? "—" : value.replaceAll("\\p{Cntrl}", " ");
            if (value.length() > MAX_VALUE) {
                value = value.substring(0, MAX_VALUE) + "…";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
