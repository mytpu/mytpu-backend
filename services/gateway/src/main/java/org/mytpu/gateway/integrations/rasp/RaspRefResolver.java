package org.mytpu.gateway.integrations.rasp;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.tool.api.ErrorCode;
import org.mytpu.tool.api.ToolException;

/**
 * Хэш календаря → объект сайта и текущая учебная неделя: {@code /gruppa_40909/2026/8/view.html} — группа 40909,
 * учебный год с 2026, неделя 8 (сайт сам перенаправляет на текущую). Кэш на сутки.
 */
public class RaspRefResolver {
    private static final Pattern PATH = Pattern.compile("/([a-z]+)_(\\d+)/(\\d{4})/(\\d+)/view\\.html");

    /** @param type {@code gruppa}, {@code sg}, {@code pomeschenie}, {@code user} */
    public record CalendarRef(String type, long id, int year, int currentWeek) {
        String weekPath(int week) {
            return "/" + type + "_" + id + "/" + year + "/" + week + "/view.html";
        }
    }

    private final RaspClient client;
    private final Messages messages;
    private final Cache<String, CalendarRef> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofDays(1))
            .maximumSize(5_000)
            .build();

    public RaspRefResolver(RaspClient client, Messages messages) {
        this.client = client;
        this.messages = messages;
    }

    public CalendarRef resolve(String hash) {
        return cache.get(hash, this::load);
    }

    private CalendarRef load(String hash) {
        URI location = client.calendarLocation(hash);
        Matcher m = PATH.matcher(location.getPath());
        if (!m.find()) {
            throw new ToolException(ErrorCode.UPSTREAM_ERROR, messages.text("error.upstream_unavailable"));
        }
        return new CalendarRef(m.group(1), Long.parseLong(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
    }
}
