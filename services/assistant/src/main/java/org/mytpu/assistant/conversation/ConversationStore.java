package org.mytpu.assistant.conversation;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.mytpu.assistant.errors.ChatException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Хранилище диалогов и слотов в оперативной памяти (specs/assistant/spec.md, шаг 7).
 * Хранит до 10 000 диалогов с временем жизни 30 минут после последнего обращения.
 */
@Component
public class ConversationStore {

    private final Cache<Key, ConversationState> cache;

    public record Key(String owner, String id) {}

    public record Snapshot(Key key, ConversationState state) {}

    public ConversationStore() {
        this(Caffeine.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build());
    }

    private ConversationStore(Cache<Key, ConversationState> cache) {
        this.cache = cache;
    }

    /** Конструктор для тестов с управляемым таймером и сроком жизни. */
    public static ConversationStore withCustomExpiry(Duration expireAfterAccess, Ticker ticker) {
        Cache<Key, ConversationState> customCache = Caffeine.newBuilder()
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
        ConversationState state = cache.getIfPresent(new Key(null, conversationId));
        return state != null ? state : ConversationState.empty();
    }

    public void put(String conversationId, ConversationState state) {
        if (conversationId != null && state != null) {
            cache.put(new Key(null, conversationId), state);
        }
    }

    public Snapshot begin(String owner, String candidate) {
        Key key = new Key(owner, resolveConversationId(candidate));
        if (owner == null) {
            return new Snapshot(key, get(key.id()));
        }
        if (candidate == null || candidate.isBlank()) {
            ConversationState empty = ConversationState.empty();
            cache.put(key, empty);
            return new Snapshot(key, empty);
        }
        ConversationState state = cache.getIfPresent(key);
        if (state == null) {
            throw new ChatException(HttpStatus.NOT_FOUND, "conversation_not_found");
        }
        return new Snapshot(key, state);
    }

    public void append(Key key, UnaryOperator<ConversationState> update) {
        if (key.owner() == null) {
            cache.asMap().compute(key, (id, current) -> update.apply(
                    current == null ? ConversationState.empty() : current));
        } else {
            // Завершившийся после истечения диалога ответ не восстанавливает его.
            cache.asMap().computeIfPresent(key, (id, state) -> update.apply(state));
        }
    }

    public long size() {
        return cache.estimatedSize();
    }

    public void cleanUp() {
        cache.cleanUp();
    }
}
