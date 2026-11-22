package ru.jbosint.osint.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Документ досье: файл, извлечённый текст и метаданные. */
data class DocRecord(
    val id: String,
    val title: String,
    val fileName: String,
    val bytes: Int,
    val chars: Int,
    val addedAt: Long,
    val sha256: String,
    val provider: String,
    val note: String = ""
)

data class DocHit(val doc: DocRecord, val score: Int, val snippet: String)

/**
 * Локальное извлечение текста. Без сети и без библиотек:
 * TXT/MD/CSV/JSON/HTML — декодирование, DOCX — распаковка `word/document.xml`.
 * PDF и прочее — только серверный режим (MarkItDown/pypdf), здесь вернётся null.
 */
object DocumentText {

    const val MAX_BYTES = 12_000_000
    private val textExt = setOf(
        "txt", "md", "markdown", "csv", "json", "log", "yml", "yaml",
        "xml", "html", "htm", "srt", "vtt"
    )
    private val tags = Regex("<[^>]+>")
    private val scripts = Regex(
        "<(script|style)[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun extension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot < 0) "" else fileName.substring(dot + 1).lowercase()
    }

    fun title(fileName: String): String =
        fileName.substringBeforeLast('.').ifBlank { fileName }.take(80)

    /** UTF-8 с BOM, потом строгий UTF-8, потом windows-1251 — типичные выгрузки из 1С/Word. */
    fun decode(bytes: ByteArray): String {
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8.removePrefix("\uFEFF")
        val cp = runCatching {
            charset("windows-1251").newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        return cp ?: String(bytes, Charsets.UTF_8)
    }

    fun plain(html: String): String = html
        .replace(scripts, " ")
        .replace(tags, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun fromDocx(bytes: ByteArray): String {
        val xml = runCatching {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var found: String? = null
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        found = zip.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                    entry = zip.nextEntry
                }
                found.orEmpty()
            }
        }.getOrDefault("")
        if (xml.isBlank()) return ""
        val withBreaks = xml
            .replace("</w:p>", "\n")
            .replace("<w:tab/>", " ")
            .replace("<w:br/>", "\n")
        val text = withBreaks
            .replace(tags, " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return text.split("\n")
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** null — формат требует серверного режима. Пустая строка — текст не найден. */
    fun extract(fileName: String, bytes: ByteArray): String? {
        val ext = extension(fileName)
        return when {
            ext in textExt || ext.isEmpty() -> {
                val raw = decode(bytes)
                if (ext == "html" || ext == "htm") plain(raw) else raw
            }
            ext == "docx" -> fromDocx(bytes)
            else -> null
        }
    }

    fun mimeFor(fileName: String): String = when (extension(fileName)) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        else -> "text/plain"
    }
}

/** Полнотекстовый поиск по досье: все слова запроса должны встретиться. */
object DocSearch {

    fun terms(query: String): List<String> = query.lowercase()
        .split(Regex("[^\\p{L}\\p{N}@._-]+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinct()
        .take(6)

    fun snippet(text: String, term: String, width: Int = 150): String {
        val flat = text.replace(Regex("\\s+"), " ")
        val index = flat.lowercase().indexOf(term)
        if (index < 0) return flat.take(width).trim()
        val start = (index - width / 3).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(flat.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        return prefix + flat.substring(start, end).trim() + suffix
    }

    fun search(
        items: List<Pair<DocRecord, String>>,
        query: String,
        limit: Int = 30
    ): List<DocHit> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        return items.mapNotNull { (doc, text) ->
            val lower = text.lowercase()
            val counts = terms.map { term -> lower.split(term).size - 1 }
            if (counts.any { it == 0 }) return@mapNotNull null
            DocHit(doc, counts.sum(), snippet(text, terms.first()))
        }.sortedByDescending { it.score }.take(limit)
    }
}

/** Хранилище документов в приватной папке приложения: index.json + <id>.txt. */
class DocumentStore(context: Context) {

    private val dir = File(context.filesDir, "documents")
    private val index = File(dir, "index.json")

    fun list(): List<DocRecord> {
        if (!index.exists()) return emptyList()
        val raw = runCatching { index.readText(Charsets.UTF_8) }.getOrDefault("")
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { position ->
            val obj = array.optJSONObject(position) ?: return@mapNotNull null
            DocRecord(
                id = obj.optString("id"),
                title = obj.optString("title"),
                fileName = obj.optString("file_name"),
                bytes = obj.optInt("bytes"),
                chars = obj.optInt("chars"),
                addedAt = obj.optLong("added_at"),
                sha256 = obj.optString("sha256"),
                provider = obj.optString("provider"),
                note = obj.optString("note")
            )
        }.filter { it.id.isNotBlank() }.sortedByDescending { it.addedAt }
    }

    fun add(title: String, fileName: String, bytes: ByteArray, text: String, provider: String, note: String): DocRecord {
        dir.mkdirs()
        val record = DocRecord(
            id = UUID.randomUUID().toString().take(12),
            title = title,
            fileName = fileName,
            bytes = bytes.size,
            chars = text.length,
            addedAt = System.currentTimeMillis(),
            sha256 = DocumentText.sha256(bytes),
            provider = provider,
            note = note
        )
        File(dir, "${record.id}.txt").writeText(text, Charsets.UTF_8)
        save(list() + record)
        return record
    }

    fun text(id: String): String =
        runCatching { File(dir, "$id.txt").readText(Charsets.UTF_8) }.getOrDefault("")

    fun texts(): List<Pair<DocRecord, String>> = list().map { it to text(it.id) }

    fun delete(id: String) {
        File(dir, "$id.txt").delete()
        save(list().filterNot { it.id == id })
    }

    private fun save(records: List<DocRecord>) {
        dir.mkdirs()
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("title", record.title)
                    .put("file_name", record.fileName)
                    .put("bytes", record.bytes)
                    .put("chars", record.chars)
                    .put("added_at", record.addedAt)
                    .put("sha256", record.sha256)
                    .put("provider", record.provider)
                    .put("note", record.note)
            )
        }
        index.writeText(array.toString(), Charsets.UTF_8)
    }
}
