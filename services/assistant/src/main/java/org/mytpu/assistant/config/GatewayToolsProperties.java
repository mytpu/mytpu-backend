package org.mytpu.assistant.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки подключения к MCP-шлюзу (specs/assistant/spec.md, шаг 8).
 *
 * @param gatewayUrl адрес шлюза (по умолчанию http://gateway:8080)
 * @param gatewayKey сервисный ключ с ролью chat.service
 * @param timeout    таймаут обращения к инструментам (по умолчанию 8с)
 * @param modelTimeout весь путь с моделью и инструментами (по умолчанию 15 с, дальше — шаблон из данных)
 */
@ConfigurationProperties("mytpu.assistant")
public record GatewayToolsProperties(
        String gatewayUrl,
        String gatewayKey,
        Duration timeout,
        Duration modelTimeout,
        HistoryProperties history
) {
    public GatewayToolsProperties {
        gatewayUrl = (gatewayUrl == null || gatewayUrl.isBlank()) ? "http://gateway:8080" : gatewayUrl;
        timeout = timeout == null ? Duration.ofSeconds(8) : timeout;
        modelTimeout = modelTimeout == null ? Duration.ofSeconds(15) : modelTimeout;
        history = history == null ? new HistoryProperties(false) : history;
    }

    public record HistoryProperties(Boolean enabled) {
        public HistoryProperties {
            enabled = enabled != null && enabled;
        }
    }
}
