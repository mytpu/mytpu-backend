package org.mytpu.ai.services;
import org.mytpu.ai.dto.ChatRequest;
import org.mytpu.ai.dto.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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

    public Flux<String> streamChat(ChatRequest chatRequest) {
        String userMessage = chatRequest.message();
        Flux<String> answerFromAI = chatClient.prompt()
                .user(userMessage)
                .stream()
                .content();
        return answerFromAI;
    }
}
