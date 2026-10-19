package org.mytpu.rag.search.ingest

import org.mytpu.rag.search.RagProperties

/**
 * Нарезка раздела на куски ~300 токенов по границам абзацев (документ 19).
 * Абзац длиннее предела режется по предложениям; одно слишком длинное предложение — по словам.
 */
class Chunker(
    private val settings: RagProperties.Chunk,
    private val countTokens: (String) -> Int,
) {

    fun chunk(documentTitle: String, section: SectionText): List<ChunkText> {
        val heading = if (section.path == documentTitle) documentTitle else "$documentTitle › ${section.path}"
        val pieces = section.text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { splitOversized(it) }

        val chunks = mutableListOf<ChunkText>()
        val current = StringBuilder()
        var currentTokens = 0
        for (piece in pieces) {
            val tokens = countTokens(piece)
            if (currentTokens > 0 && currentTokens + tokens > settings.targetTokens) {
                chunks += ChunkText(heading, current.toString(), currentTokens)
                current.clear()
                currentTokens = 0
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(piece)
            currentTokens += tokens
        }
        if (currentTokens > 0) chunks += ChunkText(heading, current.toString(), currentTokens)
        return chunks
    }

    private fun splitOversized(paragraph: String): List<String> {
        if (countTokens(paragraph) <= settings.maxTokens) return listOf(paragraph)
        val sentences = paragraph.split(SENTENCE_END).filter { it.isNotBlank() }
        return sentences.flatMap { sentence ->
            if (countTokens(sentence) <= settings.maxTokens) listOf(sentence) else splitByWords(sentence)
        }
    }

    private fun splitByWords(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && countTokens(candidate) > settings.targetTokens) {
                result += current.toString()
                current.clear()
                current.append(word)
            } else {
                current.clear()
                current.append(candidate)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private companion object {
        val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
    }
}
