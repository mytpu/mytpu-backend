package org.mytpu.gateway.admin;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.McpKeyFilter;
import org.mytpu.gateway.catalog.IntegrationCatalog;
import org.mytpu.gateway.catalog.IntegrationStatus;
import org.mytpu.gateway.runtime.DescribedFields;
import org.mytpu.gateway.runtime.GatewayControl;
import org.mytpu.gateway.runtime.Messages;
import org.mytpu.gateway.runtime.RegisteredTool;
import org.mytpu.gateway.runtime.ToolLimits;
import org.mytpu.gateway.runtime.ToolRegistry;
import org.mytpu.tool.api.ToolDescriptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Каталог в консоли: интеграции → инструменты с состоянием и действующими лимитами; включить/выключить интеграцию или
 * инструмент; свои лимиты инструмента (таблица {@code tool_settings}). Всё действует за ≤ 30 с (кэши на 30 с).
 */
@RestController
public class CatalogAdminController {
    private static final int MAX_REASON = 500;

    public record ToolView(String name, String scope, String kind, String sensitivity, String description, boolean enabled,
                           long cacheTtlSeconds, long timeoutMs, int ratePerMinute, ToolLimits.Override override,
                           List<FieldView> fields) {
    }

    /** Личное поле ответа, которое можно выключить у ключа (шаг 7а). */
    public record FieldView(String name, String sensitivity) {
    }

    public record IntegrationView(IntegrationStatus integration, List<ToolView> tools) {
    }

    public record StateChange(Boolean enabled, String reason) {
    }

    public record LimitsChange(Long cacheTtlSeconds, Long timeoutMs, Integer ratePerMinute) {
    }

    private final IntegrationCatalog catalog;
    private final ToolRegistry registry;
    private final GatewayControl control;
    private final ToolLimits limits;
    private final Messages messages;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AdminAuditLog audit;
    private final DescribedFields describedFields;

    public CatalogAdminController(IntegrationCatalog catalog, ToolRegistry registry, GatewayControl control, ToolLimits limits,
                                  Messages messages, JdbcClient jdbc, PlatformTransactionManager transactions, AdminAuditLog audit,
                                  DescribedFields describedFields) {
        this.catalog = catalog;
        this.registry = registry;
        this.control = control;
        this.limits = limits;
        this.messages = messages;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.audit = audit;
        this.describedFields = describedFields;
    }

    @GetMapping("/admin/api/catalog")
    public List<IntegrationView> catalog() {
        Map<String, ToolLimits.Override> overrides = limits.overrides();
        return catalog.status().stream().map(status -> new IntegrationView(status, registry.all().stream()
                .filter(t -> t.integration().id().equals(status.id()))
                .map(t -> tool(t, overrides.get(t.name())))
                .toList())).toList();
    }

    @PutMapping("/admin/api/integrations/{id}/state")
    public IntegrationView integrationState(@PathVariable String id, @RequestBody StateChange body, HttpServletRequest request) {
        if (catalog.all().stream().noneMatch(i -> i.id().equals(id))) {
            throw AdminException.notFound("integration_not_found", id);
        }
        state(body, request, "integration.state", "integration", id,
                "INSERT INTO integration_state (id, enabled, reason, updated_at) VALUES (:id, :enabled, :reason, now()) "
                        + "ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, reason = EXCLUDED.reason, updated_at = now()");
        return catalog().stream().filter(v -> v.integration().id().equals(id)).findFirst().orElseThrow();
    }

    @PutMapping("/admin/api/tools/{name}/state")
    public ToolView toolState(@PathVariable String name, @RequestBody StateChange body, HttpServletRequest request) {
        existing(name);
        state(body, request, "tool.state", "tool", name,
                "INSERT INTO tool_state (name, enabled, reason, updated_at) VALUES (:id, :enabled, :reason, now()) "
                        + "ON CONFLICT (name) DO UPDATE SET enabled = EXCLUDED.enabled, reason = EXCLUDED.reason, updated_at = now()");
        return tool(existing(name), limits.overrides().get(name));
    }

    @PutMapping("/admin/api/tools/{name}/limits")
    public ToolView toolLimits(@PathVariable String name, @RequestBody LimitsChange body, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        RegisteredTool tool = existing(name);
        LimitsChange change = body == null ? new LimitsChange(null, null, null) : body;
        check(change.cacheTtlSeconds() == null || (change.cacheTtlSeconds() >= 0 && change.cacheTtlSeconds() <= 86_400), "cache_ttl_range");
        check(change.timeoutMs() == null || (change.timeoutMs() >= 100 && change.timeoutMs() <= 60_000), "timeout_range");
        check(change.ratePerMinute() == null || (change.ratePerMinute() >= 1 && change.ratePerMinute() <= 10_000), "rate_range");
        tx.executeWithoutResult(status -> {
            ToolLimits.Override before = limits.overrides().get(name);
            if (change.cacheTtlSeconds() == null && change.timeoutMs() == null && change.ratePerMinute() == null) {
                jdbc.sql("DELETE FROM tool_settings WHERE tool = :tool").param("tool", name).update();
            } else {
                jdbc.sql("""
                                INSERT INTO tool_settings (tool, cache_ttl_ms, timeout_ms, rate_per_minute, updated_at)
                                VALUES (:tool, :ttl, :timeout, :rate, now())
                                ON CONFLICT (tool) DO UPDATE SET cache_ttl_ms = EXCLUDED.cache_ttl_ms, timeout_ms = EXCLUDED.timeout_ms,
                                    rate_per_minute = EXCLUDED.rate_per_minute, updated_at = now()
                                """)
                        .param("tool", name)
                        .param("ttl", change.cacheTtlSeconds() == null ? null : change.cacheTtlSeconds() * 1000)
                        .param("timeout", change.timeoutMs())
                        .param("rate", change.ratePerMinute())
                        .update();
            }
            audit.record(actor, "tool.limits", "tool", name, before, change);
        });
        limits.invalidate();
        return tool(tool, limits.overrides().get(name));
    }

    private void state(StateChange body, HttpServletRequest request, String action, String type, String id, String sql) {
        ApiClient actor = McpKeyFilter.caller(request);
        check(body != null && body.enabled() != null, "enabled_required");
        String reason = body.reason() == null ? null : body.reason().strip();
        check(body.enabled() || (reason != null && !reason.isEmpty() && reason.length() <= MAX_REASON), "reason_required", MAX_REASON);
        tx.executeWithoutResult(status -> {
            Map<String, Object> before = jdbc.sql("SELECT enabled, reason FROM " + table(type) + " WHERE " + key(type) + " = :id")
                    .param("id", id).query((rs, n) -> Map.<String, Object>of("enabled", rs.getBoolean("enabled"),
                            "reason", String.valueOf(rs.getString("reason")))).optional().orElse(Map.of("enabled", true));
            jdbc.sql(sql).param("id", id).param("enabled", body.enabled()).param("reason", reason).update();
            audit.record(actor, action, type, id, before, Map.of("enabled", body.enabled(), "reason", String.valueOf(reason)));
        });
    }

    private ToolView tool(RegisteredTool t, ToolLimits.Override override) {
        ToolDescriptor d = t.descriptor();
        String description = d.description() != null ? d.description() : messages.optional(d.name() + ".description");
        return new ToolView(d.name(), d.scope(), d.kind().name().toLowerCase(), d.sensitivity().name().toLowerCase(),
                description, control.toolEnabled(d.name()), seconds(limits.cacheTtl(d)), limits.timeout(d).toMillis(),
                limits.ratePerMinute(d), override, describedFields.of(d).stream()
                        .map(f -> new FieldView(f.name(), f.sensitivity().name().toLowerCase())).toList());
    }

    private RegisteredTool existing(String name) {
        return registry.find(name).orElseThrow(() -> AdminException.notFound("tool_not_found", name));
    }

    private static String table(String type) {
        return "integration".equals(type) ? "integration_state" : "tool_state";
    }

    private static String key(String type) {
        return "integration".equals(type) ? "id" : "name";
    }

    private static long seconds(Duration duration) {
        return duration == null ? 0 : duration.toSeconds();
    }

    private static void check(boolean condition, String code, Object... args) {
        if (!condition) {
            throw AdminException.badRequest(code, args);
        }
    }
}
