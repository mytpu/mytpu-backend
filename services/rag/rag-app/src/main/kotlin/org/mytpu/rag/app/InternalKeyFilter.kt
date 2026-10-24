package org.mytpu.rag.app

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper
import java.security.MessageDigest

/**
 * «Закрыто всё»: без сервисного ключа gateway → rag (файл-секрет `rag_internal_key`) открыт только `/actuator/health`.
 * API консоли (пути `/admin/…`) — только со своим ключом `rag_admin_key`: ключ поиска не открывает загрузку и удаление документов.
 * Путь берётся декодированным и без `;параметров`, как его сопоставляет Spring MVC. Ключ сравнивается за постоянное время;
 * пустой или короткий ключ — ошибка при старте, а не «пустой ключ подходит к пустому».
 */
@Component
@ConditionalOnWebApplication
class InternalKeyFilter(
    @Value("\${rag_internal_key:}") key: String,
    @Value("\${rag_admin_key:}") adminKey: String,
) : OncePerRequestFilter() {

    private val expected = key.trim().toByteArray()
    private val expectedAdmin = adminKey.trim().toByteArray()

    init {
        require(expected.size >= MIN_KEY_BYTES) { "Сервисный ключ rag_internal_key не задан или короче $MIN_KEY_BYTES символов" }
        require(expectedAdmin.size >= MIN_KEY_BYTES) { "Ключ консоли rag_admin_key не задан или короче $MIN_KEY_BYTES символов" }
        require(!MessageDigest.isEqual(expected, expectedAdmin)) { "rag_admin_key должен отличаться от rag_internal_key" }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = PATHS.getPathWithinApplication(request)
        return path == "/actuator/health" || path.startsWith("/actuator/health/")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val presented = request.getHeader(HEADER)?.trim()?.toByteArray()
        val path = PATHS.getPathWithinApplication(request)
        val admin = path == "/admin" || path.startsWith("/admin/")
        if (presented == null || !MessageDigest.isEqual(presented, if (admin) expectedAdmin else expected)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }
        chain.doFilter(request, response)
    }

    private companion object {
        const val HEADER = "X-Internal-Key"
        const val MIN_KEY_BYTES = 32
        val PATHS = UrlPathHelper()
    }
}
