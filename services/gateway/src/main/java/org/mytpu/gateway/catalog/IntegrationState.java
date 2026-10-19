package org.mytpu.gateway.catalog;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Выключенные администратором интеграции (таблица {@code integration_state}).
 * Читается с кэшем на 30 секунд: решение администратора действует не позже чем через 30 с (документ 20).
 */
@Component
public class IntegrationState {
    private static final String ALL = "all";

    private final JdbcClient jdbc;
    private final LoadingCache<String, Map<String, Boolean>> cache;

    public IntegrationState(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(1)
                .build(key -> load());
    }

    public boolean enabled(String integrationId) {
        return cache.get(ALL).getOrDefault(integrationId, true);
    }

    private Map<String, Boolean> load() {
        return jdbc.sql("SELECT id, enabled FROM integration_state")
                .query((rs, n) -> Map.entry(rs.getString("id"), rs.getBoolean("enabled")))
                .list().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
