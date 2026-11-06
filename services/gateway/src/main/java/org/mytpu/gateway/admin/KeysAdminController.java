package org.mytpu.gateway.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.ApiClientAdmin;
import org.mytpu.gateway.access.ApiClientStore;
import org.mytpu.gateway.access.KeyRules;
import org.mytpu.gateway.access.McpKeyFilter;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Мастер-ключи в консоли: список, выдача (ключ показывается один раз вместе с командой {@code claude mcp add}),
 * смена разрешений (действует за ≤ 30 с), перевыпуск с переходным сроком, отзыв, снятие приостановки.
 */
@RestController
public class KeysAdminController {
    private static final Duration DEFAULT_GRACE = Duration.ofHours(24);

    public record KeyView(String id, String owner, String keyPrefix, String kind, Set<String> scopes, Set<String> roles,
                          Set<String> deniedTools, Map<String, Set<String>> deniedFields, Map<String, Integer> rateLimits,
                          boolean readOnly, boolean enabled,
                          Instant expiresAt, List<String> networks, boolean suspended, boolean decoy,
                          Instant createdAt, Instant lastUsedAt, long calls30d, Instant previousKeyUntil,
                          String geo, String geoReason) {
        static KeyView of(ApiClientAdmin.KeyRow row) {
            ApiClient c = row.client();
            return new KeyView(c.id(), c.owner(), c.keyPrefix(), c.kind().db(), c.scopes(), c.roles(), c.deniedTools(), c.deniedFields(),
                    c.rateLimits(), c.readOnly(), c.enabled(), c.expiresAt(), c.allowedNetworks(), c.suspended(), c.decoy(),
                    row.createdAt(), row.lastUsedAt(), row.calls30d(), row.previousKeyUntil(),
                    c.geo() != null ? c.geo().db() : "ru_only", c.geoReason());
        }
    }

    public record NewKey(String owner, Set<String> scopes, Set<String> roles, Set<String> deniedTools,
                         Map<String, Set<String>> deniedFields, Map<String, Integer> rateLimits, Boolean write, Long days, String kind, List<String> networks,
                         Boolean decoy, String geo, String geoReason) {
    }

    public record Issued(String clientId, String key, String command) {
    }

    /** Не указано {@code readOnly} — только чтение (безопасное значение). */
    public record PermissionsChange(Set<String> scopes, Set<String> deniedTools, Map<String, Set<String>> deniedFields,
                                    Map<String, Integer> rateLimits,
                                    Boolean readOnly, List<String> networks, String geo, String geoReason) {
    }

    public record ResumeKey(String geo, String geoReason) {
    }

    public record Reissue(Integer graceHours) {
    }

    private final ApiClientAdmin keys;
    private final ApiClientStore store;
    private final KeyRules rules;
    private final AdminAuditLog audit;
    private final TransactionTemplate tx;
    private final McpAddCommand mcpCommand;

    public KeysAdminController(ApiClientAdmin keys, ApiClientStore store, KeyRules rules, AdminAuditLog audit,
                               PlatformTransactionManager transactions, McpAddCommand mcpCommand) {
        this.keys = keys;
        this.store = store;
        this.rules = rules;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.mcpCommand = mcpCommand;
    }

    @GetMapping("/admin/api/keys")
    public List<KeyView> list() {
        return keys.list().stream().map(KeyView::of).toList();
    }

    @PostMapping("/admin/api/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public Issued create(@RequestBody NewKey body, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        ApiClientStore.NewClient client = rules.newClient(new KeyRules.Request(body.owner(), set(body.scopes()), set(body.roles()),
                set(body.deniedTools()), fields(body.deniedFields()), map(body.rateLimits()), Boolean.TRUE.equals(body.write()), body.days(), kind(body.kind()),
                body.networks() == null ? List.of() : body.networks(), Boolean.TRUE.equals(body.decoy()),
                parseGeo(body.geo()), body.geoReason()));
        return tx.execute(status -> {
            ApiClientStore.IssuedKey issued = store.issue(client);
            audit.record(actor, "key.create", "key", issued.clientId(), null, view(issued.clientId()));
            return new Issued(issued.clientId(), issued.key(), mcpCommand.command(issued.key()));
        });
    }

    @PutMapping("/admin/api/keys/{id}/permissions")
    public KeyView permissions(@PathVariable String id, @RequestBody PermissionsChange body, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        ApiClientAdmin.Permissions p = new ApiClientAdmin.Permissions(set(body.scopes()), set(body.deniedTools()),
                fields(body.deniedFields()), map(body.rateLimits()), !Boolean.FALSE.equals(body.readOnly()), body.networks() == null ? List.of() : body.networks(),
                parseGeo(body.geo()), body.geoReason());
        return tx.execute(status -> {
            KeyView before = view(id);
            keys.updatePermissions(existing(id), p);
            KeyView after = view(id);
            audit.record(actor, "key.permissions", "key", id, before, after);
            return after;
        });
    }

    @PostMapping("/admin/api/keys/{id}/reissue")
    public Issued reissue(@PathVariable String id, @RequestBody(required = false) Reissue body, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        Duration grace = body == null || body.graceHours() == null ? DEFAULT_GRACE : Duration.ofHours(body.graceHours());
        if (grace.isNegative() || grace.compareTo(ApiClientAdmin.MAX_GRACE) > 0) {
            throw AdminException.badRequest("grace_range", ApiClientAdmin.MAX_GRACE.toHours());
        }
        return tx.execute(status -> {
            KeyView before = view(id);
            ApiClientStore.IssuedKey issued = keys.reissue(existing(id), grace);
            audit.record(actor, "key.reissue", "key", id, before, view(id));
            return new Issued(id, issued.key(), mcpCommand.command(issued.key()));
        });
    }

    @PostMapping("/admin/api/keys/{id}/revoke")
    public KeyView revoke(@PathVariable String id, HttpServletRequest request) {
        return change(id, "key.revoke", request, keys::revoke);
    }

    @PostMapping("/admin/api/keys/{id}/resume")
    public KeyView resume(@PathVariable String id, @RequestBody(required = false) ResumeKey body, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        return tx.execute(status -> {
            KeyView before = view(id);
            ApiClient client = existing(id);
            ApiClient.GeoPolicy requestedGeo = body == null ? null : parseGeo(body.geo());
            String reason = body == null ? null : body.geoReason();
            boolean changed = keys.resume(client, requestedGeo, reason);
            if (!changed) {
                throw AdminException.conflict("key_not_suspended", id);
            }
            KeyView after = view(id);
            audit.record(actor, "key.resume", "key", id, before, after);
            return after;
        });
    }

    private KeyView change(String id, String action, HttpServletRequest request, java.util.function.Consumer<ApiClient> operation) {
        ApiClient actor = McpKeyFilter.caller(request);
        return tx.execute(status -> {
            KeyView before = view(id);
            operation.accept(existing(id));
            KeyView after = view(id);
            audit.record(actor, action, "key", id, before, after);
            return after;
        });
    }

    private ApiClient existing(String id) {
        return keys.find(id).orElseThrow(() -> AdminException.notFound("key_not_found", id));
    }

    private KeyView view(String id) {
        return keys.list().stream().filter(r -> r.client().id().equals(id)).findFirst().map(KeyView::of)
                .orElseThrow(() -> AdminException.notFound("key_not_found", id));
    }

    private static ApiClient.GeoPolicy parseGeo(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase();
        if ("ru".equals(v) || "ru_only".equals(v)) {
            return ApiClient.GeoPolicy.RU_ONLY;
        }
        if ("any".equals(v)) {
            return ApiClient.GeoPolicy.ANY;
        }
        throw AdminException.badRequest("geo_unknown");
    }

    private static ApiClient.Kind kind(String value) {
        try {
            return value == null || value.isBlank() ? ApiClient.Kind.PERSON : ApiClient.Kind.fromDb(value);
        } catch (IllegalArgumentException e) {
            throw AdminException.badRequest("kind_unknown");
        }
    }

    private static Set<String> set(Set<String> value) {
        return value == null ? Set.of() : value;
    }

    /** Пустые списки полей не храним: инструмент без выключенных полей — без записи. */
    private static Map<String, Set<String>> fields(Map<String, Set<String>> value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Set<String>> out = new java.util.TreeMap<>();
        value.forEach((tool, names) -> {
            if (names != null && !names.isEmpty()) {
                // Set.copyOf падает на null: пусть null дойдёт до KeyRules.checkFields и вернётся 400, а не 500
                out.put(tool, names.contains(null) ? new java.util.HashSet<>(names) : Set.copyOf(names));
            }
        });
        return out;
    }

    private static Map<String, Integer> map(Map<String, Integer> value) {
        return value == null ? Map.of() : value;
    }
}
