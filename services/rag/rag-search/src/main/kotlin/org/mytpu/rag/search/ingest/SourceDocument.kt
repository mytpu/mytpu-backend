package org.mytpu.rag.search.ingest

import java.time.Instant

/** Документ из источника до нарезки: разделы по заголовкам. */
data class SourceDocument(
    val url: String,
    val title: String,
    val updatedAt: Instant,
    val sections: List<SectionText>,
)

/** @param path путь заголовков: «Раздел › Подраздел»; у документа без разделов — его заголовок */
data class SectionText(val path: String, val text: String)

/** Кусок, готовый к записи: текст с контекстным заголовком «Документ › Раздел». */
data class ChunkText(val heading: String, val text: String, val tokens: Int)
