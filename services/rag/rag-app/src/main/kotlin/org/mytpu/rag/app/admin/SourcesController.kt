package org.mytpu.rag.app.admin

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Источники консоли rag: файл (PDF, DOCX, HTML, MD, TXT до 20 МБ) или страница `*.tpu.ru` — её HTML скачивает
 * шлюз (у rag нет выхода в интернет) и передаёт сюда. Загрузка — задачей [IngestJobs]; выключить источник —
 * его документы сразу уходят из поиска; удалить — вместе с документами и файлом.
 */
@ConditionalOnWebApplication
@RestController
class SourcesController(
    private val jdbc: JdbcClient,
    transactions: PlatformTransactionManager,
    private val files: AdminFiles,
    private val jobs: IngestJobs,
) {
    private val tx = TransactionTemplate(transactions)

    data class SourceView(
        val id: Long, val kind: String, val title: String, val address: String, val enabled: Boolean,
        val documents: Long, val chunks: Long, val fileSize: Long?, val lastJob: String?, val createdAt: Instant,
    )

    data class Page(val url: String = "", val title: String? = null, val html: String = "")

    data class State(val enabled: Boolean? = null)

    @GetMapping("/admin/sources")
    fun list(): List<SourceView> = jdbc.sql(
        """
        SELECT s.id, s.kind, s.title, s.address, s.enabled, s.created_at, f.size,
               (SELECT count(*) FROM documents d WHERE d.source_id = s.id) AS documents,
               (SELECT count(*) FROM documents d JOIN sections x ON x.document_id = d.id JOIN chunks c ON c.section_id = x.id
                WHERE d.source_id = s.id) AS chunks,
               (SELECT status FROM ingest_jobs j WHERE j.source_id = s.id ORDER BY j.id DESC LIMIT 1) AS last_job
        FROM sources s LEFT JOIN files f ON f.source_id = s.id ORDER BY s.id DESC
        """,
    ).query { rs, _ ->
        SourceView(
            rs.getLong("id"), rs.getString("kind"), rs.getString("title"), rs.getString("address"), rs.getBoolean("enabled"),
            rs.getLong("documents"), rs.getLong("chunks"), rs.getObject("size") as Long?, rs.getString("last_job"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }.list()

    @PostMapping("/admin/sources/files")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun upload(
        @RequestParam("file") file: MultipartFile?,
        @RequestParam("title", required = false) title: String?,
        @RequestHeader("X-Admin-Actor", required = false) actor: String?,
    ): Map<String, Long> {
        if (file == null || file.isEmpty) throw AdminProblem.badRequest("file_required", AdminFiles.MAX_MB)
        val stored = file.inputStream.use { files.store(it, file.originalFilename ?: "file") }
        return create("file", title?.takeIf { it.isNotBlank() } ?: stored.name.substringBeforeLast('.'), "upload/" + stored.relative, stored, actor)
    }

    @PostMapping("/admin/sources/page")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun page(@RequestBody page: Page, @RequestHeader("X-Admin-Actor", required = false) actor: String?): Map<String, Long> {
        val host = runCatching { URI.create(page.url) }.getOrNull()?.takeIf { it.scheme == "https" }?.host?.lowercase()
        if (host == null || !(host == "tpu.ru" || host.endsWith(".tpu.ru"))) throw AdminProblem.badRequest("page_url")
        val bytes = page.html.toByteArray()
        if (bytes.isEmpty() || bytes.size > PAGE_MAX_MB * 1024 * 1024) throw AdminProblem.badRequest("page_html", PAGE_MAX_MB)
        val stored = files.store(ByteArrayInputStream(bytes), "page.html")
        val existing = jdbc.sql("SELECT id FROM sources WHERE kind = 'site' AND address = :url").param("url", page.url)
            .query(Long::class.java).optional()
        if (existing.isPresent) {
            val id = existing.get()
            jdbc.sql("SELECT stored_path FROM files WHERE source_id = :id").param("id", id).query(String::class.java).optional()
                .ifPresent { files.delete(it) }
            jdbc.sql("UPDATE files SET name = :name, size = :size, sha256 = :sha, stored_path = :path WHERE source_id = :id")
                .param("id", id).param("name", stored.name).param("size", stored.size).param("sha", stored.sha256)
                .param("path", stored.relative).update()
            return mapOf("sourceId" to id, "jobId" to jobs.enqueue(id, "reindex", actor))
        }
        return create("site", page.title?.takeIf { it.isNotBlank() } ?: page.url, page.url, stored, actor)
    }

    @PostMapping("/admin/sources/{id}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun reindex(@PathVariable id: Long, @RequestHeader("X-Admin-Actor", required = false) actor: String?): Map<String, Long> {
        existing(id)
        jdbc.sql("SELECT count(*) FROM files WHERE source_id = :id").param("id", id).query(Long::class.java).single()
            .takeIf { it > 0 } ?: throw AdminProblem.badRequest("source_no_file", id)
        return mapOf("sourceId" to id, "jobId" to jobs.enqueue(id, "reindex", actor))
    }

    @PutMapping("/admin/sources/{id}/state")
    fun state(@PathVariable id: Long, @RequestBody state: State): Map<String, Any> {
        existing(id)
        val enabled = state.enabled ?: throw AdminProblem.badRequest("bad_request")
        tx.executeWithoutResult {
            jdbc.sql("UPDATE sources SET enabled = :on WHERE id = :id").param("id", id).param("on", enabled).update()
            jdbc.sql("UPDATE documents SET status = :status WHERE source_id = :id").param("id", id)
                .param("status", if (enabled) "active" else "disabled").update()
        }
        return mapOf("id" to id, "enabled" to enabled)
    }

    @DeleteMapping("/admin/sources/{id}")
    fun delete(@PathVariable id: Long): Map<String, Any> {
        existing(id)
        val stored = jdbc.sql("SELECT stored_path FROM files WHERE source_id = :id").param("id", id).query(String::class.java).optional()
        jdbc.sql("DELETE FROM sources WHERE id = :id").param("id", id).update()
        stored.ifPresent { files.delete(it) }
        return mapOf("id" to id, "deleted" to true)
    }

    @GetMapping("/admin/jobs")
    fun jobs(@RequestParam(defaultValue = "50") limit: Int) = jobs.list(limit)

    @GetMapping("/admin/jobs/{id}")
    fun job(@PathVariable id: Long) = jobs.get(id)

    private fun create(kind: String, title: String, address: String, stored: AdminFiles.Stored, actor: String?): Map<String, Long> {
        val id = tx.execute {
            val source = jdbc.sql("INSERT INTO sources (kind, title, address) VALUES (:kind, :title, :address) RETURNING id")
                .param("kind", kind).param("title", title.take(300)).param("address", address).query(Long::class.java).single()
            jdbc.sql("INSERT INTO files (source_id, name, size, sha256, stored_path) VALUES (:id, :name, :size, :sha, :path)")
                .param("id", source).param("name", stored.name).param("size", stored.size).param("sha", stored.sha256)
                .param("path", stored.relative).update()
            source
        }!!
        return mapOf("sourceId" to id, "jobId" to jobs.enqueue(id, "upload", actor))
    }

    private fun existing(id: Long) {
        if (jdbc.sql("SELECT count(*) FROM sources WHERE id = :id").param("id", id).query(Long::class.java).single() == 0L) {
            throw AdminProblem.notFound("source_not_found", id)
        }
    }

    private companion object {
        const val PAGE_MAX_MB = 5
    }
}
