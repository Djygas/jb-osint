package ru.jbosint.osint.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.jbosint.lab.server.LabRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Импорт дампов утечек: ZIP-архив из памяти или GitHub-репозиторий.
 * Извлекаются только файлы поддерживаемых форматов, с защитой от zip-slip.
 */
object LeakRepoImport {

    private const val MAX_ARCHIVE = 60L * 1024 * 1024
    private const val MAX_FILE = 5L * 1024 * 1024
    private const val MAX_FILES = 3000
    private val extensions = setOf("txt", "csv", "tsv", "json", "sql", "log")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun importRepo(context: Context, url: String, ref: String): Int {
        val repo = LabRules.parseRepo(url, ref)
            ?: throw IllegalArgumentException("Только https://github.com/owner/repo")
        val base = "https://codeload.github.com/${repo.owner}/${repo.name}/zip"
        val candidates = buildList {
            if (repo.ref.isNotBlank()) {
                add("$base/${repo.ref}")
                add("$base/refs/heads/${repo.ref}")
            }
            add("$base/HEAD")
        }
        var last = "не удалось скачать архив"
        for (target in candidates) {
            val response = runCatching {
                client.newCall(Request.Builder().url(target).header("User-Agent", "JB-OSINT").build())
                    .execute()
            }.getOrElse { last = it.message ?: "сеть недоступна"; null } ?: continue
            response.use {
                if (it.isSuccessful) {
                    val stream = it.body?.byteStream() ?: throw IllegalStateException("Пустой архив")
                    val bytes = readLimited(stream, MAX_ARCHIVE)
                        ?: throw IllegalArgumentException("Архив больше 60 МБ")
                    return extract(bytes, LeakStore.leaksDir(context))
                }
                last = "GitHub ответил ${it.code}"
            }
        }
        throw IllegalStateException(last)
    }

    fun importZip(context: Context, bytes: ByteArray): Int =
        extract(bytes, LeakStore.leaksDir(context))

    private fun extract(bytes: ByteArray, target: File): Int {
        target.mkdirs()
        val root = target.canonicalFile
        var written = 0
        var total = 0L
        var seen = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                seen++
                if (seen > MAX_FILES || written >= 300) break
                if (entry.isDirectory) continue
                val raw = entry.name.replace('\\', '/')
                if (raw.contains("__MACOSX")) continue
                val base = raw.substringAfterLast('/')
                val ext = base.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext !in extensions) continue
                val data = readLimited(zip, MAX_FILE) ?: continue
                total += data.size
                if (total > MAX_ARCHIVE) break
                val file = File(root, LeakStore.safeName(base))
                if (!file.canonicalFile.path.startsWith(root.path + File.separator)) continue
                file.writeBytes(data)
                written++
            }
        }
        return written
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
