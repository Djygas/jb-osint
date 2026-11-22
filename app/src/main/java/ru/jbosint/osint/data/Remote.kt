package ru.jbosint.osint.data

import org.json.JSONObject

data class ServerHealth(val online: Boolean, val detail: String)

data class RaeRemote(
    val client: RaeClient,
    val scriptPath: String,
    val usage: String,
    val masked: String,
    val script: String
)

/** Клиент серверного ускорителя. Все запросы только по HTTPS и с X-API-Key. */
object Remote {

    fun normalizeUrl(raw: String): String? {
        val url = raw.trim().trimEnd('/')
        if (url.isEmpty() || url.contains(' ')) return null
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        return url
    }

    suspend fun health(base: String, key: String): ServerHealth {
        val headers = if (key.isBlank()) emptyMap() else mapOf("X-API-Key" to key)
        return runCatching {
            val response = Net.fetch("$base/healthz", headers, attempts = 1)
            if (response.code == 200 && response.body.isNotBlank()) {
                ServerHealth(true, "Сервер онлайн")
            } else {
                ServerHealth(false, "HTTP ${response.code}")
            }
        }.getOrElse { ServerHealth(false, it.message ?: "Нет связи") }
    }

    suspend fun raeGenerate(
        base: String,
        key: String,
        url: String,
        prompt: String
    ): Result<RaeRemote> = runCatching {
        val body = JSONObject()
            .put("url", url)
            .put("prompt", prompt.ifBlank { "Собери структурированные данные страницы" })
            .put("mode", "agent")
            .toString()
        val headers = if (key.isBlank()) emptyMap() else mapOf("X-API-Key" to key)
        val response = Net.postLong("$base/api/v1/rae/generate", headers, body)
        if (!response.ok) {
            error("HTTP ${response.code}: ${response.body.take(240)}")
        }
        val json = response.json()
        val script = json.optString("script")
        val client = RaeParser.parse(script)
            ?: error("Сервер вернул код без base_url — проверьте ответ")
        val usage = json.optJSONObject("usage")?.let {
            "токены: ${it.optInt("input_tokens")} → ${it.optInt("output_tokens")} " +
                "(${it.optString("provider")})"
        }.orEmpty()
        val masked = json.optJSONObject("masked")?.let {
            "маскировано: email ${it.optInt("emails")}, телефонов ${it.optInt("phones")}, " +
                "IP ${it.optInt("ips")}, токенов ${it.optInt("tokens")}"
        }.orEmpty()
        RaeRemote(
            client = client,
            scriptPath = json.optString("script_path"),
            usage = usage,
            masked = masked,
            script = script
        )
    }
}
