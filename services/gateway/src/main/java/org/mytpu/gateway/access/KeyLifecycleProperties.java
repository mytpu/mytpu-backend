package org.mytpu.gateway.access;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки жизненного цикла мастер-ключей ({@code mytpu.gateway.keys.*}):
 * сроки по типам ключей, ловушки, неактивность, напоминания и самоперевыпуск (specs/key-lifecycle-and-decoys).
 *
 * @param ttl                    сроки по типу ключа: по умолчанию и предел в днях
 * @param maxDaysWithoutNetworks предел срока ключа без сетей в днях
 * @param decoyDays              срок ключа-ловушки в днях
 * @param inactiveAfter          срок неактивности, после которого ключ приостанавливается
 * @param revokeUnusedAfter      срок отзыва неиспользованного ключа (null — выключено)
 * @param expiryWarnDays         дни до истечения для отправки напоминаний
 * @param selfRotateGrace        переходный срок действия старого ключа при самоперевыпуске
 * @param selfRotateEvery        минимальный интервал между самоперевыпусками
 * @param housekeepingCron       расписание регламентной уборки ключей
 * @param alerts                 настройки оповещений по вебхуку о событиях ключей
 */
@ConfigurationProperties("mytpu.gateway.keys")
public record KeyLifecycleProperties(
        Map<String, Ttl> ttl,
        Integer maxDaysWithoutNetworks,
        Integer decoyDays,
        Duration inactiveAfter,
        Duration revokeUnusedAfter,
        List<Integer> expiryWarnDays,
        Duration selfRotateGrace,
        Duration selfRotateEvery,
        String housekeepingCron,
        Alerts alerts
) {
    /**
     * Сроки действия ключа: по умолчанию и предел в днях.
     *
     * @param defaultDays срок по умолчанию в днях
     * @param maxDays     максимальный срок в днях
     */
    public record Ttl(Integer defaultDays, Integer maxDays) {
    }

    /**
     * Настройки оповещений о событиях ключей по вебхуку.
     *
     * @param events      список событий для отправки оповещений
     * @param chatId      идентификатор чата (для Telegram)
     * @param timeout     таймаут соединения и запроса к вебхуку
     * @param repeatAfter период дедупликации одинаковых событий одного ключа
     */
    public record Alerts(
            List<String> events,
            String chatId,
            Duration timeout,
            Duration repeatAfter
    ) {
        private static final List<String> DEFAULT_EVENTS = List.of(
                "key.decoy_used", "key.group_suspended", "key.network_burst", "key.expiring", "key.self_rotated");

        public Alerts {
            events = events == null || events.isEmpty() ? DEFAULT_EVENTS : List.copyOf(events);
            chatId = chatId == null ? "" : chatId;
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
            repeatAfter = repeatAfter == null ? Duration.ofMinutes(10) : repeatAfter;
        }
    }

    /** Умолчания по типу; тип без настройки получает их же. */
    private static final Map<String, Ttl> DEFAULT_TTL = Map.of(
            "person", new Ttl(30, 90),
            "server", new Ttl(90, 365),
            "demo", new Ttl(7, 14));

    public KeyLifecycleProperties {
        maxDaysWithoutNetworks = maxDaysWithoutNetworks == null ? 30 : maxDaysWithoutNetworks;
        decoyDays = decoyDays == null ? 365 : decoyDays;
        inactiveAfter = inactiveAfter == null ? Duration.ofDays(45) : inactiveAfter;
        expiryWarnDays = expiryWarnDays == null ? List.of(14, 3) : List.copyOf(expiryWarnDays);
        selfRotateGrace = selfRotateGrace == null ? Duration.ofHours(24) : selfRotateGrace;
        selfRotateEvery = selfRotateEvery == null ? Duration.ofDays(1) : selfRotateEvery;
        housekeepingCron = housekeepingCron == null || housekeepingCron.isBlank() ? "0 15 4 * * *" : housekeepingCron;
        alerts = alerts == null ? new Alerts(null, null, null, null) : alerts;
        Map<String, Ttl> merged = new LinkedHashMap<>(DEFAULT_TTL);
        if (ttl != null) {
            ttl.forEach((kind, value) -> {
                Ttl base = DEFAULT_TTL.getOrDefault(kind.toLowerCase(), DEFAULT_TTL.get("person"));
                merged.put(kind.toLowerCase(), new Ttl(
                        value == null || value.defaultDays() == null ? base.defaultDays() : value.defaultDays(),
                        value == null || value.maxDays() == null ? base.maxDays() : value.maxDays()));
            });
        }
        ttl = Map.copyOf(merged);
        // Ошибка в настройках — отказ при старте, а не ключ на неожиданный срок
        if (selfRotateGrace.isNegative() || selfRotateGrace.compareTo(ApiClientAdmin.MAX_GRACE) > 0) {
            throw new IllegalArgumentException("mytpu.gateway.keys.self-rotate-grace — от 0 до " + ApiClientAdmin.MAX_GRACE);
        }
        if (selfRotateEvery.isNegative() || selfRotateEvery.isZero()) {
            throw new IllegalArgumentException("mytpu.gateway.keys.self-rotate-every — больше нуля");
        }
        ttl.forEach((kind, t) -> {
            if (t.defaultDays() < 1 || t.defaultDays() > t.maxDays()) {
                throw new IllegalArgumentException("mytpu.gateway.keys.ttl." + kind + ": default-days — от 1 до max-days");
            }
        });
    }

    /** Сроки для типа ключа; {@code null} — как у {@code person}. */
    public Ttl ttl(ApiClient.Kind kind) {
        return ttl.get((kind == null ? ApiClient.Kind.PERSON : kind).db());
    }
}
