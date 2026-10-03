package org.mytpu.ai.services;
import org.apache.catalina.Server;
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
        ServerSentEvent<MetaEvent> metaEvent = createSseEvent(
                "meta",
                new MetaEvent("ЗАГЛУШКА", "ЗАГЛУШКА", "ЗАГЛУШКА")
        );

        ServerSentEvent<StatusEvent> AIisThinkingEvent =  createSseEvent(
                "status",
                new StatusEvent("thinking", "Думаю")
        );

        ServerSentEvent<StatusEvent> AIisWritingEvent = createSseEvent(
                "status",
                new StatusEvent("writing", "Пишу ответ")
        );

        Flux<ServerSentEvent<?>> initialFlux = Flux.just(metaEvent, AIisThinkingEvent, AIisWritingEvent);

        Flux<ServerSentEvent<?>> AIGeneratedChunks = chatClient.prompt()
                .user(chatRequest.message())
                .stream()
                .content()
                .map(chunk -> createSseEvent("delta", new DeltaEvent(chunk)));

        Flux<ServerSentEvent<?>> doneEvent = Flux.just(createSseEvent(
                "done",
                new DoneEvent("stop")
        ));

        return Flux.concat(initialFlux, AIGeneratedChunks, doneEvent);

    }

    private <T> ServerSentEvent<T> createSseEvent(String event, T data){
        return ServerSentEvent.<T>builder()
                .event(event)
                .data(data)
                .build();
    }
}
