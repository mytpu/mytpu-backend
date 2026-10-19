package org.mytpu.rag.search.ingest

import org.mytpu.rag.search.embed.OnnxEmbedder
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

data class IngestReport(
    val added: Int,
    val changed: Int,
    val unchanged: Int,
    val removed: Int,
    val chunks: Int,
    val millis: Long,
)

/**
 * Загрузка корпуса: чтение → сравнение хэша → нарезка → векторы → запись.
 * Неизменившиеся документы не пересчитываются; исчезнувшие из источника — удаляются.
 */
class CorpusIngest(
    private val reader: DatasetReader,
    private val chunker: Chunker,
    private val embedder: OnnxEmbedder,
    private val store: DocumentStore,
) {
    private val log = LoggerFactory.getLogger(CorpusIngest::class.java)

    fun ingestDataset(dir: Path): IngestReport {
        val started = System.currentTimeMillis()
        val sourceId = store.ensureSource("dataset", "Стартовый корпус хакатона", dir.fileName.toString())
        val documents = reader.read(dir)
        // Пустой источник (неверный путь, пустой том) не должен стереть весь корпус через removeMissing
        check(documents.isNotEmpty()) { "В источнике $dir нет документов — загрузка остановлена, корпус не тронут" }
        var added = 0
        var changed = 0
        var unchanged = 0
        var chunks = 0

        for (doc in documents) {
            val hash = sha256(doc.sections.joinToString("\u0000") { it.path + "\u0001" + it.text })
            val previous = store.contentHash(doc.url)
            // Пропуск — только если текст тот же И векторы текущей версии модели уже есть (смена модели пересчитывает всё)
            if (previous == hash && store.hasEmbeddings(doc.url, embedder.version)) {
                unchanged++
                continue
            }
            val sections = prepare(doc)
            store.replace(sourceId, doc, hash, sections, embedder.version)
            chunks += sections.sumOf { it.chunks.size }
            if (previous == null) added++ else changed++
            log.info("Загружен документ: {} (разделов {}, кусков {})", doc.url, sections.size, sections.sumOf { it.chunks.size })
        }

        val removed = store.removeMissing(sourceId, documents.map { it.url }.toSet())
        return IngestReport(added, changed, unchanged, removed, chunks, System.currentTimeMillis() - started)
    }

    private fun prepare(doc: SourceDocument): List<StoredSection> {
        val sectionChunks = doc.sections.map { section -> section to chunker.chunk(doc.title, section) }
        val vectors = embedder.embedPassages(sectionChunks.flatMap { (_, chunks) -> chunks.map { it.heading + "\n" + it.text } })
        var next = 0
        val seenPaths = mutableMapOf<String, Int>()
        return sectionChunks.map { (section, chunks) ->
            // Один заголовок может встретиться на странице дважды — номер повтора делает id уникальным и стабильным
            val repeat = seenPaths.merge(section.path, 1, Int::plus)!! - 1
            StoredSection(
                id = sha256(doc.url + "\u0000" + section.path + "\u0000" + repeat).take(32),
                path = section.path,
                text = section.text,
                tokens = embedder.countTokens(section.text),
                chunks = chunks.map { StoredChunk(it, vectors[next++]) },
            )
        }
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
