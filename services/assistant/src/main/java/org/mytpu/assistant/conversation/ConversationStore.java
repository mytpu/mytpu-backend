package org.mytpu.assistant.conversation;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Component;

/**
 * Хранилище диалогов и слотов в оперативной памяти (specs/assistant/spec.md, шаг 7).
 * Хранит до 10 000 диалогов с временем жизни 30 минут после последнего обращения.
 */
@Component
public class ConversationStore {

    private final Cache<String, ConversationState> cache;

    public ConversationStore() {
        this(Caffeine.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build());
    }

    public ConversationStore(Cache<String, ConversationState> cache) {
        this.cache = cache;
    }

    /** Конструктор для тестов с управляемым таймером и сроком жизни. */
    public static ConversationStore withCustomExpiry(Duration expireAfterAccess, Ticker ticker) {
        Cache<String, ConversationState> customCache = Caffeine.newBuilder()
                .expireAfterAccess(expireAfterAccess)
                .ticker(ticker)
                .maximumSize(10_000)
                .build();
        return new ConversationStore(customCache);
    }

    /**
     * Возвращает переданный клиентом conversationId, если он корректен, либо генерирует новый UUID.
     */
    public String resolveConversationId(String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            try {
                return UUID.fromString(candidate.trim()).toString();
            } catch (IllegalArgumentException ignored) {
                // Невалидный UUID клиента -> создаём надёжный новый
            }
        }
        return UUID.randomUUID().toString();
    }

    public ConversationState get(String conversationId) {
        if (conversationId == null) {
            return ConversationState.empty();
        }
        ConversationState state = cache.getIfPresent(conversationId);
        return state != null ? state : ConversationState.empty();
    }

    public void put(String conversationId, ConversationState state) {
        if (conversationId != null && state != null) {
            cache.put(conversationId, state);
        }
    }

    public long size() {
        return cache.estimatedSize();
    }

    public void cleanUp() {
        cache.cleanUp();
    }
}
