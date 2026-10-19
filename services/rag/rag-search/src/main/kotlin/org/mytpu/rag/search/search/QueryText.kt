package org.mytpu.rag.search.search

/** Подготовка запроса: нижний регистр, ё → е, длина до 200 символов; для полнотекста — слова через OR (документ 19). */
object QueryText {
    private const val MAX_LENGTH = 200
    private val WORD = Regex("[\\p{L}\\p{N}]{2,}")

    fun normalize(query: String): String =
        query.trim().lowercase().replace('ё', 'е').take(MAX_LENGTH)

    /** `to_tsquery` из слов запроса через `|`; слова — только буквы и цифры, поэтому синтаксис tsquery не сломать. */
    fun orTsQuery(text: String): String? =
        WORD.findAll(text).map { it.value }.distinct().joinToString(" | ").ifBlank { null }
}
