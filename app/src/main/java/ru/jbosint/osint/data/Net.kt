package ru.jbosint.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
        .followSslRedirects(true)
        .build()

    data class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299 && body.isNotBlank()
        fun json(): JSONObject = JSONObject(body)
    }

    suspend fun fetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        attempts: Int = 3,
        method: String = "GET"
    ): Response = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Разрешён только HTTPS" }
        var lastError: Exception? = null
        for (attempt in 1..attempts) {
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "JB-OSINT/1.0 (Android)")
                headers.forEach { (k, v) -> builder.header(k, v) }
                if (method.equals("POST", ignoreCase = true)) {
                    builder.post(ByteArray(0).toRequestBody(null))
                } else {
                    builder.get()
                }
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    // 429/5xx — имеет смысл повторить, 4xx — нет.
                    if ((response.code == 429 || response.code >= 500) && attempt < attempts) {
                        delay(400L * attempt)
                        return@use
                    }
                    return@withContext Response(response.code, body)
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < attempts) delay(350L * attempt)
            }
        }
        throw lastError ?: IOException("Сеть недоступна")
    }

    suspend fun json(url: String, headers: Map<String, String> = emptyMap()): Result<JSONObject> =
        runCatching { fetch(url, headers).json() }

    fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
