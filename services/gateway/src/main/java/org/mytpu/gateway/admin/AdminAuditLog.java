package org.mytpu.gateway.admin;

import java.util.regex.Pattern;

import org.mytpu.gateway.access.ApiClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Журнал действий администратора ({@code gateway.admin_audit}): кто, когда, что было → что стало.
 * Поля с секретами (имя похоже на {@code secret}, {@code password}, {@code token}, {@code credential}, {@code authorization},
 * {@code cookie}, {@code privateKey}, {@code apiKey}, {@code key}, {@code value}) — {@code ***}.
 * Пишется в той же транзакции, что и изменение: не записали журнал — изменения нет.
 */
@Component
public class AdminAuditLog {
    private static final Pattern SECRET = Pattern.compile("(?i).*(secret|passw|token|credential|authoriz|cookie|bearer|private_?key|access_?key|api_?key).*|key|value");
    private static final String MASK = "***";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AdminAuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(ApiClient actor, String action, String objectType, String objectId, Object before, Object after) {
        jdbc.sql("""
                        INSERT INTO admin_audit (actor_id, actor_owner, action, object_type, object_id, before, after)
                        VALUES (:actor, :owner, :action, :type, :object, CAST(:before AS jsonb), CAST(:after AS jsonb))
                        """)
                .param("actor", actor.id())
                .param("owner", actor.owner())
                .param("action", action)
                .param("type", objectType)
                .param("object", objectId)
                .param("before", masked(before))
                .param("after", masked(after))
                .update();
    }

    private String masked(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode tree = json.valueToTree(value);
        mask(tree);
        return json.writeValueAsString(tree);
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String name : java.util.List.copyOf(object.propertyNames())) {
                JsonNode child = object.get(name);
                if (SECRET.matcher(name).matches() && child != null && !child.isNull()) {
                    object.put(name, MASK);
                } else {
                    mask(child);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(AdminAuditLog::mask);
        }
    }
}
