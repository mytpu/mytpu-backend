package org.mytpu.rag.app.admin

import org.mytpu.rag.api.SearchFilter
import org.mytpu.rag.search.RagProperties
import org.mytpu.rag.search.search.Candidate
import org.mytpu.rag.search.search.HybridDocsSearch
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Песочница и обзор консоли rag. Песочница: все кандидаты с оценкой по векторам, совпадением по тексту и местом RRF,
 * пороги и причина «не нашлось». Запрос песочницы в журнал запросов не пишется — это не вопрос пользователя.
 */
@RestController
class PlaygroundController(
    private val search: HybridDocsSearch,
    private val properties: RagProperties,
    private val jdbc: JdbcClient,
) {
    data class Probe(val query: String = "", val access: String = "public")

    data class Thresholds(val minSimilarity: Double, val softSimilarity: Double, val minMargin: Double)

    data class Answer(
        val found: Boolean, val reason: String, val bestSimilarity: Double?, val margin: Double,
        val thresholds: Thresholds, val candidates: List<Candidate>,
    )

    @PostMapping("/admin/playground")
    fun probe(@RequestBody probe: Probe): Answer {
        if (probe.query.isBlank() || probe.query.length > 500) throw AdminProblem.badRequest("query_required")
        if (probe.access !in setOf("public", "students", "staff")) throw AdminProblem.badRequest("access_unknown")
        val s = properties.search
        val detailed = search.searchDetailed(probe.query, SearchFilter(null, probe.access, 5))
        val best = detailed.bestSimilarity
        val reason = when {
            detailed.result.found() -> "found"
            best == null -> "no_candidates"
            best < s.softSimilarity -> "below_soft_threshold"
            else -> "below_threshold_small_margin"
        }
        return Answer(detailed.result.found(), reason, best, detailed.margin,
            Thresholds(s.minSimilarity, s.softSimilarity, s.minMargin), detailed.candidates.take(30))
    }

    @GetMapping("/admin/overview")
    fun overview(): Map<String, Any?> {
        val version = properties.model.version
        val counts = jdbc.sql(
            """
            SELECT (SELECT count(*) FROM sources) AS sources, (SELECT count(*) FROM documents) AS documents,
                   (SELECT count(*) FROM documents WHERE status <> 'active') AS documents_off,
                   (SELECT count(*) FROM chunks) AS chunks,
                   (SELECT count(*) FROM embeddings WHERE model_version = :version) AS embeddings,
                   (SELECT max(indexed_at) FROM documents) AS last_indexed,
                   (SELECT count(*) FROM query_log WHERE NOT found AND at > now() - interval '7 days') AS misses_7d
            """,
        ).param("version", version).query { rs, _ ->
            mapOf(
                "sources" to rs.getLong("sources"), "documents" to rs.getLong("documents"), "documentsOff" to rs.getLong("documents_off"),
                "chunks" to rs.getLong("chunks"), "embeddings" to rs.getLong("embeddings"),
                "lastIndexed" to rs.getTimestamp("last_indexed")?.toInstant(), "misses7d" to rs.getLong("misses_7d"),
            )
        }.single()
        val index = jdbc.sql(
            """
            SELECT i.indexname, pg_relation_size(format('%I.%I', i.schemaname, i.indexname)::regclass) AS bytes
            FROM pg_indexes i WHERE i.tablename = 'embeddings' AND i.indexdef LIKE '%USING hnsw%'
              AND strpos(i.indexdef, :version) > 0
            """,
        ).param("version", "'$version'").query { rs, _ -> mapOf("name" to rs.getString("indexname"), "bytes" to rs.getLong("bytes")) }
            .list().firstOrNull()
        val eval = jdbc.sql("SELECT at, hit3, hit5, false_found, questions FROM eval_runs ORDER BY id DESC LIMIT 1")
            .query { rs, _ ->
                mapOf("at" to rs.getTimestamp("at").toInstant(), "hit3" to rs.getDouble("hit3"), "hit5" to rs.getDouble("hit5"),
                    "falseFound" to rs.getInt("false_found"), "questions" to rs.getInt("questions"))
            }.optional().orElse(null)
        return counts + mapOf("modelVersion" to version, "index" to index, "lastEval" to eval)
    }
}
