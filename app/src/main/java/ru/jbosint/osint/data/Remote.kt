package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.Fact

data class ServerHealth(val online: Boolean, val detail: String)

/** Факт в формате серверной сверки: объект, поле, значение, источник. */
data class AnalyzeFact(
    val subject: String,
    val field: String,
    val value: String,
    val source: String = ""
)

data class Contradiction(val subject: String, val field: String, val values: List<String>)

data class AnalyzeResult(
    val contradictions: List<Contradiction>,
    val notes: List<String>,
    val provider: String
)

/** Текст документа, извлечённый на сервере (MarkItDown/pypdf). */
data class ExtractedDoc(val text: String, val chars: Int, val provider: String)

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

    /**
     * Серверное чтение страницы: включается, когда локальный запрос не удался
     * (403/429, блокировка по IP, таймаут). Сервер отдаёт уже разобранный текст,
     * поэтому ответ разбирается тем же [PageContent].
     */
    suspend fun scrape(base: String, key: String, url: String): PageContent {
        val body = JSONObject()
            .put("url", url)
            .put("capture_api", false)
            .put("render", true)
            .toString()
        val headers = if (key.isBlank()) emptyMap() else mapOf("X-API-Key" to key)
        val response = runCatching {
            Net.fetch("$base/scrape", headers, attempts = 1, method = "POST", body = body)
        }.getOrElse {
            return PageContent(url, error = it.message ?: "сервер недоступен", via = SERVER_VIA)
        }
        if (!response.ok) {
            return PageContent(url, error = "сервер: HTTP ${response.code}", via = SERVER_VIA)
        }
        return parseScrape(response.body, url)
    }

    /** Разбор ответа `/scrape`. Вынесен из сети, чтобы покрываться тестом. */
    fun parseScrape(raw: String, url: String): PageContent {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return PageContent(url, error = "сервер: ответ не JSON", via = SERVER_VIA)
        val content = json.optJSONObject("content")
        val title = content?.optString("title").orEmpty().take(300)
        val description = content?.optString("description").orEmpty()
        val text = content?.optString("text").orEmpty()
        val links = ArrayList<String>()
        content?.optJSONArray("links")?.let { array ->
            for (index in 0 until minOf(array.length(), 60)) {
                val link = array.optString(index)
                if (link.startsWith("https://")) links += link
            }
        }
        val status = json.optInt("status", 0)
        val blocked = json.optBoolean("blocked", false)
        val ok = status in 200..299 && !blocked && (text.length > 40 || title.isNotBlank())
        val rendered = json.optJSONObject("render")?.optBoolean("used") == true
        return PageContent(
            url = json.optString("url").ifBlank { url },
            title = title,
            description = description,
            text = text,
            links = links.distinct(),
            ok = ok,
            error = if (ok) "" else "сервер: HTTP $status",
            via = if (rendered) RENDER_VIA else SERVER_VIA
        )
    }

    /**
     * Сверка фактов на сервере: ищет разные значения одного поля у одного объекта.
     * PII маскируется на сервере до любого анализа; внешняя LLM по умолчанию выключена.
     */
    suspend fun analyze(
        base: String,
        key: String,
        facts: List<AnalyzeFact>,
        question: String? = null
    ): AnalyzeResult {
        val array = JSONArray()
        facts.forEach { fact ->
            array.put(
                JSONObject()
                    .put("object", fact.subject)
                    .put("field", fact.field)
                    .put("value", fact.value)
                    .put("source", fact.source)
            )
        }
        val body = JSONObject().put("facts", array)
        if (!question.isNullOrBlank()) body.put("question", question.take(300))
        val headers = if (key.isBlank()) emptyMap() else mapOf("X-API-Key" to key)
        val response = Net.fetch(
            "$base/ai/analyze",
            headers,
            attempts = 1,
            method = "POST",
            body = body.toString()
        )
        if (!response.ok) error("HTTP ${response.code}: ${response.body.take(200)}")
        return parseAnalyze(response.body)
    }

    /** Разбор ответа `/ai/analyze` — вынесен из сети под тест. */
    fun parseAnalyze(raw: String): AnalyzeResult {
        val json = JSONObject(raw)
        val list = json.optJSONArray("contradictions")
        val contradictions = ArrayList<Contradiction>()
        if (list != null) {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val values = item.optJSONArray("values")
                val parsed = ArrayList<String>()
                if (values != null) {
                    for (position in 0 until values.length()) {
                        values.optString(position).takeIf { it.isNotBlank() }?.let(parsed::add)
                    }
                }
                contradictions += Contradiction(
                    subject = item.optString("object"),
                    field = item.optString("field"),
                    values = parsed
                )
            }
        }
        val notesArray = json.optJSONArray("notes")
        val notes = ArrayList<String>()
        if (notesArray != null) {
            for (index in 0 until notesArray.length()) {
                notesArray.optString(index).takeIf { it.isNotBlank() }?.let(notes::add)
            }
        }
        return AnalyzeResult(
            contradictions = contradictions,
            notes = notes,
            provider = json.optString("provider").ifBlank { "local-rules" }
        )
    }

    /**
     * Факты для сверки: объект — subject, поле — kind, значение — value.
     * Берём не больше [limit], чтобы запрос оставался компактным.
     */
    fun analyzeFacts(facts: List<Fact>, limit: Int = 400): List<AnalyzeFact> = facts
        .asSequence()
        .filter { it.value.isNotBlank() }
        .map { fact ->
            AnalyzeFact(
                subject = fact.subject.ifBlank { fact.value.take(60) },
                field = fact.kind.ifBlank { fact.category.name },
                value = fact.value,
                source = fact.sourceIds.firstOrNull().orEmpty()
            )
        }
        .distinct()
        .take(limit)
        .toList()

    const val SERVER_VIA = "server"

    /** Страницу отрисовал headless-браузер на сервере (Crawl4AI). */
    const val RENDER_VIA = "render"

    /**
     * Извлечение текста документа на сервере: тело запроса — сырые байты файла,
     * имя — в query (по расширению сервер выбирает конвертер).
     */
    suspend fun extractDocument(
        base: String,
        key: String,
        name: String,
        bytes: ByteArray,
        contentType: String = DocumentText.mimeFor(name)
    ): ExtractedDoc {
        val headers = if (key.isBlank()) emptyMap() else mapOf("X-API-Key" to key)
        val raw = Net.postMultipart(
            "$base/documents/extract?name=${Net.urlEncode(name)}",
            bytes,
            contentType,
            headers,
            timeoutMs = 180_000
        )
        return parseExtract(raw)
    }

    /** Разбор ответа `/documents/extract` — вынесен из сети под тест. */
    fun parseExtract(raw: String): ExtractedDoc {
        val json = JSONObject(raw)
        val text = json.optString("text")
        require(text.isNotBlank()) { "Сервер не вернул текст документа" }
        return ExtractedDoc(
            text = text,
            chars = json.optInt("chars", text.length),
            provider = json.optString("provider").ifBlank { "server" }
        )
    }
}
