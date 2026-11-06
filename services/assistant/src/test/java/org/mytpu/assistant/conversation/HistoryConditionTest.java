package org.mytpu.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.mytpu.assistant.config.GatewayToolsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Критерий spec 5: «история выключена настройкой `mytpu.assistant.history.enabled=false`; с выключенной настройкой в БД
 * ничего не пишется». Выключено — в контексте только заглушка и БД не нужна; включено без адреса — понятный отказ запуска.
 */
class HistoryConditionTest {

    @Configuration
    @EnableConfigurationProperties(GatewayToolsProperties.class)
    static class Settings {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Settings.class, NoOpHistoryRepository.class, JdbcHistoryRepository.class, HistoryMasker.class);

    @Test
    void disabledHistoryUsesNoOpAndNeedsNoDatabase() {
        runner.withPropertyValues("mytpu.assistant.history.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HistoryRepository.class);
            assertThat(context.getBean(HistoryRepository.class)).isInstanceOf(NoOpHistoryRepository.class);
            assertThat(context).doesNotHaveBean(JdbcHistoryRepository.class);
        });
    }

    @Test
    void historyIsOffByDefault() {
        runner.run(context -> assertThat(context.getBean(HistoryRepository.class)).isInstanceOf(NoOpHistoryRepository.class));
    }

    @Test
    void enabledHistoryWithoutUrlFailsWithClearMessage() {
        runner.withPropertyValues("mytpu.assistant.history.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("ASSISTANT_DB_URL");
        });
    }
}
