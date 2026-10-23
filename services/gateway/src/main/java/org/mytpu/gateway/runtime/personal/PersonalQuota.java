package org.mytpu.gateway.runtime.personal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Суточный лимит личных записей на пару «пользователь — клиент» ({@code mytpu.gateway.personal.max-records-per-day}).
 * До вызова инструмента записи <b>занимаются</b> одним атомарным запросом (не больше {@code maxItems}), после — счёт
 * уточняется по тому, что реально ушло: параллельные вызовы не превышают лимит. Счёт в БД переживает перезапуск.
 */
@Component
public class PersonalQuota {
    private final JdbcClient jdbc;
    private final PersonalProperties properties;

    public PersonalQuota(JdbcClient jdbc, PersonalProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** @return удалось ли занять {@code records} записей (лимит ещё не исчерпан) */
    public boolean reserve(String userId, String clientId, int records) {
        return !jdbc.sql("""
                        INSERT INTO personal_usage (user_id, client_id, day, records) VALUES (:user, :client, current_date, :records)
                        ON CONFLICT (user_id, client_id, day) DO UPDATE SET records = personal_usage.records + :records
                        WHERE personal_usage.records < :max
                        RETURNING records
                        """)
                .param("user", userId).param("client", clientId).param("records", records)
                .param("max", properties.maxRecordsPerDay())
                .query(Integer.class).list().isEmpty();
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
