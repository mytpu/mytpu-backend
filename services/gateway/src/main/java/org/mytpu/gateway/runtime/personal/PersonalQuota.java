package org.mytpu.gateway.runtime.personal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Суточный лимит личных записей на пару «пользователь — клиент» ({@code mytpu.gateway.personal.max-records-per-day}).
 * До вызова инструмента записи <b>занимаются</b> (не больше {@code maxItems} и не больше остатка), ответ обрезается
 * до занятого, после — счёт уточняется по тому, что реально ушло: лимит не превышается и при параллельных вызовах.
 * Счёт в БД переживает перезапуск.
 */
@Component
public class PersonalQuota {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PersonalProperties properties;

    public PersonalQuota(JdbcClient jdbc, PlatformTransactionManager transactions, PersonalProperties properties) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.properties = properties;
    }

    /**
     * Занимает до {@code wanted} записей, но не больше остатка лимита: строка дня блокируется на время транзакции,
     * параллельный вызов ждёт и видит уже занятое.
     *
     * @return сколько занято; 0 — лимит исчерпан
     */
    public int reserve(String userId, String clientId, int wanted) {
        Integer taken = tx.execute(status -> {
            jdbc.sql("""
                            INSERT INTO personal_usage (user_id, client_id, day, records) VALUES (:user, :client, current_date, 0)
                            ON CONFLICT (user_id, client_id, day) DO NOTHING
                            """)
                    .param("user", userId).param("client", clientId).update();
            int used = jdbc.sql("SELECT records FROM personal_usage "
                            + "WHERE user_id = :user AND client_id = :client AND day = current_date FOR UPDATE")
                    .param("user", userId).param("client", clientId)
                    .query(Integer.class).single();
            int take = Math.min(wanted, properties.maxRecordsPerDay() - used);
            if (take <= 0) {
                return 0;
            }
            jdbc.sql("UPDATE personal_usage SET records = records + :take "
                            + "WHERE user_id = :user AND client_id = :client AND day = current_date")
                    .param("user", userId).param("client", clientId).param("take", take).update();
            return take;
        });
        return taken == null ? 0 : taken;
    }

    /** Поправка счёта после вызова: {@code delta} может быть отрицательной (ушло меньше, чем занято). */
    public void adjust(String userId, String clientId, int delta) {
        if (delta == 0) {
            return;
        }
        jdbc.sql("UPDATE personal_usage SET records = GREATEST(0, records + :delta) "
                        + "WHERE user_id = :user AND client_id = :client AND day = current_date")
                .param("user", userId).param("client", clientId).param("delta", delta)
                .update();
    }
}
