package org.mytpu.gateway.access;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Хранилище заявок на мастер-ключи (таблица {@code key_requests}, specs/key-lifecycle-and-decoys):
 * создание с проверкой полей, список с фильтром по статусу, одобрение и отклонение.
 */
@Component
public class KeyRequests {
    private static final Pattern CONTACT_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> ALLOWED_STATUSES = Set.of("new", "approved", "rejected");
    private static final String SELECT = """
            SELECT id, at, requester, contact, purpose, scopes, kind, days, networks::text[] AS networks_text,
                   status, decided_by, decided_at, reject_reason, client_id
            FROM key_requests""";

    public record KeyRequest(String id, Instant at, String requester, String contact, String purpose,
                             Set<String> scopes, ApiClient.Kind kind, Integer days, List<String> networks,
                             String status, String decidedBy, Instant decidedAt, String rejectReason, String clientId) {
    }

    public record NewRequest(String requester, String contact, String purpose, Set<String> scopes,
                             ApiClient.Kind kind, Integer days, List<String> networks) {
    }

    private final JdbcClient jdbc;
    private final KeyRules rules;

    public KeyRequests(JdbcClient jdbc, KeyRules rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    public KeyRequest create(NewRequest r) {
        if (r.requester() == null || r.requester().isBlank() || r.requester().strip().length() > 200) {
            throw new KeyRules.Violation("request_field", "requester", 200);
        }
        if (r.contact() != null && r.contact().strip().length() > 200) {
            throw new KeyRules.Violation("request_field", "contact", 200);
        }
        if (r.contact() == null || !CONTACT_EMAIL.matcher(r.contact().strip()).matches()) {
            throw new KeyRules.Violation("request_contact");
        }
        if (r.purpose() == null || r.purpose().isBlank() || r.purpose().strip().length() > 1000) {
            throw new KeyRules.Violation("request_field", "purpose", 1000);
        }
        Set<String> scopes = r.scopes() == null ? Set.of() : r.scopes();
        rules.checkScopes(scopes);
        ApiClient.Kind kind = r.kind() == null ? ApiClient.Kind.PERSON : r.kind();
        List<String> networks = r.networks() == null ? List.of() : r.networks();
        rules.checkNetworks(kind, networks);
        if (r.days() != null) {
            rules.checkDays(kind, false, networks, r.days().longValue());
        }

        String id = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.sql("""
                        INSERT INTO key_requests (id, requester, contact, purpose, scopes, kind, days, networks, status)
                        VALUES (:id, :requester, :contact, :purpose, :scopes, :kind, :days, CAST(:networks AS cidr[]), 'new')
                        """)
                .param("id", id)
                .param("requester", r.requester().strip())
                .param("contact", r.contact().strip())
                .param("purpose", r.purpose().strip())
                .param("scopes", scopes.toArray(String[]::new))
                .param("kind", kind.db())
                .param("days", r.days())
                .param("networks", networks.toArray(String[]::new))
                .update();
        return find(id).orElseThrow();
    }

    public List<KeyRequest> list(String status) {
        if (status != null && !status.isBlank()) {
            String s = status.strip().toLowerCase();
            if (!ALLOWED_STATUSES.contains(s)) {
                throw new KeyRules.Violation("request_status");
            }
            return jdbc.sql(SELECT + " WHERE status = :status ORDER BY at DESC LIMIT 200")
                    .param("status", s)
                    .query(this::map)
                    .list();
        }
        return jdbc.sql(SELECT + " ORDER BY at DESC LIMIT 200")
                .query(this::map)
                .list();
    }

    public Optional<KeyRequest> find(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public void approve(String id, String decidedBy, String clientId) {
        int updated = jdbc.sql("""
                        UPDATE key_requests
                        SET status = 'approved', decided_by = :decidedBy, decided_at = now(), client_id = :clientId
                        WHERE id = :id AND status = 'new'
                        """)
                .param("id", id)
                .param("decidedBy", decidedBy)
                .param("clientId", clientId)
                .update();
        if (updated == 0) {
            throw new IllegalStateException("request_decided");
        }
    }

    public void reject(String id, String decidedBy, String reason) {
        int updated = jdbc.sql("""
                        UPDATE key_requests
                        SET status = 'rejected', decided_by = :decidedBy, decided_at = now(), reject_reason = :reason
                        WHERE id = :id AND status = 'new'
                        """)
                .param("id", id)
                .param("decidedBy", decidedBy)
                .param("reason", reason)
                .update();
        if (updated == 0) {
            throw new IllegalStateException("request_decided");
        }
    }

    private KeyRequest map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp at = rs.getTimestamp("at");
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new KeyRequest(
                rs.getString("id"),
                at == null ? null : at.toInstant(),
                rs.getString("requester"),
                rs.getString("contact"),
                rs.getString("purpose"),
                strings(rs.getArray("scopes")),
                ApiClient.Kind.fromDb(rs.getString("kind")),
                rs.getObject("days", Integer.class),
                listStrings(rs.getArray("networks_text")),
                rs.getString("status"),
                rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getString("reject_reason"),
                rs.getString("client_id")
        );
    }

    private static Set<String> strings(Array array) throws SQLException {
        return array == null ? Set.of() : Set.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    private static List<String> listStrings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
