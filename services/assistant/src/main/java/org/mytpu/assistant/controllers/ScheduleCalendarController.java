package org.mytpu.assistant.controllers;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.assistant.errors.ChatErrorHandler;
import org.mytpu.assistant.errors.ChatException;
import org.mytpu.assistant.limits.ChatLimits;
import org.mytpu.assistant.tools.GatewayToolsClient;
import org.mytpu.assistant.tools.ToolUnavailable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/ai/schedule/calendar")
class ScheduleCalendarController {
    private static final String DEMO_GROUP = "ДЕМО-01";
    private static final ZoneId TOMSK = ZoneId.of("Asia/Tomsk");

    private final ChatSession session;
    private final ChatLimits limits;
    private final GatewayToolsClient gateway;
    private final ChatErrorHandler errors;
    private final Clock clock;
    private final boolean enabled;

    ScheduleCalendarController(ChatSession session, @Qualifier("scheduleLimits") ChatLimits limits,
                               GatewayToolsClient gateway, ChatErrorHandler errors, Clock clock, Environment environment) {
        this.session = session;
        this.limits = limits;
        this.gateway = gateway;
        this.errors = errors;
        this.clock = clock.withZone(TOMSK);
        this.enabled = environment.matchesProfiles("schedule-demo");
    }

    @GetMapping
    ResponseEntity<Map<String, String>> availability(HttpServletRequest request) {
        if (!enabled) throw notFound();
        ChatLimits.Permit permit = admit(request);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("demoGroup", DEMO_GROUP));
        } finally {
            permit.release();
        }
    }

    @PostMapping
    ResponseEntity<CalendarPayload> read(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        if (!enabled) throw notFound();
        ChatLimits.Permit permit = admit(request);
        try {
            String group = requiredText(body, "group");
            String view = requiredText(body, "view");
            boolean weekly = switch (view) {
                case "day" -> false;
                case "week" -> true;
                default -> throw invalid();
            };
            LocalDate selected = body.has("date") ? date(requiredText(body, "date")) : LocalDate.now(clock);
            if (!DEMO_GROUP.equals(group)) throw notFound();

            String tool = weekly ? "rasp_week" : "rasp_day";
            var answer = gateway.call(tool, Map.of("group", group, "date", selected.toString()));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(CalendarPayload.read(answer.data(), group, selected, weekly));
        } catch (ToolUnavailable error) {
            throw switch (error.code()) {
                case "not_found" -> notFound();
                case "upstream_timeout" -> new ChatException(HttpStatus.GATEWAY_TIMEOUT, "schedule_timeout");
                case "rate_limited" -> new ChatException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
                default -> CalendarPayload.unavailable();
            };
        } finally {
            permit.release();
        }
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<Map<String, String>> unreadable(HttpServletRequest request) {
        if (!enabled) return errors.chat(notFound());
        try {
            admit(request).release();
            return errors.chat(invalid());
        } catch (ChatException error) {
            return errors.chat(error);
        }
    }

    private ChatLimits.Permit admit(HttpServletRequest request) {
        ChatLimits.Permit permit = limits.reserve();
        try {
            session.requireOwner(request);
            limits.checkRate(request);
            return permit;
        } catch (RuntimeException error) {
            permit.release();
            throw error;
        }
    }

    private static String requiredText(JsonNode body, String key) {
        JsonNode value = body == null ? null : body.path(key);
        if (value == null || !value.isString() || value.asText().isBlank() || value.asText().length() > 100) {
            throw invalid();
        }
        return value.asText();
    }

    private static LocalDate date(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw invalid();
        }
    }

    private static ChatException invalid() {
        return new ChatException(HttpStatus.BAD_REQUEST, "invalid_calendar_request");
    }

    private static ChatException notFound() {
        return new ChatException(HttpStatus.NOT_FOUND, "calendar_not_found");
    }
}
