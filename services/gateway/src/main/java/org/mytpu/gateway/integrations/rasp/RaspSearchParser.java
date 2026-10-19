package org.mytpu.gateway.integrations.rasp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;

/**
 * Ответ поиска rasp.tpu.ru ({@code /select/search/main.html?q=}) → {@link RaspEntry}.
 * Вид объекта угадывается по пояснению сайта; точный вид даёт адрес календаря ({@link RaspRefResolver}).
 */
final class RaspSearchParser {
    private static final Pattern HASH = Pattern.compile("hash=([A-Za-z0-9_-]+)");
    private static final Pattern GROUP_NAME = Pattern.compile("\\d{1,3}[А-ЯA-Z]{1,2}\\d{1,3}[А-ЯA-Z]?");
    private static final Pattern PERSON = Pattern.compile("[А-ЯЁ][а-яё-]+ [А-ЯЁ][а-яё-]+( [А-ЯЁ][а-яё-]+)?");

    private RaspSearchParser() {
    }

    static List<RaspEntry> parse(JsonNode root) {
        JsonNode results = root.path("result");
        List<RaspEntry> entries = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            JsonNode item = results.get(i);
            Matcher hash = HASH.matcher(item.path("url").asString(""));
            if (!hash.find()) {
                continue;
            }
            String name = item.path("text").asString("").trim();
            String hint = Jsoup.parse(item.path("html").asString("")).select(".text-muted").text().trim();
            entries.add(new RaspEntry(name, hint.isEmpty() ? null : hint, kind(name, hint), hash.group(1)));
        }
        return entries;
    }

    private static RaspEntry.Kind kind(String name, String hint) {
        if (hint.startsWith("Подгруппы")) {
            return RaspEntry.Kind.STREAM;
        }
        if (hint.contains("корпус") || hint.contains("Корпус")) {
            return RaspEntry.Kind.ROOM;
        }
        if (PERSON.matcher(name).matches()) {
            return RaspEntry.Kind.TEACHER;
        }
        if (GROUP_NAME.matcher(name).matches()) {
            return RaspEntry.Kind.GROUP;
        }
        return RaspEntry.Kind.OTHER;
    }
}
