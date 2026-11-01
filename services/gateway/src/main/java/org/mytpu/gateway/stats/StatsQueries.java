package org.mytpu.gateway.stats;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.mytpu.gateway.runtime.AuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Обзор консоли по поминутным агрегатам ({@code audit_minute}), а не по сырому журналу: запрос за сутки читает
 * не больше 1440 строк на пару «клиент — инструмент». p95 — по корзинам длительности (верхняя граница корзины).
 */
@Component
public class StatsQueries {
    public record SeriesPoint(Instant at, long calls, long errors) {
    }

    public record Totals(long calls, long errors, long cacheHits) {
    }

    public record ToolStats(String tool, long calls, long errors, long cacheHits, long avgMs, Long p95Ms) {
    }

    public record ClientStats(String clientId, String owner, long calls, long errors) {
    }

    public record Overview(Instant from, Instant to, Totals totals, List<ToolStats> tools, List<ClientStats> topClients, List<SeriesPoint> series) {
    }

    private static final int BUCKETS = AuditLog.LATENCY_BUCKETS_MS.length + 1;
    private static final String BUCKET_SUMS = java.util.stream.IntStream.rangeClosed(1, BUCKETS)
            .mapToObj(i -> "sum(buckets[" + i + "]) AS b" + i).collect(java.util.stream.Collectors.joining(", ")) + " ";

    private final JdbcClient jdbc;

    public StatsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Overview overview(Duration window, int topClients) {
        Instant to = Instant.now();
        Instant from = to.minus(window);
        Timestamp since = Timestamp.from(from);
        // Один проход по агрегатам: суммы и корзины длительности поэлементно (без unnest — он дороже в 12 раз)
        List<ToolStats> tools = jdbc.sql("""
                        SELECT tool, sum(calls) AS calls, sum(errors) AS errors, sum(cache_hits) AS hits, sum(total_ms) AS ms,
                        """ + BUCKET_SUMS + """
                        FROM audit_minute WHERE minute >= date_trunc('minute', CAST(:since AS timestamptz))
                        GROUP BY tool ORDER BY calls DESC
                        """)
                .param("since", since)
                .query((rs, n) -> {
                    long calls = rs.getLong("calls");
                    long[] buckets = new long[BUCKETS];
                    for (int i = 0; i < BUCKETS; i++) {
                        buckets[i] = rs.getLong("b" + (i + 1));
                    }
                    return new ToolStats(rs.getString("tool"), calls, rs.getLong("errors"), rs.getLong("hits"),
                            calls == 0 ? 0 : rs.getLong("ms") / calls, p95(buckets));
                })
                .list();
        Totals totals = new Totals(tools.stream().mapToLong(ToolStats::calls).sum(),
                tools.stream().mapToLong(ToolStats::errors).sum(), tools.stream().mapToLong(ToolStats::cacheHits).sum());
        List<ClientStats> clients = jdbc.sql("""
                        SELECT m.client_id, coalesce(c.owner, '') AS owner, sum(m.calls) AS calls, sum(m.errors) AS errors
                        FROM audit_minute m LEFT JOIN api_clients c ON c.id = m.client_id
                        WHERE m.minute >= date_trunc('minute', CAST(:since AS timestamptz))
                        GROUP BY m.client_id, c.owner ORDER BY calls DESC LIMIT :top
                        """)
                .param("since", since).param("top", topClients)
                .query((rs, n) -> new ClientStats(rs.getString("client_id"), rs.getString("owner"), rs.getLong("calls"), rs.getLong("errors")))
                .list();

        ZoneId zone = ZoneId.of("Asia/Tomsk");
        ZonedDateTime nowZoned = to.atZone(zone);
        int hours = (int) Math.max(1, window.toHours());
        boolean hourly = hours <= 24;
        int points = hourly ? 24 : (int) Math.ceil(hours / 24.0);
        String step = hourly ? "1 hour" : "1 day";
        String truncUnit = hourly ? "hour" : "day";
        ZonedDateTime endZoned = hourly
                ? nowZoned.truncatedTo(ChronoUnit.HOURS)
                : nowZoned.truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime startZoned = hourly
                ? endZoned.minusHours(points - 1)
                : endZoned.minusDays(points - 1);
        Timestamp startTs = Timestamp.from(startZoned.toInstant());
        Timestamp endTs = Timestamp.from(endZoned.toInstant());

        List<SeriesPoint> series = jdbc.sql("""
                        WITH buckets AS (
                            SELECT generate_series(
                                CAST(:startTs AS timestamptz),
                                CAST(:endTs AS timestamptz),
                                CAST(:step AS interval)
                            ) AS at
                        ),
                        agg AS (
                            SELECT date_trunc(CAST(:truncUnit AS text), minute, 'Asia/Tomsk') AS at,
                                   sum(calls) AS calls,
                                   sum(errors) AS errors
                            FROM audit_minute
                            WHERE minute >= CAST(:startTs AS timestamptz)
                            GROUP BY 1
                        )
                        SELECT b.at,
                               coalesce(a.calls, 0) AS calls,
                               coalesce(a.errors, 0) AS errors
                        FROM buckets b
                        LEFT JOIN agg a ON b.at = a.at
                        ORDER BY b.at ASC
                        """)
                .param("startTs", startTs)
                .param("endTs", endTs)
                .param("step", step)
                .param("truncUnit", truncUnit)
                .query((rs, n) -> new SeriesPoint(
                        rs.getTimestamp("at").toInstant(),
                        rs.getLong("calls"),
                        rs.getLong("errors")))
                .list();

        return new Overview(from, to, totals, tools, clients, series);
    }

    /** Верхняя граница корзины, в которую попадает 95-й процентиль; {@code null} — вызовов нет или дольше 30 с. */
    static Long p95(long[] buckets) {
        if (buckets == null) {
            return null;
        }
        long total = 0;
        for (long b : buckets) {
            total += b;
        }
        if (total == 0) {
            return null;
        }
        long need = (long) Math.ceil(total * 0.95);
        long seen = 0;
        for (int i = 0; i < buckets.length; i++) {
            seen += buckets[i];
            if (seen >= need) {
                return i < AuditLog.LATENCY_BUCKETS_MS.length ? (long) AuditLog.LATENCY_BUCKETS_MS[i] : null;
            }
        }
        return null;
    }
}
