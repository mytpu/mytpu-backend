package org.mytpu.gateway.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.ApiClientStore;
import org.mytpu.gateway.access.KeyRequests;
import org.mytpu.gateway.access.KeyRules;
import org.mytpu.gateway.access.McpKeyFilter;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Заявки на мастер-ключи в консоли (specs/key-lifecycle-and-decoys):
 * создание заявки, список по статусу, одобрение с выпуском ключа и отклонение с причиной.
 */
@RestController
public class KeyRequestsAdminController {

    public record KeyRequestView(String id, Instant at, String requester, String contact, String purpose,
                                 Set<String> scopes, String kind, Integer days, List<String> networks,
                                 String status, String decidedBy, Instant decidedAt, String rejectReason,
                                 String clientId) {
        public static KeyRequestView of(KeyRequests.KeyRequest r) {
            return new KeyRequestView(r.id(), r.at(), r.requester(), r.contact(), r.purpose(),
                    r.scopes(), r.kind().db(), r.days(), r.networks(), r.status(),
                    r.decidedBy(), r.decidedAt(), r.rejectReason(), r.clientId());
        }

        /** Для журнала действий — без почты и цели заявителя: журнал хранится дольше заявки. */
        KeyRequestView forAudit() {
            return new KeyRequestView(id, at, requester, null, null, scopes, kind, days, networks, status,
                    decidedBy, decidedAt, rejectReason, clientId);
        }
    }

    public record CreateKeyRequestBody(String requester, String contact, String purpose, Set<String> scopes,
                                       String kind, Integer days, List<String> networks) {
    }

    public record ApproveBody(String owner, Set<String> scopes, Set<String> deniedTools,
                             Map<String, Set<String>> deniedFields, Map<String, Integer> rateLimits, Boolean write, Long days,
                             String kind, List<String> networks, String geo, String geoReason) {
    }

    public record RejectBody(String reason) {
    }

    public record Issued(String clientId, String key, String command) {
    }

    public record KeyAuditSummary(String id, String kind, Set<String> scopes, Instant expiresAt) {
    }

    private final KeyRequests requests;
    private final ApiClientStore store;
    private final KeyRules rules;
    private final AdminAuditLog audit;
    private final TransactionTemplate tx;
    private final McpAddCommand mcpCommand;

    public KeyRequestsAdminController(KeyRequests requests, ApiClientStore store, KeyRules rules,
                                      AdminAuditLog audit, PlatformTransactionManager transactions,
                                      McpAddCommand mcpCommand) {
        this.requests = requests;
        this.store = store;
        this.rules = rules;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.mcpCommand = mcpCommand;
    }

    @PostMapping("/admin/api/key-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public KeyRequestView create(@RequestBody(required = false) CreateKeyRequestBody body, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        if (body == null) {
            throw AdminException.badRequest("bad_request");
        }
        ApiClient.Kind kind = kind(body.kind());
        KeyRequests.NewRequest draft = new KeyRequests.NewRequest(body.requester(), body.contact(), body.purpose(),
                body.scopes() == null ? Set.of() : body.scopes(), kind, body.days(),
                body.networks() == null ? List.of() : body.networks());
        return tx.execute(status -> {
            KeyRequestView view = KeyRequestView.of(requests.create(draft));
            audit.record(actor, "key_request.create", "key_request", view.id(), null, view.forAudit());
            return view;
        });
    }

    @GetMapping("/admin/api/key-requests")
    public List<KeyRequestView> list(@RequestParam(name = "status", required = false) String status) {
        return requests.list(status).stream().map(KeyRequestView::of).toList();
    }

    @PostMapping("/admin/api/key-requests/{id}/approve")
    public Issued approve(@PathVariable String id, @RequestBody(required = false) ApproveBody body,
                          HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        KeyRequests.KeyRequest req = requests.find(id)
                .orElseThrow(() -> AdminException.notFound("request_not_found", id));
        if (!"new".equals(req.status())) {
            throw AdminException.conflict("request_decided", id);
        }

        String owner = body != null && body.owner() != null && !body.owner().isBlank()
                ? body.owner().strip() : req.requester();
        Set<String> scopes = body != null && body.scopes() != null ? body.scopes() : req.scopes();
        Set<String> deniedTools = body != null && body.deniedTools() != null ? body.deniedTools() : Set.of();
        Map<String, Set<String>> deniedFields = body != null && body.deniedFields() != null ? body.deniedFields() : Map.of();
        Map<String, Integer> rateLimits = body != null && body.rateLimits() != null ? body.rateLimits() : Map.of();
        boolean write = body != null && Boolean.TRUE.equals(body.write());
        Long days = body != null && body.days() != null ? body.days()
                : (req.days() == null ? null : req.days().longValue());
        ApiClient.Kind kind = body != null && body.kind() != null && !body.kind().isBlank()
                ? kind(body.kind()) : req.kind();
        List<String> networks = body != null && body.networks() != null ? body.networks() : req.networks();
        ApiClient.GeoPolicy geo = body != null ? parseGeo(body.geo()) : null;
        String geoReason = body != null ? body.geoReason() : null;

        ApiClientStore.NewClient client = rules.newClient(new KeyRules.Request(
                owner, scopes, Set.of(), deniedTools, deniedFields, rateLimits, write, days, kind, networks, false,
                geo, geoReason
        ));

        return tx.execute(status -> {
            ApiClientStore.IssuedKey issued = store.issue(client);
            try {
                requests.approve(id, actor.owner(), issued.clientId());
            } catch (IllegalStateException e) {
                throw AdminException.conflict("request_decided", id);
            }
            KeyRequests.KeyRequest after = requests.find(id).orElseThrow();
            audit.record(actor, "key_request.approve", "key_request", id, KeyRequestView.of(req).forAudit(), KeyRequestView.of(after).forAudit());
            audit.record(actor, "key.create", "key", issued.clientId(), null,
                    new KeyAuditSummary(issued.clientId(), kind.db(), scopes, client.expiresAt()));
            return new Issued(issued.clientId(), issued.key(), mcpCommand.command(issued.key()));
        });
    }

    @PostMapping("/admin/api/key-requests/{id}/reject")
    public KeyRequestView reject(@PathVariable String id, @RequestBody(required = false) RejectBody body,
                                 HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        if (body == null || body.reason() == null || body.reason().isBlank() || body.reason().strip().length() > 500) {
            throw AdminException.badRequest("reason_required", 500);
        }
        KeyRequests.KeyRequest req = requests.find(id)
                .orElseThrow(() -> AdminException.notFound("request_not_found", id));
        if (!"new".equals(req.status())) {
            throw AdminException.conflict("request_decided", id);
        }
        return tx.execute(status -> {
            try {
                requests.reject(id, actor.owner(), body.reason().strip());
            } catch (IllegalStateException e) {
                throw AdminException.conflict("request_decided", id);
            }
            KeyRequests.KeyRequest after = requests.find(id).orElseThrow();
            audit.record(actor, "key_request.reject", "key_request", id, KeyRequestView.of(req).forAudit(), KeyRequestView.of(after).forAudit());
            return KeyRequestView.of(after);
        });
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
}
