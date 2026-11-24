package org.mytpu.assistant.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.assistant.errors.ChatErrorHandler;
import org.mytpu.assistant.errors.ChatException;
import org.mytpu.assistant.limits.ChatLimits;
import org.mytpu.assistant.tools.GatewayToolsClient;
import org.mytpu.assistant.tools.ToolUnavailable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class ScheduleSearchController {
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_ITEMS = 10;

    private final ChatSession session;
    private final ChatLimits limits;
    private final GatewayToolsClient gateway;
    private final ChatErrorHandler errors;

    ScheduleSearchController(ChatSession session, @Qualifier("scheduleLimits") ChatLimits limits,
                             GatewayToolsClient gateway, ChatErrorHandler errors) {
        this.session = session;
        this.limits = limits;
        this.gateway = gateway;
        this.errors = errors;
    }

    @PostMapping("/api/ai/schedule/search")
    ResponseEntity<SearchResult> search(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        JsonNode input = body == null ? null : body.path("query");
        String query = input != null && input.isString() ? trim(input.asText()) : "";
        if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
            throw new ChatException(HttpStatus.BAD_REQUEST, "invalid_query");
        }

        ChatLimits.Permit permit = limits.acquire(request);
        try {
            session.requireOwner(request);
            SearchResult result = parse(gateway.call("rasp_find", Map.of("query", query)).data());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        } catch (ToolUnavailable error) {
            if ("upstream_timeout".equals(error.code())) {
                throw new ChatException(HttpStatus.GATEWAY_TIMEOUT, "schedule_timeout");
            }
            throw unavailable();
        } finally {
            permit.release();
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return errors.chat(new ChatException(HttpStatus.BAD_REQUEST, "invalid_query"));
    }

    private static String trim(String query) {
        int start = 0;
        int end = query.length();
        while (start < end && isSearchSpace(query.charAt(start))) start++;
        while (end > start && isSearchSpace(query.charAt(end - 1))) end--;
        return query.substring(start, end);
    }

    private static boolean isSearchSpace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == '\u0085' || value == '\ufeff';
    }

    private static SearchResult parse(JsonNode data) {
        if (data == null) throw unavailable();
        JsonNode total = data.path("total");
        JsonNode items = data.path("items");
        if (!total.isIntegralNumber() || !total.canConvertToInt() || total.asInt() < 0 || !items.isArray()
                || Math.min(total.asInt(), MAX_ITEMS) != items.size()) {
            throw unavailable();
        }
        List<Item> result = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode hint = item.path("hint");
            if (!hint.isMissingNode() && !hint.isNull() && !hint.isString()) throw unavailable();
            result.add(new Item(text(item, "name"), text(item, "kind"),
                    hint.isString() ? hint.asText() : null, text(item, "url")));
        }
        return new SearchResult(total.asInt(), List.copyOf(result));
    }

    private static String text(JsonNode item, String key) {
        JsonNode field = item.path(key);
        if (!field.isString() || field.asText().isBlank()) throw unavailable();
        return field.asText();
    }

    private static ChatException unavailable() {
        return new ChatException(HttpStatus.SERVICE_UNAVAILABLE, "schedule_unavailable");
    }

    record SearchResult(int total, List<Item> items) { }

    record Item(String name, String kind, String hint, String url) { }
}
