package org.mytpu.assistant.conversation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.mytpu.assistant.config.GatewayToolsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * История сообщений в БД assistant (spec 5, шаг 7): только маскированный текст ({@link HistoryMasker}), очистка по
 * сроку раз в сутки. Работает, только если {@code mytpu.assistant.history.enabled=true}; иначе — {@link NoOpHistoryRepository}.
 * Источник данных создаётся здесь, а не автонастройкой Spring Boot: при выключенной истории БД не нужна вовсе.
 */
@Component
@ConditionalOnProperty(name = "mytpu.assistant.history.enabled", havingValue = "true")
public class JdbcHistoryRepository implements HistoryRepository {
    private static final Logger log = LoggerFactory.getLogger(JdbcHistoryRepository.class);

    private final JdbcClient jdbc;
    private final HistoryMasker masker;
    private final GatewayToolsProperties.HistoryProperties settings;
    private final Clock clock;

    public JdbcHistoryRepository(GatewayToolsProperties properties, HistoryMasker masker, Clock clock) {
        this.settings = properties.history();
        if (settings.url() == null || settings.url().isBlank()) {
            throw new IllegalStateException("история включена, но не задан ASSISTANT_DB_URL (mytpu.assistant.history.url)");
        }
        DataSource dataSource = new DriverManagerDataSource(settings.url(),
                settings.username(), settings.password());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/history").load().migrate();
        this.jdbc = JdbcClient.create(dataSource);
        this.masker = masker;
        this.clock = clock;
    }

    @Override
    public void save(String conversationId, String messageId, String role, String content) {
        try {
            jdbc.sql("INSERT INTO chat_history (conversation_id, message_id, role, content) VALUES (:conv, :msg, :role, :content)")
                    .param("conv", UUID.fromString(conversationId))
                    .param("msg", UUID.fromString(messageId))
                    .param("role", role)
                    .param("content", masker.mask(content))
                    .update();
        } catch (DataAccessException e) {
            // Сбой истории не должен ломать ответ; текст исключения может содержать значения — только класс
            log.warn("история не сохранена: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "Asia/Tomsk")
    void purge() {
        Instant before = Instant.now(clock).minus(settings.retention());
        try {
            int removed = jdbc.sql("DELETE FROM chat_history WHERE created_at < :before")
                    .param("before", Timestamp.from(before)).update();
            log.info("история: удалено старых записей {}", removed);
        } catch (DataAccessException e) {
            log.warn("история не очищена: {}", e.getClass().getSimpleName());
        }
    }

    /** Планировщик нужен только для очистки истории — включается вместе с ней. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "mytpu.assistant.history.enabled", havingValue = "true")
    static class Scheduling {
    }
}
