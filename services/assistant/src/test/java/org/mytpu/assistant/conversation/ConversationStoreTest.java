package org.mytpu.assistant.conversation;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConversationStoreTest {

    @Test
    @DisplayName("Хранение и перенос слотов между запросами («а завтра?»)")
    void shouldPersistAndTransferSlots() {
        ConversationStore store = new ConversationStore();
        String convId = UUID.randomUUID().toString();

        ConversationState state1 = store.get(convId)
                .withMessage("user", "расписание 8К31")
                .withSlots(Map.of("group", "8К31", "query", "8К31"));
        store.put(convId, state1);

        // Второй шаг: пользователь спрашивает «а завтра?»
        ConversationState loaded = store.get(convId);
        assertEquals("8К31", loaded.slots().get("group"));

        ConversationState state2 = loaded
                .withMessage("user", "а завтра?")
                .withSlots(Map.of("date", "2026-11-02"));
        store.put(convId, state2);

        ConversationState finalState = store.get(convId);
        assertEquals("8К31", finalState.slots().get("group"), "Группа должна сохраниться");
        assertEquals("2026-11-02", finalState.slots().get("date"), "Дата должна обновиться");
        assertEquals(2, finalState.messages().size());
    }

    @Test
    @DisplayName("Ограничение истории последних 6 сообщений")
    void shouldKeepOnlyLastSixMessages() {
        ConversationState state = ConversationState.empty();
        for (int i = 1; i <= 10; i++) {
            state = state.withMessage("user", "сообщение " + i);
        }

        assertEquals(6, state.messages().size());
        assertEquals("сообщение 5", state.messages().get(0).content());
        assertEquals("сообщение 10", state.messages().get(5).content());
    }

    @Test
    @DisplayName("Срок жизни: 30 минут без обращений")
    void shouldExpireAfterThirtyMinutesOfInactivity() {
        AtomicLong fakeNanos = new AtomicLong(0);
        ConversationStore store = ConversationStore.withCustomExpiry(
                Duration.ofMinutes(30),
                fakeNanos::get
        );

        String convId = UUID.randomUUID().toString();
        store.put(convId, ConversationState.empty().withMessage("user", "привет"));
        assertFalse(store.get(convId).messages().isEmpty());

        // Прошло 29 минут
        fakeNanos.addAndGet(Duration.ofMinutes(29).toNanos());
        store.cleanUp();
        assertFalse(store.get(convId).messages().isEmpty(), "Через 29 минут диалог ещё жив");

        // Прошло ещё 31 минута без обращений
        fakeNanos.addAndGet(Duration.ofMinutes(31).toNanos());
        store.cleanUp();
        assertTrue(store.get(convId).messages().isEmpty(), "Спустя 30+ минут диалог должен истечь");
    }

    @Test
    @DisplayName("Корректное разрешение conversationId клиента")
    void shouldResolveConversationId() {
        ConversationStore store = new ConversationStore();
        String validUuid = UUID.randomUUID().toString();

        assertEquals(validUuid, store.resolveConversationId(validUuid));
        assertNotNull(store.resolveConversationId(null));
        assertNotEquals("invalid-uuid", store.resolveConversationId("invalid-uuid"));
    }

    @Test
    @DisplayName("При выключенной истории NoOpHistoryRepository ничего не бросает и не пишет")
    void shouldNoopWhenHistoryDisabled() {
        HistoryRepository repo = new NoOpHistoryRepository();
        assertDoesNotThrow(() -> repo.save("conv-1", "msg-1", "user", "вопрос"));
    }

    @Test
    @DisplayName("Контекст Spring: при history.enabled=false регистрируется NoOpHistoryRepository")
    void shouldRegisterNoopHistoryRepositoryWhenDisabled() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(NoOpHistoryRepository.class)
                .withPropertyValues("mytpu.assistant.history.enabled=false")
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(HistoryRepository.class);
                    org.assertj.core.api.Assertions.assertThat(context).hasSingleBean(NoOpHistoryRepository.class);
                });
    }
}
