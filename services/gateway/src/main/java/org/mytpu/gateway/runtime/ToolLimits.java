package org.mytpu.gateway.runtime;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.mytpu.tool.api.ToolDescriptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Действующие лимиты инструмента: своё значение из консоли (таблица {@code tool_settings}) → {@code application.yml}
 * ({@link ToolSettings}) → описание инструмента. Таблица читается с кэшем на 30 с — изменение действует за ≤ 30 с.
 */
@Component
public class ToolLimits {
    /** Своё значение из консоли; {@code null} в поле — не переопределено. */
    public record Override(Duration cacheTtl, Duration timeout, Integer ratePerMinute) {
    }

    private final ToolSettings config;
    private final JdbcClient jdbc;
    private final LoadingCache<String, Map<String, Override>> overrides;

    public ToolLimits(ToolSettings config, JdbcClient jdbc) {
        this.config = config;
        this.jdbc = jdbc;
        this.overrides = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).maximumSize(1).build(k -> load());
    }

    public Duration cacheTtl(ToolDescriptor descriptor) {
        Override o = overrides().get(descriptor.name());
        return o != null && o.cacheTtl() != null ? o.cacheTtl() : config.cacheTtl(descriptor);
    }

    public Duration timeout(ToolDescriptor descriptor) {
        Override o = overrides().get(descriptor.name());
        return o != null && o.timeout() != null ? o.timeout() : config.timeout(descriptor);
    }

    public int ratePerMinute(ToolDescriptor descriptor) {
        Override o = overrides().get(descriptor.name());
        return o != null && o.ratePerMinute() != null ? o.ratePerMinute() : config.ratePerMinute(descriptor);
    }

    public Map<String, Override> overrides() {
        return overrides.get("all");
    }

    /** Консоль изменила таблицу — на этом экземпляре сразу, на остальных — за ≤ 30 с. */
    public void invalidate() {
        overrides.invalidateAll();
    }

    private Map<String, Override> load() {
        return jdbc.sql("SELECT tool, cache_ttl_ms, timeout_ms, rate_per_minute FROM tool_settings")
                .query((rs, n) -> Map.entry(rs.getString("tool"), new Override(
                        millis(rs.getObject("cache_ttl_ms", Long.class)),
                        millis(rs.getObject("timeout_ms", Long.class)),
                        rs.getObject("rate_per_minute", Integer.class))))
                .list().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Duration millis(Long value) {
        return value == null ? null : Duration.ofMillis(value);
    }
}
