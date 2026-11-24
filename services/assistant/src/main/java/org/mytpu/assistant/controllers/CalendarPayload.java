package org.mytpu.assistant.controllers;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import org.mytpu.assistant.errors.ChatException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

record CalendarPayload(String group, String source, String timeZone, String selectedDate, String weekStart,
                       String minDate, String maxDate, List<Day> days) {

    static CalendarPayload read(JsonNode answer, String group, LocalDate selected, boolean weekly) {
        if (answer == null || !group.equals(text(answer, "group")) || !"demo".equals(text(answer, "source"))
                || !"Asia/Tomsk".equals(text(answer, "timeZone")) || !selected.toString().equals(text(answer, "selectedDate"))) {
            throw unavailable();
        }
        LocalDate week = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate min = date(answer, "minDate");
        LocalDate max = date(answer, "maxDate");
        JsonNode entries = answer.path("days");
        if (!week.toString().equals(text(answer, "weekStart")) || min.isAfter(week) || max.isBefore(week.plusDays(6))
                || !entries.isArray() || entries.size() != (weekly ? 7 : 1)) {
            throw unavailable();
        }
        List<Day> days = new ArrayList<>(entries.size());
        LocalDate first = weekly ? week : selected;
        for (int i = 0; i < entries.size(); i++) {
            String expected = first.plusDays(i).toString();
            JsonNode entry = entries.get(i);
            JsonNode lessons = entry.path("lessons");
            if (!expected.equals(text(entry, "date")) || !lessons.isArray()) throw unavailable();
            List<Lesson> valid = new ArrayList<>(lessons.size());
            for (JsonNode lesson : lessons) {
                String start = time(lesson, "start");
                String end = time(lesson, "end");
                if (end.compareTo(start) <= 0 || (!valid.isEmpty() && valid.getLast().start().compareTo(start) > 0)) {
                    throw unavailable();
                }
                valid.add(new Lesson(start, end, text(lesson, "subject"), optional(lesson, "kind"),
                        optional(lesson, "teacher"), optional(lesson, "building"), optional(lesson, "room")));
            }
            days.add(new Day(expected, List.copyOf(valid)));
        }
        return new CalendarPayload(group, "demo", "Asia/Tomsk", selected.toString(), week.toString(),
                min.toString(), max.toString(), List.copyOf(days));
    }

    private static LocalDate date(JsonNode answer, String key) {
        try {
            return LocalDate.parse(text(answer, key));
        } catch (DateTimeParseException error) {
            throw unavailable();
        }
    }

    private static String time(JsonNode answer, String key) {
        String value = text(answer, key);
        try {
            if (value.length() == 5 && LocalTime.parse(value).toString().equals(value)) return value;
        } catch (DateTimeParseException error) {
            throw unavailable();
        }
        throw unavailable();
    }

    private static String text(JsonNode answer, String key) {
        String value = optional(answer, key);
        if (value == null || value.isBlank()) throw unavailable();
        return value;
    }

    private static String optional(JsonNode answer, String key) {
        JsonNode value = answer.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) throw unavailable();
        return value.asText();
    }

    static ChatException unavailable() {
        return new ChatException(HttpStatus.SERVICE_UNAVAILABLE, "schedule_unavailable");
    }

    record Day(String date, List<Lesson> lessons) { }

    record Lesson(String start, String end, String subject, String kind, String teacher, String building, String room) { }
}
