package org.mytpu.gateway.access;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Регламентная фоновая уборка мастер-ключей (specs/key-lifecycle-and-decoys, шаг 4):
 * приостановка неактивных ключей, отзыв неиспользованных и отправка напоминаний об истечении срока.
 * Выполняется раз в сутки ночью (или по расписанию из настроек).
 * Для предотвращения параллельного запуска между экземплярами используется PostgreSQL advisory-lock.
 */
@Component
public class KeyHousekeeping {
    private static final Logger log = LoggerFactory.getLogger(KeyHousekeeping.class);

    /**
     * Уникальный идентификатор транзакционного advisory lock для исключения
     * одновременного выполнения уборки ключей несколькими экземплярами шлюза.
     */
    private static final long ADVISORY_LOCK_ID = 0x6d747075_6b657904L;

    /** Итог одного запуска регламентной уборки ключей. */
    public record Summary(int suspended, int revoked, int warned) {
    }

    private record ExpiringClient(String id, Instant expiresAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ApiClientStore store;
    private final KeyEvents events;
    private final KeyLifecycleProperties properties;

    public KeyHousekeeping(JdbcClient jdbc, PlatformTransactionManager transactions, ApiClientStore store,
                           KeyEvents events, KeyLifecycleProperties properties) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.store = store;
        this.events = events;
        this.properties = properties;
    }

    @Scheduled(cron = "${mytpu.gateway.keys.housekeeping-cron:0 15 4 * * *}")
    public void run() {
        runOnce();
    }

    /**
     * Выполняет полный цикл регламентной уборки в одной транзакции с advisory lock.
     * Возвращает сводку с количеством обработанных ключей.
     */
    public Summary runOnce() {
        Summary summary = tx.execute(status -> {
            Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:lockId)")
                    .param("lockId", ADVISORY_LOCK_ID)
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(locked)) {
                log.info("Регламентная уборка ключей пропущена: замок уже удерживается другим экземпляром");
                return new Summary(0, 0, 0);
            }

            int suspended = suspendInactive();
            int revoked = revokeUnused();
            int warned = warnExpiring();

            log.info("Регламентная уборка ключей: приостановлено={}, отозвано={}, предупреждений={}",
                    suspended, revoked, warned);
            return new Summary(suspended, revoked, warned);
        });

        return summary != null ? summary : new Summary(0, 0, 0);
    }

    private int suspendInactive() {
        double sec = properties.inactiveAfter().toMillis() / 1000.0;
        String detail = String.valueOf(properties.inactiveAfter().toDays());
        List<String> ids = jdbc.sql("""
                UPDATE api_clients
                SET suspended_at = now(),
                    suspended_reason = 'inactive'
                WHERE enabled
                  AND suspended_at IS NULL
                  AND NOT decoy
                  AND cardinality(roles) = 0
                  AND coalesce(last_used_at, created_at) < now() - make_interval(secs => :sec)
                  AND (expires_at IS NULL OR expires_at > now())
                RETURNING id
                """)
                .param("sec", sec)
                .query(String.class)
                .list();

        for (String id : ids) {
            store.invalidate(id);
            events.recordNow(id, KeyEvents.INACTIVE_SUSPENDED, detail);
        }
        return ids.size();
    }

    private int revokeUnused() {
        if (properties.revokeUnusedAfter() == null) {
            return 0;
        }
        double sec = properties.revokeUnusedAfter().toMillis() / 1000.0;
        String detail = String.valueOf(properties.revokeUnusedAfter().toDays());
        List<String> ids = jdbc.sql("""
                UPDATE api_clients
                SET enabled = false,
                    previous_key_until = NULL
                WHERE enabled
                  AND last_used_at IS NULL
                  AND NOT decoy
                  AND cardinality(roles) = 0
                  AND created_at < now() - make_interval(secs => :sec)
                RETURNING id
                """)
                .param("sec", sec)
                .query(String.class)
                .list();

        for (String id : ids) {
            store.invalidate(id);
            events.recordNow(id, KeyEvents.UNUSED_REVOKED, detail);
        }
        return ids.size();
    }

    private int warnExpiring() {
        List<Integer> days = properties.expiryWarnDays();
        if (days == null || days.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = days.stream().sorted().toList();
        int total = 0;
        for (int d : sorted) {
            List<ExpiringClient> warned = jdbc.sql("""
                    UPDATE api_clients
                    SET expiry_warned_days = :d
                    WHERE enabled
                      AND NOT decoy
                      AND expires_at > now()
                      AND expires_at <= now() + make_interval(days => :d)
                      AND (expiry_warned_days IS NULL OR expiry_warned_days > :d)
                    RETURNING id, expires_at
                    """)
                    .param("d", d)
                    .query((rs, rowNum) -> new ExpiringClient(
                            rs.getString("id"),
                            rs.getTimestamp("expires_at").toInstant()
                    ))
                    .list();

            for (ExpiringClient c : warned) {
                events.recordNow(c.id(), KeyEvents.EXPIRING, "d=" + d + " until=" + c.expiresAt());
            }
            total += warned.size();
        }
        return total;
    }
}
