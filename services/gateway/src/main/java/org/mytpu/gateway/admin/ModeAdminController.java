package org.mytpu.gateway.admin;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.mytpu.gateway.access.ApiClient;
import org.mytpu.gateway.access.McpKeyFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Режим шлюза: {@code normal} / {@code read_only} / {@code internal_only} с причиной; действует за ≤ 30 с (кэш {@code GatewayControl}). */
@RestController
public class ModeAdminController {
    private static final Set<String> MODES = Set.of("normal", "read_only", "internal_only");
    private static final int MAX_REASON = 500;

    public record ModeView(String mode, String reason, Instant updatedAt) {
    }

    public record ModeChange(String mode, String reason) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AdminAuditLog audit;

    public ModeAdminController(JdbcClient jdbc, PlatformTransactionManager transactions, AdminAuditLog audit) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.audit = audit;
    }

    @GetMapping("/admin/api/mode")
    public ModeView mode() {
        return current();
    }

    @PutMapping("/admin/api/mode")
    public ModeView change(@RequestBody ModeChange change, HttpServletRequest request) {
        ApiClient actor = McpKeyFilter.caller(request);
        String mode = change == null || change.mode() == null ? "" : change.mode().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw AdminException.badRequest("mode_unknown", MODES);
        }
        String reason = change.reason() == null ? "" : change.reason().strip();
        if (reason.isEmpty() || reason.length() > MAX_REASON) {
            throw AdminException.badRequest("reason_required", MAX_REASON);
        }
        return tx.execute(status -> {
            ModeView before = current();
            jdbc.sql("UPDATE mode SET value = :value, reason = :reason, updated_at = now() WHERE id = 1")
                    .param("value", mode).param("reason", reason).update();
            ModeView after = current();
            audit.record(actor, "mode.set", "mode", null, before, after);
            return after;
        });
    }

    private ModeView current() {
        return jdbc.sql("SELECT value, reason, updated_at FROM mode WHERE id = 1")
                .query((rs, n) -> new ModeView(rs.getString("value"), rs.getString("reason"), rs.getTimestamp("updated_at").toInstant()))
                .single();
    }
}
