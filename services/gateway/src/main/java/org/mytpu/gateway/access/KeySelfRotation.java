package org.mytpu.gateway.access;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Самоперевыпуск действующего мастер-ключа клиентом (specs/key-lifecycle-and-decoys, шаг 3).
 * Новый ключ выдаётся один раз, прежний ключ действует переходный срок (grace).
 * Запрещено для демо-ключей, ключей консоли и ловушек.
 */
@Component
public class KeySelfRotation {

    /** Результат успешного самоперевыпуска ключа. */
    public record Result(String key, Instant expiresAt, Instant previousKeyUntil) {
    }

    /** Отказ в самоперевыпуске: HTTP-статус, код ошибки и задержка повтора в секундах. */
    public static class RotationDenied extends RuntimeException {
        private final int status;
        private final String error;
        private final long retryAfterSeconds;

        public RotationDenied(int status, String error, long retryAfterSeconds) {
            super(error);
            this.status = status;
            this.error = error;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int status() {
            return status;
        }

        public String error() {
            return error;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final JdbcClient jdbc;
    private final ApiClientStore store;
    private final KeyRules rules;
    private final KeyEvents events;
    private final KeyLifecycleProperties properties;

    public KeySelfRotation(JdbcClient jdbc, ApiClientStore store, KeyRules rules, KeyEvents events,
                           KeyLifecycleProperties properties) {
        this.jdbc = jdbc;
        this.store = store;
        this.rules = rules;
        this.events = events;
        this.properties = properties;
    }

    /**
     * Выполняет самоперевыпуск мастер-ключа. Новый ключ сохраняется атомарно,
     * прежний действует переходный срок. При нарушении правил выбрасывает {@link RotationDenied}.
     */
    public Result rotate(ApiClient client, String presentedKey, String subnet) {
        if (client.kind() == ApiClient.Kind.DEMO || !client.roles().isEmpty() || client.decoy()) {
            throw new RotationDenied(403, "rotate_not_allowed", 0);
        }
        if (presentedKey == null || presentedKey.isBlank()) {
            throw new RotationDenied(403, "rotate_not_allowed", 0);
        }

        byte[] presentedHash = ApiKeys.hash(presentedKey);
        String newKey = ApiKeys.generate();
        byte[] newHash = ApiKeys.hash(newKey);
        String newPrefix = ApiKeys.shownPrefix(newKey);
        Instant newExpires = rules.defaultExpiry(client.kind(), client.decoy(), client.allowedNetworks());

        double graceSec = properties.selfRotateGrace().toMillis() / 1000.0;
        double everySec = properties.selfRotateEvery().toMillis() / 1000.0;

        long maxDays = rules.maxDays(client.kind(), client.decoy(), client.allowedNetworks());

        // Срок: не дольше умолчания по типу, не дольше периода, выданного администратором,
        // и не дальше created_at + предел типа — дальше новый ключ выдаёт только администратор
        Optional<Result> updated = jdbc.sql("""
                UPDATE api_clients
                SET previous_key_hash = key_hash,
                    previous_key_until = now() + make_interval(secs => :graceSec),
                    key_hash = :hash,
                    key_prefix = :prefix,
                    rotated_at = now(),
                    expires_at = LEAST(CAST(:expires AS timestamptz),
                                       now() + (expires_at - coalesce(rotated_at, created_at)),
                                       created_at + make_interval(days => :maxDays)),
                    expiry_warned_days = NULL
                WHERE id = :id
                  AND enabled
                  AND suspended_at IS NULL
                  AND NOT decoy
                  AND kind <> 'demo'
                  AND cardinality(roles) = 0
                  AND key_hash = :presentedHash
                  AND (rotated_at IS NULL OR rotated_at <= now() - make_interval(secs => :everySec))
                  AND created_at + make_interval(days => :maxDays) > now() + make_interval(secs => :graceSec)
                RETURNING expires_at, previous_key_until
                """)
                .param("id", client.id())
                .param("graceSec", graceSec)
                .param("hash", newHash)
                .param("prefix", newPrefix)
                .param("expires", Timestamp.from(newExpires))
                .param("presentedHash", presentedHash)
                .param("everySec", everySec)
                .param("maxDays", (int) maxDays)
                .query((rs, rowNum) -> new Result(newKey, rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("previous_key_until").toInstant()))
                .optional();

        if (updated.isPresent()) {
            store.invalidate(client.id());
            events.record(client.id(), KeyEvents.SELF_ROTATED, subnet, null);
            return updated.get();
        }

        handleFailure(client.id(), presentedHash, maxDays);
        throw new RotationDenied(403, "rotate_not_allowed", 0);
    }

    private void handleFailure(String clientId, byte[] presentedHash, long maxDays) {
        record KeyState(byte[] keyHash, byte[] prevHash, Timestamp rotatedAt, Timestamp createdAt) {
        }

        Optional<KeyState> state = jdbc.sql("""
                SELECT key_hash, previous_key_hash, rotated_at, created_at
                FROM api_clients
                WHERE id = :id
                """)
                .param("id", clientId)
                .query((rs, rowNum) -> new KeyState(
                        rs.getBytes("key_hash"),
                        rs.getBytes("previous_key_hash"),
                        rs.getTimestamp("rotated_at"),
                        rs.getTimestamp("created_at")
                ))
                .optional();

        if (state.isEmpty()) {
            throw new RotationDenied(403, "rotate_not_allowed", 0);
        }

        KeyState row = state.get();
        if (row.prevHash() != null && Arrays.equals(row.prevHash(), presentedHash)) {
            throw new RotationDenied(403, "rotate_current_key_only", 0);
        }

        if (row.keyHash() != null && Arrays.equals(row.keyHash(), presentedHash)) {
            Instant lifetimeEnd = row.createdAt().toInstant().plus(Duration.ofDays(maxDays));
            if (!lifetimeEnd.isAfter(Instant.now().plus(properties.selfRotateGrace()))) {
                throw new RotationDenied(403, "rotate_lifetime", 0);
            }
            if (row.rotatedAt() != null) {
                Instant nextAllowed = row.rotatedAt().toInstant().plus(properties.selfRotateEvery());
                Instant now = Instant.now();
                if (nextAllowed.isAfter(now)) {
                    long retryAfter = Math.max(1, (Duration.between(now, nextAllowed).toMillis() + 999) / 1000);
                    throw new RotationDenied(429, "rotate_too_often", retryAfter);
                }
            }
        }

        throw new RotationDenied(403, "rotate_not_allowed", 0);
    }
}
