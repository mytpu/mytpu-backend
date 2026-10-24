package org.mytpu.gateway.connectors;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Коннекторы и их версии в БД: версия неизменна, опубликованная — указатель {@code connectors.published_version}. */
@Component
public class ConnectorStore {
    private static final Logger log = LoggerFactory.getLogger(ConnectorStore.class);

    public record Summary(String id, String title, Integer publishedVersion, int latestVersion, Instant updatedAt) {
    }

    public record Version(int version, Instant createdAt, String createdBy, boolean published, ConnectorSpec spec) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ConnectorStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Summary> list() {
        return jdbc.sql("""
                        SELECT c.id, c.published_version, c.updated_at, v.version AS latest, v.spec ->> 'title' AS title
                        FROM connectors c
                        JOIN LATERAL (SELECT version, spec FROM connector_versions WHERE connector_id = c.id
                                      ORDER BY version DESC LIMIT 1) v ON true
                        ORDER BY c.id
                        """)
                .query((rs, n) -> new Summary(rs.getString("id"), rs.getString("title"),
                        rs.getObject("published_version", Integer.class), rs.getInt("latest"), rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    public boolean exists(String id) {
        return jdbc.sql("SELECT count(*) FROM connectors WHERE id = :id").param("id", id).query(Long.class).single() > 0;
    }

    /** Новая версия (черновик); коннектор создаётся при первой. */
    public int addVersion(String id, ConnectorSpec spec, String actor) {
        jdbc.sql("INSERT INTO connectors (id) VALUES (:id) ON CONFLICT (id) DO UPDATE SET updated_at = now()").param("id", id).update();
        return jdbc.sql("""
                        INSERT INTO connector_versions (connector_id, version, spec, created_by)
                        SELECT :id, coalesce(max(version), 0) + 1, CAST(:spec AS jsonb), :actor FROM connector_versions WHERE connector_id = :id
                        RETURNING version
                        """)
                .param("id", id).param("spec", json.writeValueAsString(spec)).param("actor", actor)
                .query(Integer.class).single();
    }

    public List<Version> versions(String id) {
        return jdbc.sql("""
                        SELECT v.version, v.created_at, v.created_by, v.spec::text AS spec, c.published_version = v.version AS published
                        FROM connector_versions v JOIN connectors c ON c.id = v.connector_id
                        WHERE v.connector_id = :id ORDER BY v.version DESC
                        """)
                .param("id", id)
                .query((rs, n) -> new Version(rs.getInt("version"), rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"),
                        rs.getBoolean("published"), parse(id, rs.getString("spec"))))
                .list();
    }

    public Optional<Version> version(String id, int version) {
        return versions(id).stream().filter(v -> v.version() == version).findFirst();
    }

    /** Опубликовать версию (в том числе прежнюю — откат); {@code null} — снять с публикации. */
    public void publish(String id, Integer version) {
        jdbc.sql("UPDATE connectors SET published_version = :version, updated_at = now() WHERE id = :id")
                .param("id", id).param("version", version).update();
    }

    /** Опубликованные коннекторы: id → описание; битое описание пропускается с записью в лог. */
    public Map<String, ConnectorSpec> published() {
        Map<String, ConnectorSpec> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT c.id, v.spec::text AS spec FROM connectors c
                        JOIN connector_versions v ON v.connector_id = c.id AND v.version = c.published_version
                        ORDER BY c.id
                        """)
                .query(rs -> {
                    ConnectorSpec spec = parse(rs.getString("id"), rs.getString("spec"));
                    if (spec != null) {
                        result.put(rs.getString("id"), spec);
                    }
                });
        return result;
    }

    /** Отпечаток опубликованного набора: другой экземпляр видит изменение и перечитывает коннекторы. */
    public String signature() {
        return jdbc.sql("SELECT coalesce(string_agg(id || ':' || published_version, ',' ORDER BY id), '') FROM connectors "
                + "WHERE published_version IS NOT NULL").query(String.class).single();
    }

    private ConnectorSpec parse(String id, String spec) {
        try {
            return json.readValue(spec, ConnectorSpec.class);
        } catch (JacksonException e) {
            log.error("Описание коннектора {} не читается: {}", id, e.getClass().getSimpleName());
            return null;
        }
    }
}
