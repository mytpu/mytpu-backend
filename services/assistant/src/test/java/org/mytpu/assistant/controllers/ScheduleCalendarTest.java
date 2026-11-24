package org.mytpu.assistant.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mytpu.assistant.errors.ChatErrorHandler;
import org.mytpu.assistant.errors.ChatException;
import org.mytpu.assistant.limits.ChatLimits;
import org.mytpu.assistant.tools.GatewayToolsClient;
import org.mytpu.assistant.tools.ToolAnswer;
import org.mytpu.assistant.tools.ToolUnavailable;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class ScheduleCalendarTest {
    private static final String PATH = "/api/ai/schedule/calendar";
    private static final String QUERY = "{\"group\":\"ДЕМО-01\",\"view\":\"day\"}";
    private final GatewayToolsClient gateway = mock(GatewayToolsClient.class);
    private final ChatSession session = mock(ChatSession.class);
    private final ChatLimits limits = mock(ChatLimits.class);

    @Test
    void defaultProfileHidesCalendarBeforeCallingGateway() throws Exception {
        MockMvc mvc = setup(false);
        mvc.perform(get(PATH)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("calendar_not_found"));
        mvc.perform(request(QUERY)).andExpect(status().isNotFound());
        verifyNoInteractions(gateway);
    }

    @Test
    void forcesSessionBeforeGateway() throws Exception {
        when(session.requireOwner(any())).thenThrow(new ChatException(HttpStatus.UNAUTHORIZED, "session_required"));
        MockMvc mvc = setup(true);
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(request(QUERY)).andExpect(status().isUnauthorized());
        mvc.perform(request("{")).andExpect(status().isUnauthorized());
        verify(limits, never()).checkRate(any());
        verifyNoInteractions(gateway);
    }

    @Test
    void validatesArgumentsAndRejectsRealGroup() throws Exception {
        MockMvc mvc = setup(true);
        mvc.perform(request(QUERY.replace("ДЕМО-01", "8К31"))).andExpect(status().isNotFound());
        mvc.perform(request(QUERY.replace("day", "other"))).andExpect(status().isBadRequest());
        mvc.perform(request(QUERY.replace("}", ",\"date\":\"2026-02-30\"}"))).andExpect(status().isBadRequest());
        mvc.perform(request("{")).andExpect(status().isBadRequest());
        verifyNoInteractions(gateway);
    }

    @Test
    void distinguishesEmptyDayFromMalformedDataAndProviderErrors() throws Exception {
        var input = Map.<String, Object>of("group", "ДЕМО-01", "date", "2026-11-30");
        var json = new JsonMapper();
        var empty = new ToolAnswer("", json.readTree("""
                {"group":"ДЕМО-01","source":"demo","timeZone":"Asia/Tomsk","selectedDate":"2026-11-30",
                "weekStart":"2026-11-30","minDate":"2026-11-23","maxDate":"2026-12-13",
                "days":[{"date":"2026-11-30","lessons":[]}]}
                """), null, true, null);
        var broken = new ToolAnswer("", json.createObjectNode(), null, true, null);
        when(gateway.call("rasp_day", input)).thenReturn(empty, broken)
                .thenThrow(new ToolUnavailable("upstream_timeout"), new ToolUnavailable("not_found"));
        MockMvc mvc = setup(true);
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.demoGroup").value("ДЕМО-01"));
        mvc.perform(request(QUERY)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.selectedDate").value("2026-11-30")).andExpect(jsonPath("$.days[0].lessons").isEmpty());
        mvc.perform(request(QUERY)).andExpect(status().isServiceUnavailable());
        mvc.perform(request(QUERY)).andExpect(status().isGatewayTimeout());
        mvc.perform(request(QUERY)).andExpect(status().isNotFound());
    }

    private MockMvc setup(boolean demo) {
        when(limits.reserve()).thenReturn(mock(ChatLimits.Permit.class));
        var errors = new ChatErrorHandler(new StaticMessageSource());
        var env = new MockEnvironment();
        if (demo) env.setActiveProfiles("schedule-demo");
        Clock clock = Clock.fixed(Instant.parse("2026-11-29T17:00:00Z"), ZoneOffset.UTC);
        var controller = new ScheduleCalendarController(session, limits, gateway, errors, clock, env);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(errors).build();
    }

    private static MockHttpServletRequestBuilder request(String body) {
        return post(PATH).contentType("application/json").content(body);
    }
}
