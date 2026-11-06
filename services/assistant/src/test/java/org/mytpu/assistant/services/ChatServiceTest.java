package org.mytpu.assistant.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mytpu.assistant.conversation.ConversationStore;
import org.mytpu.assistant.conversation.NoOpHistoryRepository;
import org.mytpu.assistant.dto.CardEvent;
import org.mytpu.assistant.dto.ChatRequest;
import org.mytpu.assistant.dto.ChatResponse;
import org.mytpu.assistant.dto.MetaEvent;
import org.mytpu.assistant.dto.Source;
import org.mytpu.assistant.errors.ChatErrorHandler;
import org.mytpu.assistant.llm.ModelPath;
import org.mytpu.assistant.normalize.QueryNormalizer;
import org.mytpu.assistant.router.Intent;
import org.mytpu.assistant.router.IntentRouter;
import org.mytpu.assistant.router.Route;
import org.mytpu.assistant.tools.DirectAnswerer;
import org.mytpu.assistant.tools.DirectResult;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.codec.ServerSentEvent;
import tools.jackson.databind.node.JsonNodeFactory;

/** Выбор пути: прямой путь без модели, остальное — путь с моделью; слоты переходят между вопросами (spec 5, шаги 6–9). */
class ChatServiceTest {

    private DirectAnswerer directAnswerer;
    private ModelPath modelPath;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        StreamErrors errors = new StreamErrors(new ChatErrorHandler(new StaticMessageSource()));
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T05:00:00Z"), ZoneId.of("Asia/Tomsk"));
        directAnswerer = mock(DirectAnswerer.class);
        modelPath = mock(ModelPath.class);
        chatService = new ChatService(errors, new QueryNormalizer(clock), new IntentRouter(), new ConversationStore(),
                directAnswerer, modelPath, new NoOpHistoryRepository());
    }

    @Test
    @DisplayName("Прямой путь (chat) не вызывает модель")
    void directChatDoesNotCallModel() {
        when(directAnswerer.answer(any())).thenReturn(DirectResult.textOnly("10 корпус — пр. Ленина, 2"));

        ChatResponse response = chatService.chat(new ChatRequest("где 10 корпус"), () -> { });

        assertEquals("10 корпус — пр. Ленина, 2", response.message());
        verify(modelPath, never()).answer(any(), anyList(), anyMap(), any());
    }

    @Test
    @DisplayName("Прямой поток: meta, status, card, sources, delta, done; conversation_id клиента сохраняется")
    void directStreamEvents() {
        CardEvent card = new CardEvent("rasp_find", "10 корпус", JsonNodeFactory.instance.objectNode(), null);
        List<Source> sources = List.of(new Source("10 корпус", "https://rasp.tpu.ru/kalendar.html?hash=abc"));
        when(directAnswerer.answer(any())).thenReturn(new DirectResult("10 корпус — пр. Ленина, 2", Optional.of(card), sources));
        String conversationId = UUID.randomUUID().toString();

        List<ServerSentEvent<?>> events = chatService.streamChat(new ChatRequest("где 10 корпус", conversationId))
                .collectList().block();

        assertNotNull(events);
        assertEquals(List.of("meta", "status", "card", "sources", "delta", "done"),
                events.stream().map(ServerSentEvent::event).toList());
        assertEquals(conversationId, ((MetaEvent) events.getFirst().data()).conversation_id());
        verify(modelPath, never()).answer(any(), anyList(), anyMap(), any());
    }

    @Test
    @DisplayName("Вопрос по документам идёт путём с моделью")
    void docsQuestionGoesToModelPath() {
        when(modelPath.answer(any(), anyList(), anyMap(), any()))
                .thenReturn(new ModelPath.Outcome(DirectResult.textOnly("Ответ по документу"), "llm"));

        ChatResponse response = chatService.chat(new ChatRequest("как получить справку об обучении"), () -> { });

        assertEquals("Ответ по документу", response.message());
        verify(modelPath, times(1)).answer(any(), anyList(), anyMap(),
                org.mockito.ArgumentMatchers.<Route>argThat(r -> r.intent() == Intent.DOCS_HOWTO));
    }

    @Test
    @DisplayName("«а завтра?» после «расписание 8К31» — снова прямой путь с той же группой")
    void slotsCarryOverToFollowUp() {
        when(directAnswerer.answer(any())).thenReturn(DirectResult.textOnly("Расписание"));
        String conversationId = UUID.randomUUID().toString();

        chatService.chat(new ChatRequest("расписание 8К31", conversationId), () -> { });
        chatService.chat(new ChatRequest("а завтра?", conversationId), () -> { });

        verify(directAnswerer, times(1)).answer(org.mockito.ArgumentMatchers.<Route>argThat(
                r -> "8К31".equals(r.slots().get("group")) && "2026-11-02".equals(r.slots().get("date"))));
        verify(modelPath, never()).answer(any(), anyList(), anyMap(), any());
    }
}
