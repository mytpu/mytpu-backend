package org.mytpu.rag.search.files

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import org.mytpu.rag.search.ingest.SectionText
import org.mytpu.rag.search.ingest.SourceDocument

/** Файл не разобран: формат не поддерживается, файл битый или вышел за лимиты. Текст — для журнала задачи. */
class FileRejected(message: String) : RuntimeException(message)

/**
 * Файл из консоли rag → документ с разделами: PDF (по страницам), DOCX (по заголовкам), HTML (по h1–h3),
 * Markdown (по `#`), TXT (одним разделом). Лимиты — размер файла, число страниц и объём текста;
 * таймаут задачи — у вызывающего ([org.mytpu.rag.search.files] не держит потоков).
 */
class FileParser(private val limits: Limits = Limits()) {

    data class Limits(
        val maxBytes: Long = 20L * 1024 * 1024,
        val maxPages: Int = 300,
        val maxChars: Int = 2_000_000,
        /** Распакованный `word/document.xml` — защита от zip-бомбы. */
        val maxDocxXmlBytes: Long = 50L * 1024 * 1024,
    )

    fun parse(file: Path, name: String, url: String, title: String?): SourceDocument {
        val size = Files.size(file)
        if (size == 0L || size > limits.maxBytes) throw FileRejected("Файл пустой или больше ${limits.maxBytes / 1024 / 1024} МБ")
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val parsed = when (ext) {
            "pdf" -> FormatReaders.pdf(file, limits)
            "docx" -> FormatReaders.docx(file, limits)
            "html", "htm" -> FormatReaders.html(Files.readString(file), limits)
            "md", "markdown" -> markdown(Files.readString(file))
            "txt" -> Parsed(null, listOf(SectionText("Текст", Files.readString(file))))
            else -> throw FileRejected("Формат .$ext не поддерживается: PDF, DOCX, HTML, MD, TXT")
        }
        val sections = parsed.sections.map { SectionText(it.path.trim().take(300), clean(it.text)) }.filter { it.text.isNotBlank() }
        val chars = sections.sumOf { it.text.length }
        if (sections.isEmpty()) throw FileRejected("В файле не найден текст (скан без текстового слоя?)")
        if (chars > limits.maxChars) throw FileRejected("Текста больше ${limits.maxChars} символов — разделите файл")
        val docTitle = title?.takeIf { it.isNotBlank() } ?: parsed.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
        return SourceDocument(url, docTitle.trim().take(300), Instant.now(), sections)
    }

    /** Разобранный текст до общих правил (обрезка путей, пустые разделы). */
    data class Parsed(val title: String?, val sections: List<SectionText>)

    private fun markdown(text: String): Parsed {
        val sections = mutableListOf<SectionText>()
        var title: String? = null
        var heading = "Текст"
        val buffer = StringBuilder()
        for (line in text.lines()) {
            val m = HEADING.matchEntire(line)
            if (m != null) {
                if (buffer.isNotBlank()) sections += SectionText(heading, buffer.toString())
                buffer.clear()
                heading = m.groupValues[2].trim()
                if (title == null && m.groupValues[1].length == 1) title = heading
            } else {
                buffer.appendLine(line)
            }
        }
        if (buffer.isNotBlank()) sections += SectionText(heading, buffer.toString())
        return Parsed(title, sections)
    }

    private fun clean(text: String): String =
        text.replace(CONTROL, " ").replace(SPACES, " ").replace(BLANK_LINES, "\n\n").trim()

    private companion object {
        val HEADING = Regex("^(#{1,3})\\s+(.+)$")
        val CONTROL = Regex("[\\p{Cntrl}&&[^\\n\\t]]")
        val SPACES = Regex("[ \\t]{2,}")
        val BLANK_LINES = Regex("\\n{3,}")
    }
}
