package org.mytpu.ai.services;
import org.mytpu.ai.dto.ChatRequest;
import org.mytpu.ai.dto.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder chatClientBuilder){
        this.chatClient = chatClientBuilder.build();
    }

    public ChatResponse chat(ChatRequest chatRequest) {
        String userMessage = chatRequest.message();

        String answer = chatClient.prompt()
                .system("Ты гений по имени Андрей")
                .user(userMessage)
                .call()
                .content();
        return new ChatResponse(answer);
    }
}
