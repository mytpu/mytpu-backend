package org.mytpu.rag.app.admin

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.Executors
import org.mytpu.rag.search.RagMigrations
import org.mytpu.rag.search.RagProperties
import org.mytpu.rag.search.files.FileParser
import org.mytpu.rag.search.files.FileRejected
import org.mytpu.rag.search.ingest.CorpusIngest
import org.mytpu.rag.search.ingest.DocumentStore
import org.mytpu.rag.search.ingest.SectionText
import org.mytpu.rag.search.privacy.Redactor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Загрузка из консоли — в фоне внутри rag, одна задача за раз: разбор файла → [Redactor] → нарезка и векторы →
 * замена документа источника. Поиск во время загрузки отвечает (модель общая, загрузка — один поток).
 * После перезапуска незавершённые задачи помечаются прерванными, ждавшие — запускаются снова.
 */
@Component
@ConditionalOnWebApplication
class IngestJobs(
    private val jdbc: JdbcClient,
    private val files: AdminFiles,
    private val parser: FileParser,
    private val redactor: Redactor,
    private val ingest: CorpusIngest,
    private val store: DocumentStore,
    private val texts: AdminTexts,
    private val json: JsonMapper,
    private val properties: RagProperties,
    // Таблицы задач создаёт миграция rag — до неё восстанавливать нечего
    @Suppress("unused") migrations: RagMigrations,
) {
    private val log = LoggerFactory.getLogger(IngestJobs::class.java)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "rag-admin-ingest").apply { isDaemon = true } }
    private val parsers = Executors.newCachedThreadPool { Thread(it, "rag-admin-parse").apply { isDaemon = true } }

    data class Job(
        val id: Long, val sourceId: Long, val kind: String, val status: String, val log: String, val redactions: Map<String, Int>,
        val chunks: Int?, val createdBy: String?, val createdAt: Instant, val startedAt: Instant?, val finishedAt: Instant?,
    )

    @PostConstruct
    fun recover() {
        jdbc.sql("UPDATE ingest_jobs SET status = 'failed', finished_at = now(), log = log || :line WHERE status = 'running'")
            .param("line", texts.text("job.interrupted") + "\n").update()
        jdbc.sql("SELECT id FROM ingest_jobs WHERE status = 'queued' ORDER BY id").query(Long::class.java).list()
            .forEach { id -> executor.execute { run(id!!) } }
    }

    @PreDestroy
    fun stop() {
        executor.shutdownNow()
        parsers.shutdownNow()
    }

    fun enqueue(sourceId: Long, kind: String, actor: String?): Long {
        val id = jdbc.sql(
            "INSERT INTO ingest_jobs (source_id, kind, created_by, log) VALUES (:source, :kind, :actor, :log) RETURNING id",
        ).param("source", sourceId).param("kind", kind).param("actor", actor).param("log", texts.text("job.queued") + "\n")
            .query(Long::class.java).single()
        executor.execute { run(id) }
        return id
    }

    fun list(limit: Int): List<Job> = jdbc.sql("SELECT * FROM ingest_jobs ORDER BY id DESC LIMIT :limit")
        .param("limit", limit.coerceIn(1, 200)).query { rs, _ -> map(rs) }.list()

    fun get(id: Long): Job = jdbc.sql("SELECT * FROM ingest_jobs WHERE id = :id").param("id", id).query { rs, _ -> map(rs) }
        .optional().orElseThrow { AdminProblem.notFound("job_not_found", id) }

    private fun run(jobId: Long) {
        val job = get(jobId)
        jdbc.sql("UPDATE ingest_jobs SET status = 'running', started_at = now() WHERE id = :id").param("id", jobId).update()
        try {
            val source = jdbc.sql(
                "SELECT s.kind, s.title, s.address, f.name, f.stored_path FROM sources s JOIN files f ON f.source_id = s.id WHERE s.id = :id",
            ).param("id", job.sourceId).query { rs, _ ->
                listOf(rs.getString("kind"), rs.getString("title"), rs.getString("address"), rs.getString("name"), rs.getString("stored_path"))
            }.optional().orElseThrow { FileRejected(texts.text("admin.source_no_file", job.sourceId)) }
            val (kind, title, address, name, stored) = source
            append(jobId, texts.text("job.started", name))
            val counts = linkedMapOf<String, Int>()
            fun clean(text: String) = redactor.redact(text).also { r -> r.counts.forEach { (k, v) -> counts.merge(k, v, Int::plus) } }.text
            // Адрес документа уходит в выдачу поиска: имя файла — тоже через Redactor (ФИО в имени файла не утекает)
            val url = if (kind == "site") address else "upload:${job.sourceId}/${clean(name)}"
            // Название не задано при загрузке (совпадает с именем файла) — берётся из документа: заголовок, title PDF
            val given = title.takeIf { it != name.substringBeforeLast('.') && it != address }
            val parsed = parseWithin(PARSE_TIMEOUT_SECONDS) { parser.parse(files.path(stored), name, url, given) }
            append(jobId, texts.text("job.parsed", parsed.sections.size, parsed.sections.sumOf { it.text.length }))
            val document = parsed.copy(title = clean(parsed.title), sections = parsed.sections.map { SectionText(clean(it.path), clean(it.text)) })
            append(jobId, texts.text("job.redacted", counts.values.sum()))
            val chunks = ingest.ingestDocument(job.sourceId, document, properties.model.backgroundBatchSize)
            if (given == null) {
                jdbc.sql("UPDATE sources SET title = :title WHERE id = :id").param("id", job.sourceId).param("title", document.title).update()
            }
            store.removeMissing(job.sourceId, setOf(document.url))
            jdbc.sql(
                "UPDATE ingest_jobs SET status = 'done', finished_at = now(), chunks = :chunks, redactions = CAST(:counts AS jsonb), log = log || :line WHERE id = :id",
            ).param("id", jobId).param("chunks", chunks).param("counts", json.writeValueAsString(counts))
                .param("line", texts.text("job.done", chunks) + "\n").update()
        } catch (e: Exception) {
            val reason = if (e is FileRejected) e.message ?: "?" else e.javaClass.simpleName
            log.warn("Задача загрузки {} не выполнена: {}", jobId, reason)
            jdbc.sql("UPDATE ingest_jobs SET status = 'failed', finished_at = now(), log = log || :line WHERE id = :id")
                .param("id", jobId).param("line", texts.text("job.failed", reason) + "\n").update()
        } finally {
            // Куча, выросшая на большом документе, возвращается системе (Serial GC сжимает её после сборки)
            System.gc()
        }
    }

    /**
     * Разбор с лимитом времени: зависший PDF не держит единственный поток загрузки. Поток разбора прерывается;
     * если библиотека прерывание не слышит, он доживает сам (daemon), а задача уже помечена ошибкой.
     */
    private fun <T> parseWithin(seconds: Long, block: () -> T): T {
        val future = parsers.submit<T> { block() }
        try {
            return future.get(seconds, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            throw FileRejected(texts.text("job.parse_timeout", seconds))
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause as? Exception ?: e
        }
    }

    private fun append(jobId: Long, line: String) {
        jdbc.sql("UPDATE ingest_jobs SET log = left(log || :line, 4000) WHERE id = :id").param("id", jobId).param("line", "$line\n").update()
    }

    private fun map(rs: java.sql.ResultSet) = Job(
        rs.getLong("id"), rs.getLong("source_id"), rs.getString("kind"), rs.getString("status"), rs.getString("log"),
        json.readValue(rs.getString("redactions"), object : tools.jackson.core.type.TypeReference<Map<String, Int>>() {}),
        rs.getObject("chunks") as Int?, rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("started_at")?.toInstant(), rs.getTimestamp("finished_at")?.toInstant(),
    )

    private companion object {
        const val PARSE_TIMEOUT_SECONDS = 120L
    }
}
