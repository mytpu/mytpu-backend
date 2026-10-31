package org.mytpu.gateway.admin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.ApiClientAdmin;
import org.mytpu.gateway.access.DecoyGroups;
import org.mytpu.gateway.access.KeyRules;
import org.mytpu.gateway.access.McpKeyFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Управление группами ключей рядом с ловушкой в консоли (specs/key-lifecycle-and-decoys, шаг 5).
 * Чтение состава группы и замена списка ключей группы администратором.
 */
@RestController
public class DecoyGroupAdminController {

    public record DecoyGroupView(String decoyId, List<String> members) {
    }

    public record UpdateDecoyGroup(List<String> members) {
    }

    private final DecoyGroups decoys;
    private final ApiClientAdmin keys;
    private final AdminAuditLog audit;
    private final TransactionTemplate tx;

    public DecoyGroupAdminController(DecoyGroups decoys, ApiClientAdmin keys, AdminAuditLog audit,
                                     PlatformTransactionManager transactions) {
        this.decoys = decoys;
        this.keys = keys;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    @GetMapping("/admin/api/keys/{id}/decoy-group")
    public DecoyGroupView get(@PathVariable String id) {
        ApiClient client = existing(id);
        if (!client.decoy()) {
            throw new KeyRules.Violation("decoy_group_not_decoy");
        }
        return new DecoyGroupView(id, decoys.members(id));
    }

    @PutMapping("/admin/api/keys/{id}/decoy-group")
    public DecoyGroupView update(@PathVariable String id, @RequestBody(required = false) UpdateDecoyGroup body,
                                 HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        existing(id);
        List<String> members = body == null || body.members() == null ? List.of() : body.members();
        return tx.execute(status -> {
            DecoyGroupView before = new DecoyGroupView(id, decoys.members(id));
            decoys.replace(id, members);
            DecoyGroupView after = new DecoyGroupView(id, decoys.members(id));
            audit.record(actor, "key.decoy_group", "key", id, before, after);
            return after;
        });
    }

    private ApiClient existing(String id) {
        return keys.find(id).orElseThrow(() -> AdminException.notFound("key_not_found", id));
    }
}
