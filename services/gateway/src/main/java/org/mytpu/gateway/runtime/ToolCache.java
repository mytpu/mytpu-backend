package org.mytpu.gateway.runtime;

import java.time.Duration;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.mytpu.tool.api.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Кэш успешных ответов: ключ — инструмент + пользователь + хэш аргументов, срок — {@code cacheTtl} из описания инструмента.
 * Ограничен по весу (размер ответа в JSON), чтобы память gateway не росла без предела.
 */
@Component
public class ToolCache {
    private static final long MAX_WEIGHT_BYTES = 32L * 1024 * 1024;

    private record Entry(ToolResult<?> result, Duration ttl, int bytes) {
    }

    private final JsonMapper json;
    private final Cache<String, Entry> cache = Caffeine.newBuilder()
            .maximumWeight(MAX_WEIGHT_BYTES)
            .weigher((String key, Entry entry) -> entry.bytes() + key.length())
            .expireAfter(new Expiry<String, Entry>() {
                @Override
                public long expireAfterCreate(String key, Entry entry, long now) {
                    return entry.ttl().toNanos();
                }

                @Override
                public long expireAfterUpdate(String key, Entry entry, long now, long current) {
                    return entry.ttl().toNanos();
                }

                @Override
                public long expireAfterRead(String key, Entry entry, long now, long current) {
                    return current;
                }
            })
            .build();

    public ToolCache(JsonMapper json) {
        this.json = json;
    }

    public Optional<ToolResult<?>> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key)).map(Entry::result);
    }

    public void put(String key, ToolResult<?> result, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        cache.put(key, new Entry(result, ttl, json.writeValueAsBytes(result).length));
    }
}
