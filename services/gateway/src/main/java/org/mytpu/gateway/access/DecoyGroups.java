package org.mytpu.gateway.access;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Управление группами ключей рядом с ловушкой (specs/key-lifecycle-and-decoys, шаг 5).
 * Срабатывание ключа-ловушки приостанавливает все настоящие ключи группы (компрометация места).
 */
@Component
public class DecoyGroups implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DecoyGroups.class);
    private static final int MAX_GROUP_SIZE = 100;
    private static final int TRIP_ATTEMPTS = 3;

    /** Ловушки, чья группа ждёт приостановки: одна задача на ловушку, поэтому поток вызовов ловушки не растит очередь. */
    private final Map<String, String> pending = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    private record MemberMeta(String id, boolean decoy, int roleCount) {
    }

    private final JdbcClient jdbc;
    private final ApiClientStore store;
    private final KeyEvents events;

    public DecoyGroups(JdbcClient jdbc, ApiClientStore store, KeyEvents events) {
        this.jdbc = jdbc;
        this.store = store;
        this.events = events;
    }

    /**
     * Приостановка группы в фоне: ответ на ловушку её не ждёт. Не отбрасывается при всплеске (одна задача на ловушку)
     * и повторяется при сбое БД; не вышло — ERROR в лог.
     */
    public void tripLater(String decoyId, String subnet) {
        if (pending.putIfAbsent(decoyId, subnet) != null) {
            return;
        }
        executor.submit(() -> {
            try {
                for (int attempt = 1; attempt <= TRIP_ATTEMPTS; attempt++) {
                    try {
                        trip(decoyId, subnet);
                        return;
                    } catch (RuntimeException e) {
                        if (attempt == TRIP_ATTEMPTS) {
                            log.error("Группа ловушки {} не приостановлена: {}", decoyId, e.getClass().getName());
                        } else {
                            Thread.sleep(1000L * attempt);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pending.remove(decoyId);
            }
        });
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    /**
     * Возвращает список идентификаторов ключей, входящих в группу ловушки.
     */
    public List<String> members(String decoyId) {
        return jdbc.sql("SELECT client_id FROM key_decoy_groups WHERE decoy_id = :d ORDER BY client_id")
                .param("d", decoyId)
                .query(String.class)
                .list();
    }

    /**
     * Заменяет состав группы ловушки в вызывающей транзакции.
     * Ловушка должна существовать и быть decoy; каждый член — существующий ключ,
     * не ловушка и не ключ консоли. В группе не более 100 членов.
     */
    @Transactional
    public void replace(String decoyId, List<String> clientIds) {
        Optional<Boolean> isDecoy = jdbc.sql("SELECT decoy FROM api_clients WHERE id = :id")
                .param("id", decoyId)
                .query(Boolean.class)
                .optional();
        if (isDecoy.isEmpty() || !Boolean.TRUE.equals(isDecoy.get())) {
            throw new KeyRules.Violation("decoy_group_not_decoy");
        }

        Set<String> uniqueIds = checkMembers(clientIds);

        jdbc.sql("DELETE FROM key_decoy_groups WHERE decoy_id = :d")
                .param("d", decoyId)
                .update();

        for (String memberId : uniqueIds) {
            jdbc.sql("INSERT INTO key_decoy_groups (decoy_id, client_id) VALUES (:d, :c)")
                    .param("d", decoyId)
                    .param("c", memberId)
                    .update();
        }
    }

    /** Проверка состава группы: существующие ключи, не ловушки и не ключи консоли, не больше 100. */
    public Set<String> checkMembers(List<String> clientIds) {
        List<String> incoming = clientIds == null ? List.of() : clientIds;
        if (incoming.size() > MAX_GROUP_SIZE) {
            throw new KeyRules.Violation("decoy_group_size", MAX_GROUP_SIZE);
        }

        Set<String> uniqueIds = new LinkedHashSet<>(incoming);
        if (uniqueIds.size() > MAX_GROUP_SIZE) {
            throw new KeyRules.Violation("decoy_group_size", MAX_GROUP_SIZE);
        }

        for (String memberId : uniqueIds) {
            Optional<MemberMeta> member = jdbc.sql("""
                    SELECT id, decoy, cardinality(roles) AS role_count
                    FROM api_clients
                    WHERE id = :id
                    """)
                    .param("id", memberId)
                    .query((rs, rowNum) -> new MemberMeta(
                            rs.getString("id"),
                            rs.getBoolean("decoy"),
                            rs.getInt("role_count")
                    ))
                    .optional();

            if (member.isEmpty() || member.get().decoy() || member.get().roleCount() > 0) {
                throw new KeyRules.Violation("decoy_group_member", memberId);
            }
        }
        return uniqueIds;
    }

    /**
     * Приостанавливает группу при срабатывании ловушки.
     * Для каждого приостановленного ключа сбрасывает кэш и записывает событие {@link KeyEvents#GROUP_SUSPENDED}.
     * Возвращает список идентификаторов приостановленных ключей.
     */
    public List<String> trip(String decoyId, String subnet) {
        List<String> suspendedIds = jdbc.sql("""
                UPDATE api_clients
                SET suspended_at = now(),
                    suspended_reason = 'decoy'
                WHERE id IN (SELECT client_id FROM key_decoy_groups WHERE decoy_id = :d)
                  AND suspended_at IS NULL
                  AND cardinality(roles) = 0
                RETURNING id
                """)
                .param("d", decoyId)
                .query(String.class)
                .list();

        for (String id : suspendedIds) {
            store.invalidate(id);
            events.record(id, KeyEvents.GROUP_SUSPENDED, subnet, "decoy=" + decoyId);
        }
        return suspendedIds;
    }
}
