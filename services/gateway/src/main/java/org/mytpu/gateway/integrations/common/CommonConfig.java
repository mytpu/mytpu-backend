package org.mytpu.gateway.integrations.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Общее для интеграций: часы (подменяются в тестах) и разбор дат. */
@Configuration
public class CommonConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DateArgParser dateArgParser(Clock clock) {
        return new DateArgParser(clock);
    }
}
