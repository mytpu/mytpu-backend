package org.mytpu.ai.services;
import org.mytpu.ai.dto.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import javax.print.attribute.standard.Severity;

@Service
public class ChatService {
    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder chatClientBuilder){
        this.chatClient = chatClientBuilder.build();
    }

    public ChatResponse chat(ChatRequest chatRequest) {
        String userMessage = chatRequest.message();
        String answerFromAI = chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
        return new ChatResponse(answerFromAI);
    }

    public Flux<ServerSentEvent<?>> streamChat(ChatRequest chatRequest) {
        ServerSentEvent<MetaEvent> metaEvent = ServerSentEvent.<MetaEvent>builder()
                .event("meta")
                .data(new MetaEvent("ЗАГЛУШКА", "ЗАГЛУШКА", "ЗАГЛУШКА"))
                .build();

        ServerSentEvent<StatusEvent> initialStatusEvent = ServerSentEvent.<StatusEvent>builder()
                .event("status")
                .data(new StatusEvent("thinking", "Думаю..."))
                .build();

       Flux<ServerSentEvent<?>> initialFlux = Flux.just(metaEvent, initialStatusEvent);

       return initialFlux;
    }
}
