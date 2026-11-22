package org.mytpu.gateway.integrations.rasp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mytpu.gateway.runtime.CallRequest;
import org.mytpu.gateway.runtime.CallerPolicy;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.gateway.runtime.check.AccessCheck;
import org.mytpu.tool.api.CallContext;
import org.mytpu.tool.api.Channel;
import org.mytpu.tool.api.ToolResult;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;

class DemoCalendarTest {
    private static final String GROUP = "ДЕМО-01";
    private static final LocalDate MONDAY = LocalDate.parse("2026-11-30");

    @Test
    void tomskWeekCrossesMonthAndKeepsEmptyDaysAndSortedLessons() throws Exception {
        var calendar = at("2026-11-29T17:00:00Z");
        var week = calendar.read(GROUP, MONDAY.plusDays(2), true).orElseThrow();
        assertEquals("2026-11-30", week.weekStart());
        assertEquals("2026-11-23", week.minDate());
        assertEquals("2026-12-13", week.maxDate());
        assertEquals(7, week.days().size());
        assertEquals("2026-12-06", week.days().getLast().date());
        assertEquals("08:30", week.days().getFirst().lessons().getFirst().start());
        assertTrue(week.days().get(1).lessons().isEmpty());
        var day = calendar.read(GROUP, MONDAY.plusDays(1), false).orElseThrow();
        assertEquals(java.util.List.of(week.days().get(1)), day.days());
        assertTrue(calendar.read("8К31", MONDAY, false).isEmpty());
        assertTrue(calendar.read(GROUP, LocalDate.parse("2026-12-14"), true).isEmpty());
        assertEquals("2026-11-16", at("2026-11-29T16:59:59Z").read(GROUP, MONDAY, true).orElseThrow().minDate());
    }

    @Test
    void demoToolIsForServiceChannel() throws Exception {
        var messages = new Messages(new StaticMessageSource());
        var tool = new DemoCalendarTool("rasp_day", false, at("2026-11-29T17:00:00Z"), messages);
        var registered = new RegisteredTool(null, tool);
        var check = new AccessCheck(messages);
        var input = new DemoCalendarTool.Input(GROUP, MONDAY.toString());
        assertInstanceOf(ToolResult.Ok.class, tool.call(context(Channel.CHAT), input));
        assertTrue(check.visible(request(Channel.CHAT), registered));
        assertFalse(check.visible(request(Channel.MCP), registered));
    }

    @Test
    void onlyDemoProfileRegistersCalendarTools() {
        assertEquals(java.util.List.of("rasp_find"), toolNames("default"));
        assertEquals(java.util.List.of("rasp_find", "rasp_day", "rasp_week"), toolNames("schedule-demo"));
    }

    private static java.util.List<String> toolNames(String profile) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(JsonMapper.class, () -> new JsonMapper());
            context.registerBean(Messages.class, () -> new Messages(new StaticMessageSource()));
            context.registerBean(RaspProperties.class, () -> new RaspProperties(null, null, null, null, null, null, null));
            context.register(DemoCalendar.class, RaspIntegration.class);
            context.refresh();
            return context.getBean(RaspIntegration.class).tools().stream().map(tool -> tool.descriptor().name()).toList();
        }
    }

    private static CallRequest request(Channel channel) {
        return new CallRequest(context(channel), CallerPolicy.unrestricted(), "rasp_day", null);
    }

    private static DemoCalendar at(String instant) throws Exception {
        return new DemoCalendar(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), new JsonMapper());
    }

    private static CallContext context(Channel channel) {
        return new CallContext(null, Set.of("chat.service"), "demo", channel, Set.of("rasp.read"), "demo");
    }
}
