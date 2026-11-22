package org.mytpu.assistant.controllers;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.assistant.dto.ChatRequest;
import org.mytpu.assistant.dto.ChatResponse;
import org.mytpu.assistant.limits.ChatLimits;
import org.mytpu.assistant.services.ChatService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** Чат: проверка запроса → место в лимитах → модель; место освобождается, когда вызов модели закончился или поток закрыт. */
@RestController
@RequestMapping("/api/ai")
public class ChatController {
    private final ChatService chatService;
    private final ChatRequestValidator validator;
    private final ChatLimits limits;
    private final ChatSession session;

    ChatController(ChatService chatService, ChatRequestValidator validator, ChatLimits limits, ChatSession session) {
        this.chatService = chatService;
        this.validator = validator;
        this.limits = limits;
        this.session = session;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody(required = false) ChatRequest chatRequest, HttpServletRequest request) {
        validator.validate(chatRequest);
        ChatLimits.Permit permit = limits.acquire(request);
        String owner;
        try {
            owner = session.owner(request);
        } catch (RuntimeException e) {
            permit.release();
            throw e;
        }
        return chatService.chat(chatRequest, owner, permit::release);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> streamChat(@RequestBody(required = false) ChatRequest chatRequest, HttpServletRequest request) {
        validator.validate(chatRequest);
        ChatLimits.Permit permit = limits.acquire(request);
        try {
            return chatService.streamChat(chatRequest, session.owner(request)).doFinally(signal -> permit.release());
        } catch (RuntimeException e) {
            permit.release();
            throw e;
        }
    }
}
