package org.mytpu.rag.search.files

import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.mytpu.rag.search.ingest.SectionText

/** Чтение форматов: каждое — с лимитами, без загрузки всего файла в память там, где это возможно. */
internal object FormatReaders {

    /** PDF: раздел на страницу; промежуточные буферы PDFBox — во временных файлах, а не в куче. */
    fun pdf(file: Path, limits: FileParser.Limits): FileParser.Parsed {
        try {
            Loader.loadPDF(RandomAccessReadBufferedFile(file.toFile()), null, null, null, IOUtils.createTempFileOnlyStreamCache()).use { doc ->
                if (doc.isEncrypted) throw FileRejected("PDF зашифрован")
                if (doc.numberOfPages > limits.maxPages) throw FileRejected("В PDF больше ${limits.maxPages} страниц")
                val stripper = PDFTextStripper()
                val sections = (1..doc.numberOfPages).map { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    SectionText("Страница $page", stripper.getText(doc))
                }
                return FileParser.Parsed(doc.documentInformation?.title, sections)
            }
        } catch (e: FileRejected) {
            throw e
        } catch (e: java.io.IOException) {
            throw FileRejected("PDF не читается: ${e.javaClass.simpleName}")
        }
    }

    /** DOCX: абзацы `word/document.xml`; абзац со стилем заголовка начинает раздел. */
    fun docx(file: Path, limits: FileParser.Limits): FileParser.Parsed {
        try {
            ZipFile(file.toFile()).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: throw FileRejected("Это не DOCX: нет word/document.xml")
                zip.getInputStream(entry).use { return docxXml(Bounded(it, limits.maxDocxXmlBytes)) }
            }
        } catch (e: FileRejected) {
            throw e
        } catch (e: Exception) {
            throw FileRejected("DOCX не читается: ${e.javaClass.simpleName}")
        }
    }

    private fun docxXml(input: InputStream): FileParser.Parsed {
        val factory = XMLInputFactory.newFactory().apply {
            // Внешние сущности и DTD не разбираются (XXE)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val reader = factory.createXMLStreamReader(input)
        val sections = mutableListOf<SectionText>()
        var heading = "Текст"
        var title: String? = null
        val body = StringBuilder()
        val paragraph = StringBuilder()
        var isHeading = false
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "p" -> { paragraph.clear(); isHeading = false }
                    "pStyle" -> {
                        val style = reader.getAttributeValue(reader.getNamespaceURI("w"), "val") ?: reader.getAttributeValue(null, "val") ?: ""
                        isHeading = style.startsWith("Heading", true) || style.startsWith("Заголовок", true) || style == "Title"
                    }
                    "t" -> paragraph.append(reader.elementText)
                    "tab" -> paragraph.append('\t')
                    "br" -> paragraph.append('\n')
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "p") {
                    val text = paragraph.toString().trim()
                    if (isHeading && text.isNotEmpty()) {
                        if (body.isNotBlank()) sections += SectionText(heading, body.toString())
                        body.clear()
                        heading = text
                        if (title == null) title = text
                    } else if (text.isNotEmpty()) {
                        body.append(text).append('\n')
                    }
                }
            }
        }
        if (body.isNotBlank()) sections += SectionText(heading, body.toString())
        return FileParser.Parsed(title, sections)
    }

    /** HTML: без скриптов, стилей и навигации; разделы по h1–h3. */
    fun html(text: String, limits: FileParser.Limits): FileParser.Parsed {
        val doc = Jsoup.parse(text.take(limits.maxChars * 2))
        doc.select("script, style, noscript, nav, header, footer, form, iframe, svg").remove()
        val sections = mutableListOf<SectionText>()
        var heading = doc.title().ifBlank { "Текст" }
        val body = StringBuilder()
        for (element in doc.body()?.select("h1, h2, h3, p, li, td, pre, blockquote") ?: emptyList()) {
            if (element.tagName() in setOf("h1", "h2", "h3")) {
                if (body.isNotBlank()) sections += SectionText(heading, body.toString())
                body.clear()
                heading = element.text()
            } else if (element.parents().none { it.tagName() in setOf("li", "td", "blockquote") && it != element }) {
                body.append(element.text()).append('\n')
            }
        }
        if (body.isNotBlank()) sections += SectionText(heading, body.toString())
        return FileParser.Parsed(doc.title().ifBlank { null }, sections)
    }

    /** Поток, который обрывается после [max] байт. */
    private class Bounded(private val input: InputStream, private val max: Long) : InputStream() {
        private var read = 0L
        override fun read(): Int {
            val b = input.read()
            if (b >= 0 && ++read > max) throw FileRejected("Распакованный документ больше ${max / 1024 / 1024} МБ")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = input.read(b, off, len)
            if (n > 0) {
                read += n
                if (read > max) throw FileRejected("Распакованный документ больше ${max / 1024 / 1024} МБ")
            }
            return n
        }
    }
}
