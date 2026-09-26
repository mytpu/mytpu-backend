package org.mytpu.ai.controllers;

import org.mytpu.ai.dto.ChatRequest;
import org.mytpu.ai.dto.ChatResponse;
import org.mytpu.ai.services.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chatService;

    @Autowired
    public ChatController(ChatService chatService){
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest chatRequest){
        return chatService.chat(chatRequest);
    }
}
