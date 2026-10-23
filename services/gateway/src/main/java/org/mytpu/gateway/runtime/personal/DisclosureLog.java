package org.mytpu.gateway.runtime.personal;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Журнал выдачи личного ({@code gateway.disclosures}) — основа страницы «Мои данные и агенты»: кто, что, в каком режиме
 * и какие поля получил. Значений полей здесь нет — только их имена и число записей.
 */
@Component
public class DisclosureLog {
    private final JdbcClient jdbc;

    public DisclosureLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String userId, String clientId, String tool, String mode, String reason, List<String> fields, int records) {
        jdbc.sql("""
                        INSERT INTO disclosures (user_id, client_id, tool, mode, reason, fields, records)
                        VALUES (:user, :client, :tool, :mode, :reason, :fields, :records)
                        """)
                .param("user", userId).param("client", clientId).param("tool", tool)
                .param("mode", mode).param("reason", reason)
                .param("fields", fields.toArray(String[]::new)).param("records", records)
                .update();
    }
}
