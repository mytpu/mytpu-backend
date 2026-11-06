package org.mytpu.assistant.normalize;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Нормализация запроса пользователя: исправление раскладки клавиатуры
 * и выделение сущностей (группа, аудитория, дата). Тонкая Spring-обёртка над чистой логикой.
 */
@Component
public class QueryNormalizer {

    private final Clock clock;

    public QueryNormalizer(Clock clock) {
        this.clock = clock;
    }

    public NormalizedQuery normalize(String rawText) {
        String input = rawText == null ? "" : rawText.trim();
        String fixed = KeyboardLayout.fix(input).trim();

        Optional<String> group = GroupParser.parse(fixed);
        Optional<Room> room = RoomParser.parse(fixed);
        Optional<DateQuery> date = RussianDateParser.parse(fixed, clock);

        return new NormalizedQuery(fixed, group, room, date);
    }
}
