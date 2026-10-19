package org.mytpu.rag.search.ingest

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp

/** Разделы документа вместе с кусками и их векторами — заменяются целиком в одной транзакции. */
data class StoredSection(val id: String, val path: String, val text: String, val tokens: Int, val chunks: List<StoredChunk>)

data class StoredChunk(val chunk: ChunkText, val vector: FloatArray)

class DocumentStore(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
) {

    fun ensureSource(kind: String, title: String, address: String): Long =
        jdbc.sql(
            """
            INSERT INTO sources (kind, title, address) VALUES (:kind, :title, :address)
            ON CONFLICT (kind, address) DO UPDATE SET title = EXCLUDED.title
            RETURNING id
            """,
        ).param("kind", kind).param("title", title).param("address", address)
            .query(Long::class.java).single()

    fun contentHash(url: String): String? =
        jdbc.sql("SELECT content_hash FROM documents WHERE url = :url")
            .param("url", url).query(String::class.java).optional().orElse(null)

    fun hasEmbeddings(url: String, modelVersion: String): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM documents d
                JOIN sections s ON s.document_id = d.id
                JOIN chunks c ON c.section_id = s.id
                JOIN embeddings e ON e.chunk_id = c.id AND e.model_version = :version
                WHERE d.url = :url)
            """,
        ).param("url", url).param("version", modelVersion).query(Boolean::class.java).single()

    fun replace(sourceId: Long, doc: SourceDocument, contentHash: String, sections: List<StoredSection>, modelVersion: String) {
        tx.executeWithoutResult {
            val documentId = jdbc.sql(
                """
                INSERT INTO documents (source_id, url, title, content_hash, updated_at)
                VALUES (:source, :url, :title, :hash, :updated)
                ON CONFLICT (url) DO UPDATE SET source_id = EXCLUDED.source_id, title = EXCLUDED.title,
                    content_hash = EXCLUDED.content_hash, updated_at = EXCLUDED.updated_at, indexed_at = now()
                RETURNING id
                """,
            ).param("source", sourceId).param("url", doc.url).param("title", doc.title)
                .param("hash", contentHash).param("updated", Timestamp.from(doc.updatedAt))
                .query(Long::class.java).single()

            jdbc.sql("DELETE FROM sections WHERE document_id = :doc").param("doc", documentId).update()
            sections.forEachIndexed { ord, section -> insertSection(documentId, ord, section, modelVersion) }
        }
    }

    /** Удаляет документы источника, которых больше нет в нём; куски и векторы уходят каскадом. */
    fun removeMissing(sourceId: Long, presentUrls: Set<String>): Int =
        jdbc.sql("DELETE FROM documents WHERE source_id = :source AND NOT (url = ANY(:urls))")
            .param("source", sourceId).param("urls", presentUrls.toTypedArray())
            .update()

    fun counts(modelVersion: String): Map<String, Long> =
        jdbc.sql(
            """
            SELECT (SELECT count(*) FROM documents) AS documents,
                   (SELECT count(*) FROM sections) AS sections,
                   (SELECT count(*) FROM chunks) AS chunks,
                   (SELECT count(*) FROM embeddings WHERE model_version = :version) AS embeddings
            """,
        ).param("version", modelVersion).query { rs, _ ->
            linkedMapOf(
                "documents" to rs.getLong("documents"),
                "sections" to rs.getLong("sections"),
                "chunks" to rs.getLong("chunks"),
                "embeddings" to rs.getLong("embeddings"),
            )
        }.single()

    private fun insertSection(documentId: Long, ord: Int, section: StoredSection, modelVersion: String) {
        jdbc.sql(
            "INSERT INTO sections (id, document_id, ord, path, text, tokens) VALUES (:id, :doc, :ord, :path, :text, :tokens)",
        ).param("id", section.id).param("doc", documentId).param("ord", ord)
            .param("path", section.path).param("text", section.text).param("tokens", section.tokens)
            .update()

        section.chunks.forEachIndexed { chunkOrd, stored ->
            val chunkId = jdbc.sql(
                """
                INSERT INTO chunks (section_id, ord, heading, text, tokens)
                VALUES (:section, :ord, :heading, :text, :tokens) RETURNING id
                """,
            ).param("section", section.id).param("ord", chunkOrd).param("heading", stored.chunk.heading)
                .param("text", stored.chunk.text).param("tokens", stored.chunk.tokens)
                .query(Long::class.java).single()

            jdbc.sql(
                "INSERT INTO embeddings (chunk_id, model_version, embedding) VALUES (:chunk, :version, CAST(:vector AS halfvec))",
            ).param("chunk", chunkId).param("version", modelVersion).param("vector", vectorLiteral(stored.vector))
                .update()
        }
    }

    companion object {
        fun vectorLiteral(vector: FloatArray): String = vector.joinToString(",", "[", "]")
    }
}
