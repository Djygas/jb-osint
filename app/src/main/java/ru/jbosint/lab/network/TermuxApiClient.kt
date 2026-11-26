package ru.jbosint.lab.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.lab.model.LabApiConfig
import ru.jbosint.lab.model.LabException
import ru.jbosint.lab.model.LabProject
import ru.jbosint.lab.model.LabProjectType
import ru.jbosint.lab.model.LabRunResult
import ru.jbosint.lab.model.LabScript
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP-клиент локального сервера лаборатории в Termux.
 *
 * Соединение только с loopback (`127.0.0.1` / `localhost`), обязательный
 * заголовок `X-Lab-Token`. Токен никогда не пишется в лог и не попадает в URL.
 */
class TermuxApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun health(config: LabApiConfig): Boolean = withContext(Dispatchers.IO) {
        runCatching { get(config, "/health").optBoolean("ok", false) }
            .getOrElse { error(it) }
    }

    suspend fun clone(config: LabApiConfig, url: String, ref: String): LabProject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("url", url).put("ref", ref)
            val json = post(config, "/clone", body, totalTimeoutSec = 960)
            LabProject(
                url = json.optString("url"),
                ref = json.optString("ref"),
                dir = json.optString("dir"),
                type = LabProjectType.from(json.optString("type")),
                scripts = scripts(json.optJSONArray("scripts"))
            )
        }

    suspend fun scan(config: LabApiConfig, dir: String): LabProject =
        withContext(Dispatchers.IO) {
            val json = post(config, "/scan", JSONObject().put("dir", dir))
            LabProject(
                url = "",
                ref = "",
                dir = json.optString("dir", dir),
                type = LabProjectType.from(json.optString("type")),
                scripts = scripts(json.optJSONArray("scripts"))
            )
        }

    suspend fun run(
        config: LabApiConfig,
        dir: String,
        command: String,
        timeoutSec: Int
    ): LabRunResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("dir", dir)
            .put("command", command)
            .put("timeout", timeoutSec.coerceIn(1, 1800))
        val json = post(config, "/run", body, totalTimeoutSec = timeoutSec.toLong() + 30)
        LabRunResult(
            exitCode = json.optInt("exit_code", -1),
            stdout = json.optString("stdout"),
            stderr = json.optString("stderr"),
            tookMs = json.optLong("took_ms")
        )
    }

    suspend fun clear(config: LabApiConfig, dir: String) = withContext(Dispatchers.IO) {
        post(config, "/clear", JSONObject().put("dir", dir))
        Unit
    }

    suspend fun export(config: LabApiConfig, dir: String): String =
        withContext(Dispatchers.IO) {
            post(config, "/export", JSONObject().put("dir", dir)).optString("path")
        }

    private fun get(config: LabApiConfig, path: String): JSONObject {
        val request = Request.Builder()
            .url(endpoint(config, path))
            .header("X-Lab-Token", config.token)
            .get()
            .build()
        return execute(request)
    }

    private fun post(
        config: LabApiConfig,
        path: String,
        body: JSONObject,
        totalTimeoutSec: Long = 40
    ): JSONObject {
        val request = Request.Builder()
            .url(endpoint(config, path))
            .header("X-Lab-Token", config.token)
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(request, totalTimeoutSec)
    }

    private fun execute(request: Request, totalTimeoutSec: Long = 40): JSONObject {
        val http = if (totalTimeoutSec > 40) {
            client.newBuilder()
                .callTimeout(totalTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(totalTimeoutSec, TimeUnit.SECONDS)
                .build()
        } else {
            client
        }
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw LabException(detail(text) ?: "Сервер ответил HTTP ${response.code}")
            }
            return runCatching { JSONObject(text) }
                .getOrElse { throw LabException("Сервер вернул не JSON") }
        }
    }

    private fun endpoint(config: LabApiConfig, path: String): String {
        val base = config.baseUrl.trim().trimEnd('/')
        val loopback = base.startsWith("http://127.0.0.1") ||
            base.startsWith("http://localhost") ||
            base.startsWith("http://[::1]")
        if (!loopback) throw LabException("Адрес должен быть локальным: http://127.0.0.1:8081")
        if (config.token.isBlank()) throw LabException("Внутренний токен не создан — включите лабораторию")
        return base + path
    }

    private fun scripts(array: JSONArray?): List<LabScript> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val path = item.optString("path")
            if (path.isBlank()) return@mapNotNull null
            LabScript(
                name = item.optString("name").ifBlank { path.substringAfterLast('/') },
                path = path,
                type = LabProjectType.from(item.optString("type")),
                button = item.optString("button").takeIf { it.isNotBlank() && !item.isNull("button") },
                param = item.optString("param").takeIf { it.isNotBlank() && !item.isNull("param") }
            )
        }
    }

    private fun detail(text: String): String? = runCatching {
        JSONObject(text).optString("detail")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun error(t: Throwable): Nothing = when (t) {
        is LabException -> throw t
        is IOException -> throw LabException("Локальный сервер недоступен — включите лабораторию")
        else -> throw LabException(t.message ?: "Ошибка сети")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
