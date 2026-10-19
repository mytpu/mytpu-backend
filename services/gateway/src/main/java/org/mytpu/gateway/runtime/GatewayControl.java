package org.mytpu.gateway.runtime;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Решения администратора: режим шлюза ({@code mode}) и выключенные инструменты ({@code tool_state}).
 * Читаются с кэшем на 30 секунд — действуют не позже чем через 30 с (документ 20).
 */
@Component
public class GatewayControl {

    public enum Mode { NORMAL, READ_ONLY, INTERNAL_ONLY }

    private static final Duration TTL = Duration.ofSeconds(30);

    private final JdbcClient jdbc;
    private final LoadingCache<String, Mode> mode;
    private final LoadingCache<String, Map<String, Boolean>> toolState;

    public GatewayControl(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.mode = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(1).build(k -> loadMode());
        this.toolState = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(1).build(k -> loadToolState());
    }

    public Mode mode() {
        return mode.get("mode");
    }

    public boolean toolEnabled(String name) {
        return toolState.get("tools").getOrDefault(name, true);
    }

    private Mode loadMode() {
        return jdbc.sql("SELECT value FROM mode WHERE id = 1").query(String.class).optional()
                .map(v -> Mode.valueOf(v.toUpperCase(java.util.Locale.ROOT)))
                .orElse(Mode.NORMAL);
    }

    private Map<String, Boolean> loadToolState() {
        return jdbc.sql("SELECT name, enabled FROM tool_state")
                .query((rs, n) -> Map.entry(rs.getString("name"), rs.getBoolean("enabled")))
                .list().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
