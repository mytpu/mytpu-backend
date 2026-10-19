package org.mytpu.rag.search.ingest

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Стартовый корпус хакатона (`helpdesk-ai/data/raw`): статьи help.tpu.ru и страницы сайта ТПУ.
 * ПД в нём уже вырезаны (правила redact хакатона).
 */
class DatasetReader(private val mapper: JsonMapper = JsonMapper.builder().build()) {

    fun read(dir: Path): List<SourceDocument> = readHelpArticles(dir.resolve(HELP_FILE)) + readSitePages(dir.resolve(SITE_FILE))

    /** Статья — один раздел: заголовок статьи и её текст. */
    private fun readHelpArticles(file: Path): List<SourceDocument> {
        if (!Files.exists(file)) return emptyList()
        val root = mapper.readTree(file.toFile())
        val collectedAt = Instant.parse(root.path("collectedAt").asString())
        return root.path("articles").items().map { article ->
            val title = article.path("title").asString().trim()
            SourceDocument(
                url = article.path("url").asString(),
                title = title,
                updatedAt = collectedAt,
                sections = listOf(SectionText(title, article.path("body").asString())),
            )
        }
    }

    /** Страница сайта: текст построчный, разделы начинаются со строк из `headings`. */
    private fun readSitePages(file: Path): List<SourceDocument> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file)
            .filter { it.isNotBlank() }
            .map { mapper.readTree(it) }
            .map { page ->
                val title = page.path("title").asString().trim()
                SourceDocument(
                    url = page.path("url").asString(),
                    title = title,
                    updatedAt = Instant.parse(page.path("fetchedAt").asString()),
                    sections = splitByHeadings(title, page.path("text").asString(), headings(page)),
                )
            }
    }

    private fun headings(page: JsonNode): Set<String> =
        page.path("headings").items().map { it.asString().trim() }.filter { it.isNotEmpty() }.toSet()

    private fun splitByHeadings(title: String, text: String, headings: Set<String>): List<SectionText> {
        val sections = mutableListOf<SectionText>()
        var path = title
        val body = StringBuilder()
        fun flush() {
            if (body.isNotBlank()) sections += SectionText(path, body.toString().trim())
            body.clear()
        }
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed in headings && trimmed != title) {
                flush()
                path = trimmed
            } else if (trimmed.isNotEmpty() && trimmed != title) {
                body.append(trimmed).append('\n')
            }
        }
        flush()
        return sections
    }

    /** Элементы массива JSON: в Jackson 3 у JsonNode свой map, поэтому обходим по индексам. */
    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    private companion object {
        const val HELP_FILE = "help-tpu.json"
        const val SITE_FILE = "tpu-site.jsonl"
    }
}
