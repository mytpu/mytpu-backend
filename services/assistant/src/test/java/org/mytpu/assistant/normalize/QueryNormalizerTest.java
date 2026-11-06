package org.mytpu.assistant.normalize;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryNormalizerTest {

    private QueryNormalizer normalizer;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-11-01T05:00:00Z"),
                ZoneId.of("Asia/Tomsk")
        );
        normalizer = new QueryNormalizer(fixedClock);
    }

    @Test
    @DisplayName("Исправление раскладки и извлечение группы и даты")
    void shouldFixLayoutAndExtractGroupAndDate() {
        NormalizedQuery query = normalizer.normalize("hfcnbcfybt 8r31 yf pfjnhf");

        assertEquals("расписание 8к31 на завтра", query.text());
        assertTrue(query.group().isPresent());
        assertEquals("8К31", query.group().get());
        assertTrue(query.date().isPresent());
        assertEquals(LocalDate.parse("2026-11-02"), query.date().get().from());
        assertTrue(query.room().isEmpty());
    }

    @Test
    @DisplayName("Исправление раскладки и извлечение аудитории")
    void shouldFixLayoutAndExtractRoom() {
        NormalizedQuery query = normalizer.normalize("ult fel 403,?");

        assertEquals("где ауд 403б?", query.text());
        assertTrue(query.room().isPresent());
        assertNull(query.room().get().building());
        assertEquals("403б", query.room().get().room());
        assertTrue(query.group().isEmpty());
    }

    @Test
    @DisplayName("Комплексный запрос: группа, корпус, аудитория, дата на следующей неделе")
    void shouldExtractAllEntities() {
        NormalizedQuery query = normalizer.normalize("пары 8-к-31 в к.10 ауд 402 на следующей неделе");

        assertEquals("пары 8-к-31 в к.10 ауд 402 на следующей неделе", query.text());
        assertEquals("8К31", query.group().orElse(null));
        assertTrue(query.room().isPresent());
        assertEquals("10", query.room().get().building());
        assertEquals("402", query.room().get().room());
        assertTrue(query.date().isPresent());
        assertEquals(LocalDate.parse("2026-11-02"), query.date().get().from());
        assertEquals(LocalDate.parse("2026-11-08"), query.date().get().to());
    }

    @Test
    @DisplayName("Английский запрос и аббревиатура MCP не трогаются")
    void shouldNotTouchEnglishQueryAndExceptions() {
        NormalizedQuery query = normalizer.normalize("what is MCP schedule?");

        assertEquals("what is MCP schedule?", query.text());
        assertTrue(query.group().isEmpty());
        assertTrue(query.room().isEmpty());
        assertTrue(query.date().isEmpty());
    }

    @Test
    @DisplayName("Обрезка пробелов и пустой запрос")
    void shouldHandleBlankAndNull() {
        NormalizedQuery emptyQuery = normalizer.normalize("");
        assertEquals("", emptyQuery.text());
        assertTrue(emptyQuery.group().isEmpty());

        NormalizedQuery nullQuery = normalizer.normalize(null);
        assertEquals("", nullQuery.text());
        assertTrue(nullQuery.group().isEmpty());

        NormalizedQuery trimmedQuery = normalizer.normalize("   расписание 0В21   ");
        assertEquals("расписание 0В21", trimmedQuery.text());
        assertEquals("0В21", trimmedQuery.group().orElse(null));
    }
}
