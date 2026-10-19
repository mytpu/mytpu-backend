package org.mytpu.tool.api;

/** Состояние интеграции для каталога и консоли. */
public record HealthStatus(State state, String detail) {

    public enum State {
        UP,
        DEGRADED,
        DOWN,
        /** Не хватает секретов или настроек — инструменты интеграции не публикуются. */
        NOT_CONFIGURED
    }

    public static HealthStatus up() {
        return new HealthStatus(State.UP, null);
    }

    public static HealthStatus notConfigured(String detail) {
        return new HealthStatus(State.NOT_CONFIGURED, detail);
    }

    public static HealthStatus down(String detail) {
        return new HealthStatus(State.DOWN, detail);
    }
}
