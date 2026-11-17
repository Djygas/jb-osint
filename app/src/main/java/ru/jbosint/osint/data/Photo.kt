package ru.jbosint.osint.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Локальный разбор фотографии: контрольная сумма, перцептивный хеш, размеры,
 * EXIF (устройство, дата, координаты) и число лиц на снимке.
 */
data class PhotoAnalysis(
    val fileName: String,
    val mime: String,
    val sha256: String,
    val pHash: String,
    val width: Int,
    val height: Int,
    val faces: Int,
    val exif: Map<String, String>,
    val bytes: ByteArray
) {
    fun summary(): String {
        val parts = mutableListOf<String>()
        parts += "$fileName, ${width}×$height"
        if (faces > 0) parts += "лиц на снимке: $faces" else parts += "лиц не обнаружено"
        exif["Модель"]?.let { parts += it }
        exif["Дата съёмки"]?.let { parts += it }
        if (exif["Координаты"] != null) parts += "есть геометка"
        return parts.joinToString("; ")
    }
}

object PhotoAnalyzer {

    private const val MAX_SIDE = 1600

    suspend fun analyze(context: Context, uri: Uri): PhotoAnalysis = withContext(Dispatchers.IO) {
        val name = queryName(context, uri) ?: "photo.jpg"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не удалось прочитать файл изображения")
        require(bytes.size <= 25 * 1024 * 1024) { "Файл больше 25 МБ — уменьшите снимок" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        require(srcW > 0 && srcH > 0) { "Формат изображения не поддерживается" }

        val ops = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(srcW, srcH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, ops)
            ?: error("Не удалось декодировать изображение")
        val scaled = scaleDown(full, MAX_SIDE)

        val exif = readExif(context, uri, bytes)
        val faces = detectFaces(scaled)

        PhotoAnalysis(
            fileName = name,
            mime = context.contentResolver.getType(uri) ?: "image/jpeg",
            sha256 = sha256(bytes),
            pHash = dHash(scaled),
            width = srcW,
            height = srcH,
            faces = faces,
            exif = exif,
            bytes = bytes
        )
    }

    /** Факты, которые можно установить по самому файлу. */
    fun facts(photo: PhotoAnalysis): List<RawFact> {
        val source = "photo"
        val out = mutableListOf<RawFact>()
        val subject = photo.fileName

        out += RawFact(
            FactCategory.DOCUMENT, "Файл", photo.fileName, subject,
            mapOf(
                "sha256" to photo.sha256,
                "размер" to "${photo.width}×${photo.height}",
                "тип" to photo.mime
            ), source
        )
        out += RawFact(FactCategory.DOCUMENT, "Перцептивный хеш", photo.pHash, subject, emptyMap(), source)
        if (photo.faces > 0) {
            out += RawFact(
                FactCategory.DOCUMENT, "Лица на снимке", photo.faces.toString(), subject,
                mapOf("обнаружение" to "локальный детектор устройства"), source
            )
        }
        photo.exif.forEach { (kind, value) ->
            val category = when (kind) {
                "Координаты" -> FactCategory.DOCUMENT
                "Дата съёмки", "Модель", "Производитель", "Программное обеспечение" -> FactCategory.TECHNICAL
                else -> FactCategory.DOCUMENT
            }
            out += RawFact(category, kind, value, subject, emptyMap(), source)
        }
        return out
    }

    /** Никнеймы и идентификаторы, встреченные в ссылках найденных источников. */
    fun handlesFrom(urls: Collection<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in urls) {
            val url = raw.trim()
            if (url.isEmpty()) continue
            val route = Regex("^https?://([^/]+)/(.+)$", RegexOption.IGNORE_CASE).find(url) ?: continue
            val host = route.groupValues[1].lowercase().removePrefix("www.")
            val path = route.groupValues[2].substringBefore('?').trim('/')
            if (path.isEmpty()) continue
            val first = path.split('/').firstOrNull().orEmpty()
            val handle = when {
                host.endsWith("twitter.com") || host == "x.com" -> first
                host.endsWith("instagram.com") -> first
                host.endsWith("vk.com") -> first
                host.endsWith("github.com") -> first
                host.endsWith("t.me") -> first
                host.endsWith("tiktok.com") -> path.substringAfter('@', "")
                host.endsWith("pikabu.ru") -> ""
                else -> ""
            }
            val clean = handle.trim().removePrefix("@")
            if (clean.length in 2..32 && !clean.equals("status", true) &&
                clean.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
            ) out += clean
            if (out.size >= 6) break
        }
        return out.toList()
    }

    // ------------------------------------------------------------------ утилиты

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    private fun sampleFor(w: Int, h: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= 2000 && h / (sample * 2) >= 2000) sample *= 2
        return sample
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(src.width, src.height)
        if (side <= maxSide) return src
        val ratio = maxSide.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true
        )
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Разностный хеш 8×8: устойчив к сжатию и небольшому кадрированию. */
    private fun dHash(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val gray = IntArray(9 * 8)
        for (y in 0 until 8) {
            for (x in 0 until 9) {
                val pixel = small.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                gray[y * 9 + x] = (r * 299 + g * 587 + b * 114) / 1000
            }
        }
        if (small !== bitmap) small.recycle()
        val bits = StringBuilder(64)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                bits.append(if (gray[y * 9 + x] > gray[y * 9 + x + 1]) '1' else '0')
            }
        }
        return bits.toString().toBigInteger().toString(16).padStart(16, '0')
    }

    private suspend fun detectFaces(bitmap: Bitmap): Int {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setMinFaceSize(0.08f)
                .build()
        )
        return try {
            detector.process(InputImage.fromBitmap(bitmap, 0)).await().size
        } catch (error: Throwable) {
            0
        } finally {
            detector.close()
        }
    }

    private fun readExif(context: Context, uri: Uri, bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                ?: ExifInterface(bytes.inputStream())
            exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { out["Производитель"] = it }
            exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { out["Модель"] = it }
            exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { out["Программное обеспечение"] = it }
            val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (!date.isNullOrBlank()) out["Дата съёмки"] = date.trim()
            val lat = exif.latLong?.getOrNull(0)
            val lon = exif.latLong?.getOrNull(1)
            if (lat != null && lon != null) {
                out["Координаты"] = String.format(Locale.US, "%.6f, %.6f", lat, lon)
            }
            exif.getAttribute(ExifInterface.TAG_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { out["Автор (EXIF)"] = it }
            exif.getAttribute(ExifInterface.TAG_COPYRIGHT)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { out["Права (EXIF)"] = it }
        }
        return out
    }
}

/** Один результат обратного поиска изображения. */
data class PhotoHit(val url: String, val similarity: Int, val title: String, val provider: String)

/**
 * Обратный поиск по изображению. SauceNAO — публичный API с бесплатным ключом,
 * отправляет файл и возвращает ссылки на страницы-источники.
 */
object ReverseImage {

    suspend fun sauceNao(key: String, photo: PhotoAnalysis, limit: Int = 8): List<PhotoHit> =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext emptyList()
            val boundary = "----jb" + System.nanoTime()
            val body = ByteArrayOutputStream()
            fun field(name: String, value: String) {
                body.write("--$boundary\r\n".toByteArray())
                body.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                body.write(value.toByteArray())
                body.write("\r\n".toByteArray())
            }
            field("api_key", key)
            field("output_type", "2")
            field("numres", limit.toString())
            body.write("--$boundary\r\n".toByteArray())
            body.write(
                ("Content-Disposition: form-data; name=\"file\"; filename=\"${photo.fileName}\"\r\n" +
                    "Content-Type: ${photo.mime}\r\n\r\n").toByteArray()
            )
            body.write(photo.bytes)
            body.write("\r\n--$boundary--\r\n".toByteArray())

            val response = Net.postBinary(
                "https://saucenao.com/search.php", body.toByteArray(),
                "multipart/form-data; boundary=$boundary", timeoutMs = 60_000
            )
            val json = JSONObject(response)
            val results = json.optJSONArray("results") ?: return@withContext emptyList()
            val hits = mutableListOf<PhotoHit>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val header = item.optJSONObject("header") ?: continue
                val similarity = (header.optDouble("similarity", 0.0) * 100).toInt()
                if (similarity < 45) continue
                val data = item.optJSONObject("data") ?: JSONObject()
                val title = data.optJSONArray("title")?.optString(0)
                    ?: data.optString("source").takeIf { it.isNotBlank() }
                    ?: header.optString("index_name")
                val urls = data.optJSONArray("ext_urls")
                if (urls == null || urls.length() == 0) {
                    val direct = data.optString("source")
                    if (direct.startsWith("http") && !direct.contains("nHentai", true)) {
                        hits += PhotoHit(direct, similarity, title, "SauceNAO")
                    }
                    continue
                }
                for (u in 0 until urls.length()) {
                    val url = urls.optString(u)
                    if (url.startsWith("http")) hits += PhotoHit(url, similarity, title, "SauceNAO")
                }
            }
            hits.distinctBy { it.url }.take(limit)
        }
}

/**
 * Совпадение поиска по лицу: страница, где встретилось то же лицо.
 * Схожесть приходит от сервиса и трактуется только как оценка совпадения изображения.
 */
data class FaceHit(
    val url: String,
    val title: String,
    val similarity: Int,
    val provider: String,
    val status: String
)

/**
 * Поиск по лицу через внешний сервис. Публичного документированного API у
 * ProFaceFinder нет, поэтому адрес эндпоинта и ключ задаёт пользователь
 * (модуль работает с любым сервисом, принимающим multipart-загрузку снимка
 * и возвращающим JSON со списком совпадений). Разбор ответа терпимый:
 * поддерживаются поля url/link/page_url, similarity/score/confidence.
 */
object FaceSearch {

    const val MIN_SIMILARITY = 50

    fun statusFor(similarity: Int, confirmed: Boolean): String = when {
        similarity >= 90 && confirmed -> "Подтверждено"
        similarity >= 60 -> "Требует проверки"
        else -> "Не подтверждено"
    }

    suspend fun search(
        endpoint: String,
        key: String,
        photo: PhotoAnalysis,
        limit: Int = 10
    ): List<FaceHit> = withContext(Dispatchers.IO) {
        val target = endpoint.trim()
        if (!target.startsWith("https://")) return@withContext emptyList()
        val boundary = "----jb" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\n".toByteArray())
            body.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            body.write(value.toByteArray())
            body.write("\r\n".toByteArray())
        }
        if (key.isNotBlank()) field("api_key", key)
        field("limit", limit.toString())
        body.write("--$boundary\r\n".toByteArray())
        body.write(
            ("Content-Disposition: form-data; name=\"image\"; filename=\"${photo.fileName}\"\r\n" +
                "Content-Type: ${photo.mime}\r\n\r\n").toByteArray()
        )
        body.write(photo.bytes)
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val headers = if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")
        val response = runCatching {
            Net.postMultipart(target, body.toByteArray(), "multipart/form-data; boundary=$boundary", headers)
        }.getOrElse { return@withContext emptyList() }
        parse(response, photo.fileName).take(limit)
    }

    /** Разбор ответа сервиса: массив совпадений в любом из типовых конвертов. */
    fun parse(body: String, subject: String = ""): List<FaceHit> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { org.json.JSONArray(body) }.getOrNull()
            ?: return emptyList()
        val items = collect(root, 0)
        val hits = items.mapNotNull { item -> toHit(item) }
            .filter { it.similarity >= MIN_SIMILARITY }
            .distinctBy { it.url }
            .sortedByDescending { it.similarity }
        return hits
    }

    private fun collect(node: Any?, depth: Int): List<JSONObject> {
        if (node == null || depth > 4) return emptyList()
        if (node is org.json.JSONArray) {
            return (0 until node.length()).flatMap { index ->
                when (val child = node.opt(index)) {
                    is JSONObject -> listOf(child)
                    is org.json.JSONArray -> collect(child, depth + 1)
                    else -> emptyList()
                }
            }
        }
        val obj = node as? JSONObject ?: return emptyList()
        for (key in listOf("results", "matches", "data", "hits", "items", "images", "faces")) {
            val child = obj.opt(key)
            if (child is org.json.JSONArray || child is JSONObject) {
                val found = collect(child, depth + 1)
                if (found.isNotEmpty()) return found
            }
        }
        // Единственный объект-совпадение без конверта.
        return if (obj.has("url") || obj.has("link") || obj.has("page_url")) listOf(obj) else emptyList()
    }

    private fun toHit(item: JSONObject): FaceHit? {
        val url = firstString(item, "url", "link", "page_url", "profile_url", "source", "href")
            ?.takeIf { it.startsWith("http") } ?: return null
        val similarity = firstNumber(item, "similarity", "score", "confidence", "match", "similarity_percent")
            .let { raw -> if (raw in 0.0..1.0) (raw * 100).toInt() else raw.toInt() }
            .coerceIn(0, 100)
        val title = firstString(item, "title", "name", "source_name", "domain", "site", "page_title")
            ?: url.substringAfter("://").substringBefore('/')
        val provider = firstString(item, "provider", "engine", "service") ?: "Поиск по лицу"
        return FaceHit(url, title.take(120), similarity, provider, statusFor(similarity, false))
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.opt(key) ?: continue
            if (value is String && value.isNotBlank()) return value.trim()
        }
        return null
    }

    private fun firstNumber(obj: JSONObject, vararg keys: String): Double {
        for (key in keys) {
            val raw = obj.opt(key) ?: continue
            val value = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.replace(',', '.').toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            if (value > 0.0) return value
        }
        return 0.0
    }
}

/** Пересборка сводки из нескольких наборов фактов (фото + уточняющие поиски). */
object DataMerge {

    fun toRaw(data: ru.jbosint.osint.core.model.AggregatedData): List<RawFact> =
        data.facts.map { fact ->
            RawFact(
                category = fact.category,
                kind = fact.kind,
                value = fact.value,
                subject = fact.subject,
                attributes = fact.attributes,
                sourceId = fact.sourceIds.firstOrNull() ?: "merge",
                sourceUrl = fact.urls.firstOrNull().orEmpty()
            )
        }

    fun merge(
        query: String,
        parts: List<ru.jbosint.osint.core.model.AggregatedData>,
        extra: List<RawFact>,
        durationMs: Long
    ): ru.jbosint.osint.core.model.AggregatedData {
        val raw = mutableListOf<RawFact>()
        parts.forEach { raw += toRaw(it) }
        raw += extra
        val stats = ru.jbosint.osint.core.model.SearchStats(
            sourcesAttempted = parts.sumOf { it.stats.sourcesAttempted } + 1,
            sourcesResponded = parts.sumOf { it.stats.sourcesResponded } + 1,
            sourcesFailed = parts.sumOf { it.stats.sourcesFailed },
            rawFacts = parts.sumOf { it.stats.rawFacts } + extra.size,
            duplicatesRemoved = parts.sumOf { it.stats.duplicatesRemoved },
            durationMs = durationMs
        )
        val merged = ru.jbosint.osint.core.aggregate.Aggregator.aggregate(query, raw, stats)
        val sources = LinkedHashSet(parts.flatMap { it.sources })
        sources += ru.jbosint.osint.core.report.SourceCatalog.ref("photo")
        return merged.copy(sources = sources.toList())
    }
}

internal fun nowStamp(): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
