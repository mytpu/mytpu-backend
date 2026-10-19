package org.mytpu.gateway.runtime;

import org.mytpu.gateway.GatewayApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.stereotype.Component;

/**
 * Профиль {@code dev}: проверка границ модулей Spring Modulith при старте — двери и интеграции не лезут
 * во внутренние пакеты друг друга, циклов нет. Постоянный тест границ — за тестировщиком команды.
 */
@Component
@Profile("dev")
public class ModuleBoundaries implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ModuleBoundaries.class);

    @Override
    public void run(ApplicationArguments args) {
        ApplicationModules modules = ApplicationModules.of(GatewayApplication.class);
        modules.forEach(m -> log.info("Модуль {}: {}", m.getIdentifier(), m.getBasePackage().getName()));
        modules.verify();
        log.info("Границы модулей: нарушений нет");
    }
}
