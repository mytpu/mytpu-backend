package org.mytpu.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MCP-шлюз «Мой ТПУ». Модули (пакеты Spring Modulith): {@code catalog}, {@code access}, {@code runtime}, {@code mcp},
 * {@code integrations.*}. Двери и интеграции знают только {@code runtime} и контракт {@code tool-api}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
