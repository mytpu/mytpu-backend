package org.mytpu.rag.app

import org.mytpu.rag.app.admin.QueryLog
import org.mytpu.rag.api.SearchFilter
import org.mytpu.rag.api.SearchResult
import org.mytpu.rag.search.search.HybridDocsSearch
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Запрос поиска от gateway. */
data class SearchRequest(
    val query: String = "",
    val limit: Int = 5,
    val audience: String? = null,
    val access: String = "public",
)

/**
 * `POST /internal/search`. Какие уровни доступа документов можно запрашивать этим ключом, решает rag сам
 * (`mytpu.rag.internal.allowed-access`), а не доверяет gateway: сейчас — только `public`.
 */
@RestController
class InternalSearchController(
    private val search: HybridDocsSearch,
    private val queryLog: QueryLog,
    @Value("\${mytpu.rag.internal.allowed-access:public}") private val allowedAccess: Set<String>,
) {

    @PostMapping("/internal/search")
    fun search(@RequestBody request: SearchRequest): SearchResult {
        if (request.access !in allowedAccess) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Уровень доступа недоступен этому ключу")
        }
        val detailed = search.searchDetailed(request.query, SearchFilter(request.audience, request.access, request.limit))
        // В журнал — только после вырезания ПД (QueryLog); по нему консоль показывает, чего нет в базе
        queryLog.record(request.query, detailed.result.found(), detailed.bestSimilarity)
        return detailed.result
    }
}
