package org.mytpu.gateway.admin;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.McpKeyFilter;
import org.mytpu.gateway.connectors.ConnectorCaller;
import org.mytpu.gateway.connectors.ConnectorHttpClient;
import org.mytpu.gateway.connectors.ConnectorSecrets;
import org.mytpu.gateway.connectors.ConnectorSpec;
import org.mytpu.gateway.connectors.ConnectorStore;
import org.mytpu.gateway.connectors.DeclarativeTool;
import org.mytpu.gateway.runtime.DescribedInput;
import org.mytpu.gateway.runtime.Messages;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * Секреты коннектора (задать, удалить; значение не возвращается никогда) и «Проверить»: вызов инструмента любой
 * версии с тестовыми аргументами из шлюза — сырой ответ (до 20 КБ), итоговые {@code summary} и {@code data}.
 */
@RestController
public class ConnectorProbeAdminController {
    private static final int RAW_LIMIT = 20 * 1024;
    private static final int MAX_SECRET = 4096;

    public record SecretValue(String value) {
    }

    public record Probe(String tool, ObjectNode arguments) {
    }

    private final ConnectorStore store;
    private final ConnectorSecrets secrets;
    private final ConnectorCaller caller;
    private final Messages messages;
    private final AdminAuditLog audit;
    private final TransactionTemplate tx;

    public ConnectorProbeAdminController(ConnectorStore store, ConnectorSecrets secrets, ConnectorCaller caller, Messages messages,
                                         AdminAuditLog audit, PlatformTransactionManager transactions) {
        this.store = store;
        this.secrets = secrets;
        this.caller = caller;
        this.messages = messages;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    @PutMapping("/admin/api/connectors/{id}/secrets/{name}")
    public Map<String, Object> putSecret(@PathVariable String id, @PathVariable String name, @RequestBody SecretValue body,
                                         HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        ConnectorSpec spec = latest(id);
        if (!spec.auth().type().secrets().contains(name)) {
            throw AdminException.badRequest("connector_secret_name", spec.auth().type().secrets());
        }
        // Перевод строки в секрете — подмена заголовка запроса; управляющих символов в ключах API не бывает
        if (body == null || body.value() == null || body.value().isBlank() || body.value().length() > MAX_SECRET
                || body.value().chars().anyMatch(Character::isISOControl)) {
            throw AdminException.badRequest("connector_secret_value", MAX_SECRET);
        }
        if (!secrets.available()) {
            throw new AdminException(HttpStatus.CONFLICT, "connector_vault_missing");
        }
        tx.executeWithoutResult(status -> {
            boolean existed = secrets.exists(id, name);
            secrets.put(id, name, spec.secretOrigin(), body.value());
            audit.record(actor, "connector.secret.set", "connector", id, Map.of("secret", name, "set", existed), Map.of("secret", name, "set", true));
        });
        caller.forget(id);
        return Map.of("id", id, "secret", name, "set", true);
    }

    @DeleteMapping("/admin/api/connectors/{id}/secrets/{name}")
    public Map<String, Object> deleteSecret(@PathVariable String id, @PathVariable String name, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        latest(id);
        boolean removed = Boolean.TRUE.equals(tx.execute(status -> {
            boolean r = secrets.delete(id, name);
            audit.record(actor, "connector.secret.delete", "connector", id, Map.of("secret", name, "set", r), Map.of("secret", name, "set", false));
            return r;
        }));
        caller.forget(id);
        return Map.of("id", id, "secret", name, "removed", removed);
    }

    @PostMapping("/admin/api/connectors/{id}/versions/{version}/test")
    public Map<String, Object> test(@PathVariable String id, @PathVariable int version, @RequestBody Probe body,
                                    HttpServletRequest request) {
        ConnectorSpec spec = store.version(id, version).map(ConnectorStore.Version::spec)
                .orElseThrow(() -> AdminException.notFound("connector_version_not_found", id, version));
        ConnectorSpec.ToolSpec toolSpec = spec.tools().stream().filter(t -> body != null && t.name().equals(body.tool())).findFirst()
                .orElseThrow(() -> AdminException.notFound("tool_not_found", body == null ? "" : body.tool()));
        // Вызов наружу из шлюза — в журнал действий (какая версия, какой инструмент; аргументы — нет)
        audit.record(McpKeyFilter.caller(request), "connector.test", "connector", id, null,
                Map.of("version", version, "tool", toolSpec.name()));
        DeclarativeTool tool = new DeclarativeTool(id, spec, toolSpec, caller, messages);
        DescribedInput.Validated validated = tool.validate(body.arguments() == null ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode() : body.arguments());
        Map<String, Object> out = new LinkedHashMap<>();
        if (!validated.errors().isEmpty()) {
            out.put("ok", false);
            out.put("error", "invalid_arguments");
            out.put("problems", validated.errors());
            return out;
        }
        try {
            ConnectorCaller.Outcome outcome = caller.call(id, spec, toolSpec, (ObjectNode) validated.input());
            byte[] raw = outcome.fetched().body();
            out.put("ok", true);
            out.put("status", outcome.fetched().status());
            out.put("raw", new String(raw, 0, Math.min(raw.length, RAW_LIMIT), StandardCharsets.UTF_8));
            out.put("rawTruncated", raw.length > RAW_LIMIT);
            out.put("summary", outcome.summary());
            out.put("data", outcome.mapped().data());
        } catch (ConnectorHttpClient.CallFailed e) {
            out.put("ok", false);
            out.put("error", e.code());
            out.put("message", messages.text("connector.call." + e.code(), e.args()));
        }
        return out;
    }

    private ConnectorSpec latest(String id) {
        if (!store.exists(id)) {
            throw AdminException.notFound("connector_not_found", id);
        }
        return store.versions(id).getFirst().spec();
    }

}
