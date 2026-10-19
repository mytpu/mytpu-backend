package org.mytpu.gateway.integrations.rasp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.Choice;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.ToolException;

/**
 * Группа из человеческого ввода: «8к31», «8 К 31», «8K31» (латиница) → «8К31». Ищет на сайте и оставляет
 * только настоящие группы (не потоки подгрупп). Несколько совпадений — выбор для человека, ни одного — инструкция.
 */
public class GroupResolver {
    private static final Map<Character, Character> LATIN_TO_CYRILLIC = Map.ofEntries(
            Map.entry('A', 'А'), Map.entry('B', 'В'), Map.entry('C', 'С'), Map.entry('E', 'Е'), Map.entry('H', 'Н'),
            Map.entry('K', 'К'), Map.entry('M', 'М'), Map.entry('O', 'О'), Map.entry('P', 'Р'), Map.entry('T', 'Т'),
            Map.entry('X', 'Х'), Map.entry('Y', 'У'));

    /** Найденная группа или вопрос с вариантами. */
    public sealed interface Match {
        record Found(RaspEntry group) implements Match {
        }

        record Ambiguous(String question, List<Choice> options) implements Match {
        }
    }

    private final RaspClient client;
    private final Messages messages;
    private final Cache<String, List<RaspEntry>> searches;

    public GroupResolver(RaspClient client, Messages messages, RaspProperties properties) {
        this.client = client;
        this.messages = messages;
        this.searches = Caffeine.newBuilder().expireAfterWrite(properties.searchCacheTtl()).maximumSize(2_000).build();
    }

    public Match resolve(String input) {
        String text = input == null ? "" : input.trim();
        // Повторный вызов после выбора: «8К31 (ИШИТР - План)» — имя и пояснение
        String hint = null;
        int bracket = text.indexOf('(');
        if (bracket > 0 && text.endsWith(")")) {
            hint = text.substring(bracket + 1, text.length() - 1).trim();
            text = text.substring(0, bracket).trim();
        }
        String name = normalize(text);
        if (name.isEmpty()) {
            throw new ToolException(ErrorCode.INVALID_ARGUMENTS, messages.text("rasp.group_missing"));
        }
        String wanted = hint;
        List<RaspEntry> groups = searches.get(name, client::search).stream()
                .filter(e -> e.kind() == RaspEntry.Kind.GROUP && e.name().equalsIgnoreCase(name))
                .filter(e -> wanted == null || wanted.equalsIgnoreCase(e.hint()))
                .toList();
        if (groups.isEmpty()) {
            throw new ToolException(ErrorCode.NOT_FOUND, messages.text("rasp.group_not_found", name));
        }
        if (groups.size() == 1) {
            return new Match.Found(groups.getFirst());
        }
        List<Choice> options = groups.stream().limit(5).map(g -> new Choice(g.label(), g.label())).toList();
        return new Match.Ambiguous(messages.text("rasp.group_choice", name), options);
    }

    public Optional<List<RaspEntry>> cachedSearch(String query) {
        return Optional.ofNullable(searches.getIfPresent(query));
    }

    static String normalize(String text) {
        StringBuilder result = new StringBuilder();
        for (char c : text.toUpperCase(Locale.ROOT).replace('Ё', 'Е').toCharArray()) {
            if (Character.isWhitespace(c) || c == '-') {
                continue;
            }
            result.append(LATIN_TO_CYRILLIC.getOrDefault(c, c));
        }
        return result.toString();
    }
}
