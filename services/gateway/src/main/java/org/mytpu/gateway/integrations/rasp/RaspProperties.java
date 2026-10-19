package org.mytpu.gateway.integrations.rasp;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Публичное расписание ТПУ ({@code mytpu.gateway.rasp.*}). Сайт не нагружаем: кэш, не больше {@code maxConcurrent}
 * одновременных запросов, свой User-Agent с контактом.
 */
@ConfigurationProperties("mytpu.gateway.rasp")
public record RaspProperties(
        String baseUrl,
        /** Адрес сайта для людей: ссылки в ответах ведут сюда, а не на зеркало для чтения. */
        String publicUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Integer maxConcurrent,
        Duration searchCacheTtl
) {
    public RaspProperties {
        baseUrl = baseUrl == null ? "https://ro-rasp.tpu.ru" : baseUrl;
        publicUrl = publicUrl == null ? "https://rasp.tpu.ru" : publicUrl;
        userAgent = userAgent == null ? "mytpu-gateway/0.1 (+https://github.com/mytpu)" : userAgent;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(8) : readTimeout;
        maxConcurrent = maxConcurrent == null ? 4 : maxConcurrent;
        searchCacheTtl = searchCacheTtl == null ? Duration.ofMinutes(10) : searchCacheTtl;
    }
}
