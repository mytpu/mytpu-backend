package org.mytpu.rag.app

import java.nio.file.Files
import java.nio.file.Path

/**
 * Память процесса из `/proc/self/status` (только Linux): пик RSS и раздельно анонимная память и файлы.
 * Нужна для замеров rag: модель векторов живёт вне кучи JVM, и одних цифр кучи не хватает.
 */
object ProcessMemory {
    private val FIELDS = listOf("VmHWM", "VmRSS", "RssAnon", "RssFile")

    fun summary(): String {
        val status = Path.of("/proc/self/status")
        if (!Files.exists(status)) return "нет /proc/self/status"
        return Files.readAllLines(status)
            .filter { line -> FIELDS.any { line.startsWith("$it:") } }
            .joinToString(", ") { it.replace(Regex("\\s+"), " ") }
    }
}
