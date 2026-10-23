package org.mytpu.gateway.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Журнал вызовов и события ключей хранятся 180 дней (документ 20), подсети ключей — 90 дней (152-ФЗ),
 * журнал выдачи личного — {@code disclosure-retention} (1 год), суточные счётчики — 7 дней;
 * раз в сутки старые строки удаляются.
 */
@Component
public class AuditRetention {
    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    private final JdbcClient jdbc;
    private final java.time.Duration disclosureRetention;

    public AuditRetention(JdbcClient jdbc, org.mytpu.gateway.runtime.personal.PersonalProperties personal) {
        this.jdbc = jdbc;
        this.disclosureRetention = personal.disclosureRetention();
    }

    @Scheduled(cron = "${mytpu.gateway.audit.retention-cron:0 30 3 * * *}")
    public void purge() {
        int removed = jdbc.sql("DELETE FROM audit WHERE at < now() - interval '180 days'").update();
        if (removed > 0) {
            log.info("Из журнала удалено {} записей старше 180 дней", removed);
        }
        int events = jdbc.sql("DELETE FROM key_events WHERE at < now() - interval '180 days'").update();
        int networks = jdbc.sql("DELETE FROM key_networks WHERE last_seen < now() - interval '90 days'").update();
        if (events + networks > 0) {
            log.info("Удалено событий ключей: {}, подсетей ключей: {}", events, networks);
        }
        int disclosures = jdbc.sql("DELETE FROM disclosures WHERE at < now() - CAST(:days || ' days' AS interval)")
                .param("days", String.valueOf(disclosureRetention.toDays())).update();
        int usage = jdbc.sql("DELETE FROM personal_usage WHERE day < current_date - 7").update();
        if (disclosures + usage > 0) {
            log.info("Удалено записей журнала выдачи: {}, суточных счётчиков: {}", disclosures, usage);
        }
    }
}
