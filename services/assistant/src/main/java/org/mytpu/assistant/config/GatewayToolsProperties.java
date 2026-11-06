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
        history = history == null ? new HistoryProperties(false, null, null, null, null) : history;
    }

    /**
     * История сообщений в БД (spec 5, шаг 7); до входа через ТПУ выключена.
     *
     * @param url       JDBC-адрес БД assistant ({@code ASSISTANT_DB_URL})
     * @param retention сколько хранить записи (по умолчанию 30 дней)
     */
    public record HistoryProperties(Boolean enabled, String url, String username, String password, Duration retention) {
        public HistoryProperties {
            enabled = enabled != null && enabled;
            retention = retention == null ? Duration.ofDays(30) : retention;
        }
    }
}
