package org.mytpu.assistant.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Базовая конфигурация часов в часовом поясе Томска.
 */
@Configuration
public class AssistantConfig {

    public static final ZoneId TOMSK_ZONE = ZoneId.of("Asia/Tomsk");

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.system(TOMSK_ZONE);
    }
}
