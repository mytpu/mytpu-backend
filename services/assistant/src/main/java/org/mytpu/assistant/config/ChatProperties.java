package org.mytpu.assistant.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Лимиты чата ({@code mytpu.chat.*}, specs/prod-hardening-after-review).
 *
 * @param maxMessageChars   длина сообщения пользователя
 * @param maxBodyBytes      размер тела запроса
 * @param maxConcurrent     одновременных вопросов на экземпляр
 * @param perIpPerMinute    запросов с одного адреса в минуту
 * @param trustedProxies    прокси, которым верим в {@code X-Forwarded-For} (адреса или CIDR); пусто — никому
 */
@ConfigurationProperties("mytpu.chat")
public record ChatProperties(
        Integer maxMessageChars,
        Integer maxBodyBytes,
        Integer maxConcurrent,
        Integer perIpPerMinute,
        List<String> trustedProxies
) {
    public ChatProperties {
        maxMessageChars = maxMessageChars == null ? 4000 : maxMessageChars;
        maxBodyBytes = maxBodyBytes == null ? 16_384 : maxBodyBytes;
        maxConcurrent = maxConcurrent == null ? 4 : maxConcurrent;
        perIpPerMinute = perIpPerMinute == null ? 20 : perIpPerMinute;
        trustedProxies = trustedProxies == null ? List.of() : trustedProxies.stream().filter(p -> !p.isBlank()).toList();
        // Неверная настройка — отказ запуска, а не молча сломанный лимит (0 мест — все запросы 429)
        positive("max-message-chars", maxMessageChars);
        positive("max-body-bytes", maxBodyBytes);
        positive("max-concurrent", maxConcurrent);
        positive("per-ip-per-minute", perIpPerMinute);
    }

    private static void positive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("mytpu.chat." + name + " должно быть больше нуля, задано " + value);
        }
    }
}
