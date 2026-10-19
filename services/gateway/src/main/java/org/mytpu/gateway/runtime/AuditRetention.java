package org.mytpu.gateway.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Журнал хранится 180 дней (документ 20); раз в сутки старые строки удаляются. */
@Component
public class AuditRetention {
    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    private final JdbcClient jdbc;

    public AuditRetention(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${mytpu.gateway.audit.retention-cron:0 30 3 * * *}")
    public void purge() {
        int removed = jdbc.sql("DELETE FROM audit WHERE at < now() - interval '180 days'").update();
        if (removed > 0) {
            log.info("Из журнала удалено {} записей старше 180 дней", removed);
        }
    }
}
