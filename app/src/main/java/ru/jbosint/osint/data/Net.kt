package ru.jbosint.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP-слой: только HTTPS, таймауты, повторные попытки с экспоненциальной паузой. */
object Net {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Редиректы разбираем сами: ключ в query-строке переходит на новый адрес целиком,
        // и переход на чужой хост отдал бы секрет постороннему серверу.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private const val MAX_REDIRECTS = 3

    /**
     * @param crossHostRedirect хост, на который сервис пытался перенаправить запрос.
     * Запрос по нему НЕ выполняется: адрес API должен указать пользователь вручную.
     */
    data class Response(
        val code: Int,
        val body: String,
        val crossHostRedirect: String? = null,
        /** Заголовок Retry-After в секундах, если сервис его прислал. */
        val retryAfter: Int? = null
    ) {
        val ok: Boolean get() = code in 200..299 && body.isNotBlank()
        fun json(): JSONObject = JSONObject(body)
    }

    /** Переход разрешён только на тот же хост и только по HTTPS. */
    fun sameHost(fromUrl: String, toUrl: String): Boolean {
        val from = fromUrl.toHttpUrlOrNull() ?: return false
        val to = toUrl.toHttpUrlOrNull() ?: return false
        return to.isHttps && to.host.equals(from.host, ignoreCase = true)
    }

    suspend fun fetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        attempts: Int = 3,
        method: String = "GET",
        body: String? = null
    ): Response = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Разрешён только HTTPS" }
        var lastError: Exception? = null
        for (attempt in 1..attempts) {
            try {
                val result = execute(url, headers, method, body)
                // 429/5xx — имеет смысл повторить, 4xx — нет.
                if ((result.code == 429 || result.code >= 500) && attempt < attempts) {
                    delay(400L * attempt)
                    continue
                }
                return@withContext result
            } catch (e: IOException) {
                lastError = e
                if (attempt < attempts) delay(350L * attempt)
            }
        }
        throw lastError ?: IOException("Сеть недоступна")
    }

    private fun execute(
        url: String,
        headers: Map<String, String>,
        method: String,
        body: String?
    ): Response {
        var target = url
        var hops = 0
        while (true) {
            val builder = Request.Builder()
                .url(target)
                .header("Accept", "application/json")
                .header("User-Agent", "JB-OSINT/1.0 (Android)")
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (method.equals("POST", ignoreCase = true)) {
                builder.post(
                    (body ?: "")
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
            } else {
                builder.get()
            }
            client.newCall(builder.build()).execute().use { response ->
                val location = response.header("Location")
                val current = response.request.url
                if (response.code in 300..399 && !location.isNullOrBlank()) {
                    val next = current.resolve(location)
                    if (next == null) return Response(response.code, "")
                    // Чужой хост или http: дальше не идём, иначе ключ уйдёт посторонним.
                    if (!sameHost(current.toString(), next.toString())) {
                        return Response(response.code, "", crossHostRedirect = next.host)
                    }
                    if (hops >= MAX_REDIRECTS) return Response(response.code, "")
                    hops++
                    target = next.toString()
                    return@use
                }
                return Response(
                    response.code,
                    response.body?.string().orEmpty(),
                    retryAfter = response.header("Retry-After")?.toIntOrNull()
                )
            }
        }
    }

    suspend fun json(url: String, headers: Map<String, String> = emptyMap()): Result<JSONObject> =
        runCatching { fetch(url, headers).json() }

    /** Отправка файла (multipart) — для обратного поиска по изображению. */
    suspend fun postBinary(
        url: String,
        bytes: ByteArray,
        contentType: String,
        timeoutMs: Long = 60_000
    ): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Разрешён только HTTPS" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "JB-OSINT/1.0 (Android)")
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        val upload = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        upload.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${text.take(200)}")
            }
            text
        }
    }

    /** Multipart-загрузка с произвольными заголовками (ключ сервиса в заголовке). */
    suspend fun postMultipart(
        url: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000
    ): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Разрешён только HTTPS" }
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "JB-OSINT/1.0 (Android)")
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.post(bytes.toRequestBody(contentType.toMediaType())).build()
        val upload = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        upload.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${text.take(200)}")
            }
            text
        }
    }

    fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
