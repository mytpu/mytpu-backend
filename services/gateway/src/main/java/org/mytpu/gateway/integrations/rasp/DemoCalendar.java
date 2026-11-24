package org.mytpu.gateway.integrations.rasp;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("schedule-demo")
class DemoCalendar {
    static final String GROUP = "ДЕМО-01";
    private static final ZoneId TOMSK = ZoneId.of("Asia/Tomsk");

    private final Clock clock;
    private final List<Fixture> lessons;

    DemoCalendar(Clock clock, JsonMapper json) throws IOException {
        this.clock = clock.withZone(TOMSK);
        try (var input = new ClassPathResource("rasp/demo-lessons.json").getInputStream()) {
            lessons = Arrays.stream(json.readValue(input, Fixture[].class))
                    .sorted(Comparator.comparing(item -> item.lesson().start())).toList();
        }
    }

    Optional<Answer> read(String group, LocalDate selected, boolean weekly) {
        LocalDate current = monday(LocalDate.now(clock));
        LocalDate start = current.minusWeeks(1);
        LocalDate end = current.plusWeeks(2).minusDays(1);
        if (!GROUP.equals(group) || selected.isBefore(start) || selected.isAfter(end)) {
            return Optional.empty();
        }
        LocalDate week = monday(selected);
        List<Day> days = weekly
                ? IntStream.range(0, 7).mapToObj(offset -> day(week.plusDays(offset))).toList()
                : List.of(day(selected));
        return Optional.of(new Answer(GROUP, "demo", TOMSK.getId(), selected.toString(), week.toString(),
                start.toString(), end.toString(), days));
    }

    private Day day(LocalDate date) {
        List<Lesson> scheduled = lessons.stream().filter(item -> item.day() == date.getDayOfWeek())
                .map(Fixture::lesson).toList();
        return new Day(date.toString(), scheduled);
    }

    private static LocalDate monday(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    record Fixture(DayOfWeek day, Lesson lesson) { }

    record Lesson(String start, String end, String subject, String kind, String teacher, String building, String room) { }

    record Day(String date, List<Lesson> lessons) { }

    record Answer(String group, String source, String timeZone, String selectedDate, String weekStart,
                  String minDate, String maxDate, List<Day> days) { }
}
