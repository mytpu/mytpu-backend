package org.mytpu.gateway.runtime.personal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Правила выдачи личного ({@code mytpu.gateway.personal.*}, specs/personal-data-for-agents).
 *
 * @param externalFullAllowed  можно ли внешнему агенту {@code full}; до решения вуза о зарубежных провайдерах — нет
 * @param fullWithoutDpopMax   самый долгий срок согласия {@code full} без привязки токена к устройству
 * @param maxRecordsPerDay     личных записей в сутки на пару «пользователь — клиент»
 * @param cacheTtlMax          потолок кэша ответов личных инструментов (чувствительные не кэшируются)
 * @param taintWindow          сколько после недоверенного текста в сессии {@code full} снижается до {@code masked}
 * @param cardUrl              адрес карточки «Мой ТПУ» для режима {@code reference}; пусто — без ссылки (до спецификации 7)
 * @param disclosureRetention  срок хранения журнала выдачи
 */
@ConfigurationProperties("mytpu.gateway.personal")
public record PersonalProperties(
        Boolean externalFullAllowed,
        Duration fullWithoutDpopMax,
        Integer maxRecordsPerDay,
        Duration cacheTtlMax,
        Duration taintWindow,
        String cardUrl,
        Duration disclosureRetention
) {
    public PersonalProperties {
        externalFullAllowed = externalFullAllowed != null && externalFullAllowed;
        fullWithoutDpopMax = fullWithoutDpopMax == null ? Duration.ofDays(1) : fullWithoutDpopMax;
        maxRecordsPerDay = maxRecordsPerDay == null ? 200 : maxRecordsPerDay;
        cacheTtlMax = cacheTtlMax == null ? Duration.ofSeconds(60) : cacheTtlMax;
        taintWindow = taintWindow == null ? Duration.ofMinutes(30) : taintWindow;
        cardUrl = cardUrl == null ? "" : cardUrl;
        disclosureRetention = disclosureRetention == null ? Duration.ofDays(365) : disclosureRetention;
    }
}
