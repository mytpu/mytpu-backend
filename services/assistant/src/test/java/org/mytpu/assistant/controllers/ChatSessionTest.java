package org.mytpu.assistant.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.mytpu.assistant.errors.ChatException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class ChatSessionTest {
    @Test
    void requiredSessionNeverFallsBackToLegacyOwnership() {
        var session = new ChatSession("", new JsonMapper());
        var request = new MockHttpServletRequest();
        assertNull(session.owner(request));

        request.addHeader("X-Assistant-Require-Session", "1");
        var error = assertThrows(ChatException.class, () -> session.owner(request));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
    }
}
