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
 * Путь берётся декодированным и без `;параметров`, как его сопоставляет Spring MVC. Ключ сравнивается за постоянное время;
 * пустой или короткий ключ — ошибка при старте, а не «пустой ключ подходит к пустому».
 */
@Component
@ConditionalOnWebApplication
class InternalKeyFilter(
    @Value("\${rag_internal_key:}") key: String,
) : OncePerRequestFilter() {

    private val expected = key.trim().toByteArray()

    init {
        require(expected.size >= MIN_KEY_BYTES) { "Сервисный ключ rag_internal_key не задан или короче $MIN_KEY_BYTES символов" }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = PATHS.getPathWithinApplication(request)
        return path == "/actuator/health" || path.startsWith("/actuator/health/")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val presented = request.getHeader(HEADER)?.trim()?.toByteArray()
        if (presented == null || !MessageDigest.isEqual(presented, expected)) {
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
