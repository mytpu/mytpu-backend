package org.mytpu.gateway.integrations.docs;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Поиск по документам через сервис rag ({@code mytpu.gateway.docs.*}).
 *
 * @param url            адрес rag во внутренней сети
 * @param connectTimeout сколько ждать соединения
 * @param readTimeout    сколько ждать ответа поиска (у rag p95 — десятки мс)
 * @param defaultLimit   сколько документов вернуть, если модель не указала
 * @param summaryExcerpt длина выдержки в готовой фразе
 * @param dataExcerpt    длина выдержки у каждого найденного раздела
 */
@ConfigurationProperties("mytpu.gateway.docs")
public record DocsProperties(String url, Duration connectTimeout, Duration readTimeout,
                             Integer defaultLimit, Integer summaryExcerpt, Integer dataExcerpt) {

    public DocsProperties {
        defaultLimit = defaultLimit == null ? 3 : defaultLimit;
        summaryExcerpt = summaryExcerpt == null ? 300 : summaryExcerpt;
        dataExcerpt = dataExcerpt == null ? 800 : dataExcerpt;
        url = url == null ? "http://rag:8080" : url;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }
}
