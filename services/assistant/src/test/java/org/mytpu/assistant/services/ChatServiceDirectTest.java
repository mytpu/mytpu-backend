package org.mytpu.assistant.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mytpu.assistant.conversation.ConversationStore;
import org.mytpu.assistant.conversation.NoOpHistoryRepository;
import org.mytpu.assistant.dto.*;
import org.mytpu.assistant.llm.ModelPath;
import org.mytpu.assistant.errors.ChatErrorHandler;
import org.mytpu.assistant.normalize.QueryNormalizer;
import org.mytpu.assistant.router.IntentRouter;
import org.mytpu.assistant.tools.DirectAnswerer;
import org.mytpu.assistant.tools.GatewayToolsClient;
import org.mytpu.assistant.tools.ToolAnswer;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.codec.ServerSentEvent;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class ChatServiceDirectTest {

    private final AtomicInteger mlCallCount = new AtomicInteger(0);
    private ChatService chatService;
    private GatewayToolsClient gatewayToolsClient;
    private ModelPath modelPath;

    @BeforeEach
    void setUp() {
        mlCallCount.set(0);
        modelPath = Mockito.mock(ModelPath.class);
        Mockito.lenient().when(modelPath.answer(any(), any(), any(), any())).thenAnswer(inv -> {
            mlCallCount.incrementAndGet();
            throw new AssertionError("ml-server не должен вызываться на прямом пути!");
        });

        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");

        ChatErrorHandler errorHandler = new ChatErrorHandler(messages);
        StreamErrors streamErrors = new StreamErrors(errorHandler);

        Clock clock = Clock.fixed(Instant.parse("2026-11-01T05:00:00Z"), ZoneId.of("Asia/Tomsk"));
        QueryNormalizer normalizer = new QueryNormalizer(clock);
        IntentRouter router = new IntentRouter();
        ConversationStore store = new ConversationStore();

        gatewayToolsClient = Mockito.mock(GatewayToolsClient.class);
        DirectAnswerer directAnswerer = new DirectAnswerer(gatewayToolsClient, messages);
        NoOpHistoryRepository historyRepository = new NoOpHistoryRepository();

        chatService = new ChatService(streamErrors, normalizer, router, store, directAnswerer, modelPath, historyRepository);
    }

    @Test
    @DisplayName("Прямой путь chat() для приветствия не вызывает ml-server")
    void shouldNotCallMlServerOnDirectGreeting() {
        ChatResponse response = chatService.chat(new ChatRequest("привет"), () -> {});

        assertNotNull(response.message());
        assertTrue(response.message().contains("цифровой помощник"));
        assertEquals(0, mlCallCount.get(), "Счётчик вызовов модели должен остаться 0");
    }

    @Test
    @DisplayName("Прямой путь streamChat() для поиска аудитории: правильный порядок событий SSE")
    void shouldEmitOrderedEventsOnDirectToolCall() {
        ToolAnswer answer = new ToolAnswer(
                "10 корпус — пр. Ленина, 2",
                JsonNodeFactory.instance.objectNode().put("total", 1),
                null,
                true,
                List.of(new Source("10 корпус", "https://rasp.tpu.ru/kalendar.html?hash=123"))
        );
        when(gatewayToolsClient.call(eq("rasp_find"), any())).thenReturn(answer);

        List<ServerSentEvent<?>> events = chatService.streamChat(new ChatRequest("где 10 корпус"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertEquals(0, mlCallCount.get(), "Счётчик вызовов модели должен остаться 0");

        List<String> eventTypes = events.stream().map(ServerSentEvent::event).toList();
        assertEquals(List.of("meta", "status", "card", "sources", "delta", "done"), eventTypes);

        // Проверяем метаданные и card
        MetaEvent meta = (MetaEvent) events.get(0).data();
        assertNotNull(meta.conversation_id());
        CardEvent card = (CardEvent) events.get(2).data();
        assertEquals("rasp_find", card.tool());
        assertEquals("10 корпус — пр. Ленина, 2", card.summary());

        DoneEvent done = (DoneEvent) events.get(5).data();
        assertEquals("stop", done.finish());
    }

    @Test
    @DisplayName("Прямой путь для расписания группы не вызывает ml-server")
    void shouldAnswerScheduleDirectly() {
        ToolAnswer answer = new ToolAnswer(
                "Группа 8К31: расписание занятий",
                JsonNodeFactory.instance.objectNode(),
                null,
                true,
                List.of(new Source("8К31", "https://rasp.tpu.ru/kalendar.html?hash=8k31"))
        );
        when(gatewayToolsClient.call(eq("rasp_find"), any())).thenReturn(answer);

        ChatResponse response = chatService.chat(new ChatRequest("расписание 8к31"), () -> {});

        assertTrue(response.message().contains("8К31"));
        assertEquals(0, mlCallCount.get(), "ml-server не должен вызываться");
    }
}
