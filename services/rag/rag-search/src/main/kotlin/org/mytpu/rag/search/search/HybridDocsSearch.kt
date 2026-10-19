package org.mytpu.rag.search.search

import org.mytpu.rag.api.DocHit
import org.mytpu.rag.api.DocsSearch
import org.mytpu.rag.api.SearchFilter
import org.mytpu.rag.api.SearchResult
import org.mytpu.rag.search.RagProperties
import org.mytpu.rag.search.embed.OnnxEmbedder
import org.mytpu.rag.search.ingest.DocumentStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/** Итог поиска с отладкой: лучшее сходство нужно для замера порога и для песочницы консоли. */
data class DetailedResult(
    val result: SearchResult,
    val bestSimilarity: Double?,
    /** Отрыв лучшего куска от десятого по сходству: у постороннего вопроса все куски «одинаково похожи». */
    val margin: Double,
)

/**
 * Гибридный поиск документа 19: вектор (HNSW по halfvec) + полнотекст `russian`, склейка RRF в одном SQL.
 * «Нашлось ли» решает порог сходства, а не модель: слабой LLM не нужно гадать (docs/LLM.md).
 */
class HybridDocsSearch(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val embedder: OnnxEmbedder,
    private val model: RagProperties.Model,
    private val settings: RagProperties.Search,
) : DocsSearch {

    // Версия модели и размерность — в тексте запроса, а не параметрами: иначе частичный HNSW-индекс не используется
    private val sql: String

    init {
        require(VERSION.matches(model.version)) { "Недопустимая версия модели: ${model.version}" }
        sql = SEARCH_SQL.replace("{dims}", model.dimensions.toString()).replace("{version}", model.version)
    }

    override fun search(query: String, filter: SearchFilter?): SearchResult = searchDetailed(query, filter).result

    fun searchDetailed(query: String, filter: SearchFilter?): DetailedResult {
        val text = QueryText.normalize(query)
        if (text.isBlank()) return DetailedResult(SearchResult(false, emptyList()), null, 0.0)
        val limit = (filter?.limit() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val access = arrayOf(filter?.access() ?: "public")
        val vector = DocumentStore.vectorLiteral(embedder.embedQuery(text))
        val tsQuery = QueryText.orTsQuery(text)

        val rows = tx.execute {
            jdbc.sql("SET LOCAL hnsw.ef_search = 60").update()
            jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update()
            jdbc.sql(sql)
                .param("vec", vector)
                .param("tsq", tsQuery ?: "")
                .param("hasText", tsQuery != null)
                .param("pool", settings.pool)
                .param("k", settings.rrfK)
                .param("access", access)
                .query { rs, _ ->
                    Row(
                        sectionId = rs.getString("section_id"),
                        distance = rs.getObject("dist") as Double?,
                        inText = rs.getBoolean("in_txt"),
                        title = rs.getString("title"),
                        url = rs.getString("url"),
                        path = rs.getString("path"),
                        text = rs.getString("section_text"),
                        updatedAt = rs.getTimestamp("updated_at").toInstant(),
                    )
                }.list()
        }.orEmpty()

        val similarities = rows.mapNotNull { it.distance }.map { 1.0 - it }.sortedDescending()
        val best = similarities.firstOrNull()
        val margin = if (similarities.isEmpty()) 0.0 else best!! - similarities[minOf(MARGIN_RANK, similarities.size - 1)]
        val found = best != null &&
            (best >= settings.minSimilarity || (best >= settings.softSimilarity && margin >= settings.minMargin))
        val hits = if (!found) emptyList() else rows.distinctBy { it.sectionId }.take(limit).mapIndexed { i, row ->
            DocHit(i + 1, row.title, row.url, row.path, row.text.take(settings.maxSectionChars), row.updatedAt)
        }
        return DetailedResult(SearchResult(found, hits), best, margin)
    }

    private data class Row(
        val sectionId: String,
        val distance: Double?,
        val inText: Boolean,
        val title: String,
        val url: String,
        val path: String,
        val text: String,
        val updatedAt: java.time.Instant,
    )

    private companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 5
        const val MARGIN_RANK = 9
        val VERSION = Regex("[a-z0-9-]{1,64}")

        // Фильтр доступа — внутри отбора кандидатов (иначе закрытые документы занимали бы пул); вектор запроса —
        // прямо в ORDER BY, чтобы план мог взять частичный HNSW-индекс (hnsw.iterative_scan добирает при фильтре)
        val SEARCH_SQL = """
            WITH q AS (
                SELECT CASE WHEN :hasText THEN to_tsquery('russian', :tsq) END AS tq
            ),
            vec AS (
                SELECT e.chunk_id, (e.embedding::halfvec({dims})) <=> CAST(:vec AS halfvec({dims})) AS dist
                FROM embeddings e
                JOIN chunks c ON c.id = e.chunk_id
                JOIN sections s ON s.id = c.section_id
                JOIN documents d ON d.id = s.document_id
                WHERE e.model_version = '{version}' AND d.status = 'active' AND d.access = ANY(:access)
                ORDER BY (e.embedding::halfvec({dims})) <=> CAST(:vec AS halfvec({dims}))
                LIMIT :pool
            ),
            vec_ranked AS (
                SELECT chunk_id, dist, row_number() OVER (ORDER BY dist) AS r FROM vec
            ),
            txt AS (
                SELECT c.id AS chunk_id, row_number() OVER (ORDER BY ts_rank_cd(c.tsv, q.tq) DESC) AS r
                FROM chunks c
                JOIN sections s ON s.id = c.section_id
                JOIN documents d ON d.id = s.document_id, q
                WHERE q.tq IS NOT NULL AND c.tsv @@ q.tq AND d.status = 'active' AND d.access = ANY(:access)
                ORDER BY ts_rank_cd(c.tsv, q.tq) DESC
                LIMIT :pool
            ),
            fused AS (
                SELECT chunk_id, sum(score) AS score, min(dist) AS dist, bool_or(from_txt) AS in_txt
                FROM (
                    SELECT chunk_id, 1.0 / (:k + r) AS score, dist, false AS from_txt FROM vec_ranked
                    UNION ALL
                    SELECT chunk_id, 1.0 / (:k + r) AS score, NULL::float8 AS dist, true AS from_txt FROM txt
                ) u
                GROUP BY chunk_id
            )
            SELECT s.id AS section_id, f.dist, f.in_txt, d.title, d.url, s.path, s.text AS section_text, d.updated_at
            FROM fused f
            JOIN chunks c ON c.id = f.chunk_id
            JOIN sections s ON s.id = c.section_id
            JOIN documents d ON d.id = s.document_id
            WHERE d.status = 'active' AND d.access = ANY(:access)
            ORDER BY f.score DESC
        """.trimIndent()
    }
}
