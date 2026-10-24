package org.mytpu.rag.app.admin

import java.time.Instant
import org.mytpu.rag.search.embed.OnnxEmbedder
import org.mytpu.rag.search.privacy.Redactor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Журнал запросов поиска (`query_log`): текст — только после [Redactor] в режиме запроса (имена, любые номера, почта →
 * метки), хранится 30 дней. Отсюда — «Ненайденные запросы»: что спрашивали и чего нет в базе, сгруппировано по смыслу.
 */
@Component
@ConditionalOnWebApplication
class QueryLog(private val jdbc: JdbcClient, private val redactor: Redactor, private val embedder: OnnxEmbedder) {
    private val log = LoggerFactory.getLogger(QueryLog::class.java)

    data class MissGroup(val query: String, val count: Int, val examples: List<String>, val lastAt: Instant)

    fun record(query: String, found: Boolean, topScore: Double?) {
        try {
            val text = redactor.redact(query.take(MAX_QUERY), Redactor.Mode.QUERY).text
            jdbc.sql("INSERT INTO query_log (query, found, top_score) VALUES (:q, :found, :score)")
                .param("q", text).param("found", found).param("score", topScore?.toFloat()).update()
        } catch (e: RuntimeException) {
            // Журнал не должен ломать поиск
            log.warn("Запрос не записан в журнал: {}", e.javaClass.simpleName)
        }
    }

    @Scheduled(cron = "\${mytpu.rag.query-log.purge-cron:0 40 3 * * *}")
    fun purge() {
        val removed = jdbc.sql("DELETE FROM query_log WHERE at < now() - interval '30 days'").update()
        if (removed > 0) log.info("Из журнала запросов удалено {} записей старше 30 дней", removed)
    }

    /**
     * Ненайденное за [days] дней: одинаковые тексты складываются, затем похожие по смыслу (сходство векторов ≥ 0.9)
     * объединяются вокруг самого частого. Не больше [MAX_DISTINCT] разных текстов — векторы считаются на лету.
     */
    @Volatile
    private var cached: Pair<String, Pair<Long, List<MissGroup>>>? = null

    /** Тот же список не пересчитывается чаще раза в минуту: векторы запросов считаются на лету. */
    fun misses(days: Int, limit: Int): List<MissGroup> {
        val key = "$days|$limit"
        val now = System.currentTimeMillis()
        cached?.takeIf { it.first == key && now - it.second.first < CACHE_MILLIS }?.let { return it.second.second }
        return computeMisses(days, limit).also { cached = key to (now to it) }
    }

    private fun computeMisses(days: Int, limit: Int): List<MissGroup> {
        data class Distinct(val query: String, val count: Int, val lastAt: Instant)
        val distinct = jdbc.sql(
            """
            SELECT lower(btrim(query)) AS q, count(*) AS n, max(at) AS last_at FROM query_log
            WHERE NOT found AND at > now() - make_interval(days => :days)
            GROUP BY lower(btrim(query)) ORDER BY n DESC, last_at DESC LIMIT :max
            """,
        ).param("days", days.coerceIn(1, 30)).param("max", MAX_DISTINCT)
            .query { rs, _ -> Distinct(rs.getString("q"), rs.getInt("n"), rs.getTimestamp("last_at").toInstant()) }.list()
        val vectors = distinct.map { embedder.embedQuery(it.query) }
        val groups = mutableListOf<MutableList<Int>>()
        for (i in distinct.indices) {
            val home = groups.firstOrNull { g -> dot(vectors[g.first()], vectors[i]) >= SAME_MEANING }
            if (home != null) home += i else groups += mutableListOf(i)
        }
        return groups.map { g ->
            MissGroup(distinct[g.first()].query, g.sumOf { distinct[it].count }, g.take(5).map { distinct[it].query },
                g.maxOf { distinct[it].lastAt })
        }.sortedByDescending { it.count }.take(limit.coerceIn(1, 100))
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private companion object {
        const val MAX_QUERY = 500
        const val MAX_DISTINCT = 200
        const val SAME_MEANING = 0.9
        const val CACHE_MILLIS = 60_000L
    }
}
