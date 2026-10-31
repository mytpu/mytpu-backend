package org.mytpu.rag.app.admin

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Документы базы знаний: поиск по названию и адресу, фильтр по источнику и состоянию, просмотр разделов и кусков,
 * уровень доступа (`public` / `students` / `staff`; пока поиск отдаёт только `public`), выключение и удаление —
 * документ уходит из поиска сразу (поиск фильтрует по состоянию в том же запросе).
 */
@ConditionalOnWebApplication
@RestController
class DocumentsController(private val jdbc: JdbcClient) {

    data class DocumentRow(
        val id: Long, val sourceId: Long, val title: String, val url: String, val access: String, val status: String,
        val sections: Long, val chunks: Long, val updatedAt: Instant, val indexedAt: Instant,
    )

    data class Chunk(val ord: Int, val heading: String, val text: String, val tokens: Int)

    data class Section(val id: String, val ord: Int, val path: String, val text: String, val tokens: Int, val chunks: List<Chunk>)

    data class Change(val status: String? = null, val access: String? = null)

    @GetMapping("/admin/documents")
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) source: Long?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<DocumentRow> {
        val where = StringBuilder("WHERE true")
        val params = mutableMapOf<String, Any>("limit" to limit.coerceIn(1, 200))
        if (!q.isNullOrBlank()) {
            where.append(" AND (d.title ILIKE :q OR d.url ILIKE :q)")
            params["q"] = "%" + q.trim().take(200).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        source?.let { where.append(" AND d.source_id = :source"); params["source"] = it }
        if (!status.isNullOrBlank()) { where.append(" AND d.status = :status"); params["status"] = status }
        before?.let { where.append(" AND d.id < :before"); params["before"] = it }
        var spec = jdbc.sql(
            """
            SELECT d.*, (SELECT count(*) FROM sections s WHERE s.document_id = d.id) AS n_sections,
                   (SELECT count(*) FROM sections s JOIN chunks c ON c.section_id = s.id WHERE s.document_id = d.id) AS n_chunks
            FROM documents d $where ORDER BY d.id DESC LIMIT :limit
            """,
        )
        params.forEach { (k, v) -> spec = spec.param(k, v) }
        return spec.query { rs, _ -> row(rs) }.list()
    }

    @GetMapping("/admin/documents/{id}")
    fun view(@PathVariable id: Long): Map<String, Any> {
        val document = one(id)
        val chunks = jdbc.sql(
            "SELECT c.section_id, c.ord, c.heading, c.text, c.tokens FROM chunks c JOIN sections s ON s.id = c.section_id "
                + "WHERE s.document_id = :id ORDER BY s.ord, c.ord",
        ).param("id", id).query { rs, _ -> rs.getString("section_id") to Chunk(rs.getInt("ord"), rs.getString("heading"), rs.getString("text"), rs.getInt("tokens")) }
            .list().groupBy({ it.first }, { it.second })
        val sections = jdbc.sql("SELECT id, ord, path, text, tokens FROM sections WHERE document_id = :id ORDER BY ord")
            .param("id", id)
            .query { rs, _ ->
                Section(rs.getString("id"), rs.getInt("ord"), rs.getString("path"), rs.getString("text"), rs.getInt("tokens"),
                    chunks[rs.getString("id")].orEmpty())
            }.list()
        return mapOf("document" to document, "sections" to sections)
    }

    @PutMapping("/admin/documents/{id}")
    fun change(@PathVariable id: Long, @RequestBody change: Change): DocumentRow {
        one(id)
        change.status?.let {
            if (it !in setOf("active", "disabled")) throw AdminProblem.badRequest("status_unknown")
            jdbc.sql("UPDATE documents SET status = :s WHERE id = :id").param("id", id).param("s", it).update()
        }
        change.access?.let {
            if (it !in setOf("public", "students", "staff")) throw AdminProblem.badRequest("access_unknown")
            jdbc.sql("UPDATE documents SET access = :a WHERE id = :id").param("id", id).param("a", it).update()
        }
        return one(id)
    }

    @DeleteMapping("/admin/documents/{id}")
    fun delete(@PathVariable id: Long): Map<String, Any> {
        one(id)
        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", id).update()
        return mapOf("id" to id, "deleted" to true)
    }

    private fun one(id: Long): DocumentRow = jdbc.sql(
        """
        SELECT d.*, (SELECT count(*) FROM sections s WHERE s.document_id = d.id) AS n_sections,
               (SELECT count(*) FROM sections s JOIN chunks c ON c.section_id = s.id WHERE s.document_id = d.id) AS n_chunks
        FROM documents d WHERE d.id = :id
        """,
    ).param("id", id).query { rs, _ -> row(rs) }.optional().orElseThrow { AdminProblem.notFound("document_not_found", id) }

    private fun row(rs: java.sql.ResultSet) = DocumentRow(
        rs.getLong("id"), rs.getLong("source_id"), rs.getString("title"), rs.getString("url"), rs.getString("access"),
        rs.getString("status"), rs.getLong("n_sections"), rs.getLong("n_chunks"), rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("indexed_at").toInstant(),
    )
}
