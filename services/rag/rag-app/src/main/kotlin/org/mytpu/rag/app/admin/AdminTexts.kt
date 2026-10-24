package org.mytpu.rag.app.admin

import java.util.Locale
import org.slf4j.LoggerFactory
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

/** Тексты API консоли rag — из `admin-messages.properties`, на русском. */
@Component
class AdminTexts {
    private val source = ResourceBundleMessageSource().apply {
        setBasename("admin-messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }

    fun text(key: String, vararg args: Any?): String =
        source.getMessage(key, args.map { it ?: "" }.toTypedArray(), key, RU) ?: key

    private companion object {
        val RU: Locale = Locale.forLanguageTag("ru")
    }
}

/** Отказ API консоли: статус, код и текст по ключу `admin.<code>`. */
class AdminProblem(val status: HttpStatus, val code: String, vararg val args: Any?) : RuntimeException(code) {
    companion object {
        fun badRequest(code: String, vararg args: Any?) = AdminProblem(HttpStatus.BAD_REQUEST, code, *args)
        fun notFound(code: String, vararg args: Any?) = AdminProblem(HttpStatus.NOT_FOUND, code, *args)
    }
}

@RestControllerAdvice(basePackageClasses = [AdminTexts::class])
class AdminErrors(private val texts: AdminTexts) {
    private val log = LoggerFactory.getLogger(AdminErrors::class.java)

    @ExceptionHandler(AdminProblem::class)
    fun problem(e: AdminProblem) = body(e.status, e.code, texts.text("admin.${e.code}", *e.args))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) = body(HttpStatus.BAD_REQUEST, "bad_request", texts.text("admin.bad_request"))

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun badArgument(e: Exception) = body(HttpStatus.BAD_REQUEST, "bad_request", texts.text("admin.bad_request"))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(e: MaxUploadSizeExceededException) =
        body(HttpStatus.CONTENT_TOO_LARGE, "file_required", texts.text("admin.file_required", AdminFiles.MAX_MB))

    @ExceptionHandler(Exception::class)
    fun failure(e: Exception): ResponseEntity<Map<String, String>> {
        log.error("Сбой API консоли rag: {}", e.javaClass.name)
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", texts.text("admin.internal_error"))
    }

    private fun body(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(mapOf("error" to code, "message" to message))
}
