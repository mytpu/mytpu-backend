package org.mytpu.rag.app.admin

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Файлы из консоли на томе `rag-files` (`mytpu.rag.files-dir`): поток пишется прямо на диск (не в память),
 * с подсчётом SHA-256 и обрывом на 20 МБ; имя на диске — случайное, исходное хранится только в БД.
 */
@Component
class AdminFiles(@Value("\${mytpu.rag.files-dir:/data/files}") dir: String) {
    private val root: Path = Path.of(dir).toAbsolutePath().normalize()

    data class Stored(val relative: String, val name: String, val size: Long, val sha256: String)

    fun store(input: InputStream, originalName: String): Stored {
        val name = safeName(originalName)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in FORMATS) throw AdminProblem.badRequest("file_format")
        Files.createDirectories(root)
        val relative = UUID.randomUUID().toString() + "." + ext
        val target = root.resolve(relative)
        val temp = Files.createTempFile(root, "upload-", ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            var size = 0L
            DigestInputStream(input, digest).use { source ->
                Files.newOutputStream(temp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        size += n
                        if (size > MAX_MB * 1024L * 1024L) throw AdminProblem.badRequest("file_required", MAX_MB)
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (size == 0L) throw AdminProblem.badRequest("file_required", MAX_MB)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            return Stored(relative, name, size, HexFormat.of().formatHex(digest.digest()))
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun path(relative: String): Path {
        val path = root.resolve(relative).normalize()
        check(path.startsWith(root)) { "путь файла вне каталога rag-files" }
        return path
    }

    fun delete(relative: String) {
        Files.deleteIfExists(path(relative))
    }

    companion object {
        const val MAX_MB = 20
        val FORMATS = setOf("pdf", "docx", "html", "htm", "md", "markdown", "txt")

        /** Только последнее звено имени, без управляющих символов и разделителей пути; не длиннее 150 символов. */
        fun safeName(name: String): String {
            val base = name.replace('\\', '/').substringAfterLast('/').replace(Regex("[\\p{Cntrl}<>:\"|?*]"), "_").trim()
            return base.ifBlank { "file" }.takeLast(150)
        }
    }
}
