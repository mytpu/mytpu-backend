package org.mytpu.gateway.admin;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.McpKeyFilter;
import org.mytpu.gateway.catalog.IntegrationCatalog;
import org.mytpu.gateway.connectors.ConnectorIntegration;
import org.mytpu.gateway.connectors.ConnectorSecrets;
import org.mytpu.gateway.connectors.ConnectorSpec;
import org.mytpu.gateway.connectors.ConnectorStore;
import org.mytpu.gateway.connectors.ConnectorValidator;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.gateway.runtime.ToolRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Коннекторы в консоли: список, версии, новая версия (черновик) с проверкой описания, публикация версии (она же откат
 * на прежнюю) и снятие с публикации. Опубликованное видно в {@code tools/list} ключам с этим scope за ≤ 30 с.
 */
@RestController
public class ConnectorsAdminController {

    public record ConnectorView(ConnectorStore.Summary connector, Set<String> secretsSet, List<String> secretsNeeded) {
    }

    public record Details(String id, Integer publishedVersion, Set<String> secretsSet, List<ConnectorStore.Version> versions) {
    }

    private final ConnectorStore store;
    private final ConnectorValidator validator;
    private final ConnectorSecrets secrets;
    private final ConnectorIntegration integration;
    private final IntegrationCatalog catalog;
    private final ToolRegistry registry;
    private final Messages messages;
    private final AdminAuditLog audit;
    private final TransactionTemplate tx;

    public ConnectorsAdminController(ConnectorStore store, ConnectorValidator validator, ConnectorSecrets secrets,
                                     ConnectorIntegration integration, IntegrationCatalog catalog, ToolRegistry registry,
                                     Messages messages, AdminAuditLog audit, PlatformTransactionManager transactions) {
        this.store = store;
        this.validator = validator;
        this.secrets = secrets;
        this.integration = integration;
        this.catalog = catalog;
        this.registry = registry;
        this.messages = messages;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    @GetMapping("/admin/api/connectors")
    public List<ConnectorView> list() {
        return store.list().stream().map(s -> {
            ConnectorSpec latest = store.version(s.id(), s.latestVersion()).map(ConnectorStore.Version::spec).orElse(null);
            Set<String> set = latest == null ? Set.of() : secrets.names(s.id(), latest.secretOrigin());
            List<String> needed = latest == null ? List.of() : latest.auth().type().secrets();
            return new ConnectorView(s, set, needed.stream().filter(n -> !set.contains(n)).toList());
        }).toList();
    }

    @GetMapping("/admin/api/connectors/{id}")
    public Details details(@PathVariable String id) {
        existing(id);
        List<ConnectorStore.Version> versions = store.versions(id);
        Integer published = versions.stream().filter(ConnectorStore.Version::published).map(ConnectorStore.Version::version)
                .findFirst().orElse(null);
        ConnectorSpec latest = versions.isEmpty() ? null : versions.getFirst().spec();
        return new Details(id, published, latest == null ? Set.of() : secrets.names(id, latest.secretOrigin()), versions);
    }

    /** Новая версия — черновик; описание проверяется, ошибки — списком по-русски. */
    @PostMapping("/admin/api/connectors/{id}/versions")
    public ResponseEntity<Map<String, Object>> addVersion(@PathVariable String id, @RequestBody ConnectorSpec spec,
                                                          HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        List<String> problems = problems(id, spec);
        if (!problems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "connector_invalid",
                    "message", messages.text("admin.error.connector_invalid"), "problems", problems));
        }
        int version = tx.execute(status -> {
            int v = store.addVersion(id, spec, actor.id());
            audit.record(actor, "connector.version", "connector", id, null, Map.of("version", v, "spec", spec));
            return v;
        });
        return ResponseEntity.status(201).body(Map.of("id", id, "version", version));
    }

    /** Публикация версии; прежняя версия — это откат. Описание проверяется ещё раз: каталог мог измениться. */
    @PostMapping("/admin/api/connectors/{id}/versions/{version}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable String id, @PathVariable int version, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        ConnectorStore.Version target = store.version(existing(id), version)
                .orElseThrow(() -> AdminException.notFound("connector_version_not_found", id, version));
        List<String> problems = target.spec() == null ? List.of(messages.text("admin.error.connector_unreadable")) : problems(id, target.spec());
        if (!problems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "connector_invalid",
                    "message", messages.text("admin.error.connector_invalid"), "problems", problems));
        }
        Integer before = details(id).publishedVersion();
        tx.executeWithoutResult(status -> {
            store.publish(id, version);
            audit.record(actor, "connector.publish", "connector", id, Map.of("version", String.valueOf(before)), Map.of("version", version));
        });
        integration.reload();
        return ResponseEntity.ok(Map.of("id", id, "publishedVersion", version));
    }

    @PostMapping("/admin/api/connectors/{id}/unpublish")
    public Map<String, Object> unpublish(@PathVariable String id, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        Integer before = details(existing(id)).publishedVersion();
        tx.executeWithoutResult(status -> {
            store.publish(id, null);
            audit.record(actor, "connector.unpublish", "connector", id, Map.of("version", String.valueOf(before)), null);
        });
        integration.reload();
        return Map.of("id", id, "published", false);
    }

    /** Ошибки описания; scope и имена инструментов не должны совпадать с чужими (свои опубликованные — не чужие). */
    private List<String> problems(String id, ConnectorSpec spec) {
        ConnectorSpec own = store.published().get(id);
        Set<String> foreignScopes = new HashSet<>(catalog.knownScopes());
        Set<String> foreignTools = new HashSet<>(registry.all().stream().map(RegisteredTool::name).toList());
        if (own != null) {
            foreignScopes.remove(own.scope().name());
            own.tools().forEach(t -> foreignTools.remove(t.name()));
        }
        return validator.validate(id, spec, foreignScopes, foreignTools).stream()
                .map(p -> messages.text("connector.error." + p.code(), p.args())).toList();
    }

    private String existing(String id) {
        if (!store.exists(id)) {
            throw AdminException.notFound("connector_not_found", id);
        }
        return id;
    }
}
