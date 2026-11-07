package ru.jbosint.osint.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.Link

/**
 * Интеллектуальный ассистент на Hugging Face Inference Providers
 * (OpenAI-совместимый роутер `https://router.huggingface.co/v1`).
 *
 * Ключ хранится только в SecureStore (AES-256/GCM поверх Android Keystore) и
 * передаётся единственным заголовком `Authorization: Bearer` при запросе.
 * Ни ключ, ни промты не пишутся в логи и не попадают в отчёт.
 */
enum class AssistantRole(val title: String, val task: String, val maxTokens: Int) {
    ANALYST("Аналитик", "analyze", 1200),
    RESEARCHER("Исследователь", "search", 900),
    CLASSIFIER("Классификатор", "classify", 500),
    QUERY_PLANNER("Генератор запросов", "generate_queries", 600),
    SYNTHESIS("Синтезатор отчёта", "synthesize", 1800)
}

data class AssistantModel(val id: String, val title: String, val note: String)

/** Как проверяется ключ выбранного поставщика. */
enum class AssistantProbe { HUGGINGFACE_WHOAMI, OPENAI_MODELS, NONE }

/**
 * Поставщик модели. Все — OpenAI-совместимый `POST {base}/chat/completions`,
 * поэтому смена ИИ не меняет ни промты, ни разбор ответа.
 */
data class AssistantProvider(
    val id: String,
    val title: String,
    val baseUrl: String,
    val docsUrl: String,
    val keyHint: String,
    val defaultModel: String,
    val models: List<AssistantModel>,
    val probe: AssistantProbe = AssistantProbe.OPENAI_MODELS,
    val custom: Boolean = false
)

/**
 * Каталог поставщиков. Hugging Face — по умолчанию, остальные подключаются
 * своим ключом; «Свой сервер» принимает любой совместимый адрес (в том числе
 * локальный прокси на https). Ключи разных поставщиков хранятся раздельно.
 */
object AssistantCatalog {
    const val BASE_URL = "https://router.huggingface.co/v1"
    const val CHAT_URL = "$BASE_URL/chat/completions"
    const val MODELS_URL = "$BASE_URL/models"
    const val WHOAMI_URL = "https://huggingface.co/api/whoami-v2"
    const val TOKENS_URL = "https://huggingface.co/settings/tokens"
    const val DEFAULT_MODEL = "openai/gpt-oss-20b"
    const val DEFAULT_PROVIDER = "hf"

    val models: List<AssistantModel> = listOf(
        AssistantModel("openai/gpt-oss-20b", "GPT-OSS 20B", "быстрый, для черновиков"),
        AssistantModel("openai/gpt-oss-120b", "GPT-OSS 120B", "глубокий анализ"),
        AssistantModel("meta-llama/Llama-3.1-8B-Instruct", "Llama 3.1 8B", "лёгкий и дешёвый"),
        AssistantModel("meta-llama/Llama-3.3-70B-Instruct", "Llama 3.3 70B", "сбалансированный"),
        AssistantModel("Qwen/Qwen3.5-9B", "Qwen 3.5 9B", "многоязычный"),
        AssistantModel("deepseek-ai/DeepSeek-V3", "DeepSeek V3", "длинный контекст")
    )

    val providers: List<AssistantProvider> = listOf(
        AssistantProvider(
            id = "hf", title = "Hugging Face", baseUrl = BASE_URL,
            docsUrl = TOKENS_URL, keyHint = "hf_…", defaultModel = DEFAULT_MODEL,
            models = models, probe = AssistantProbe.HUGGINGFACE_WHOAMI
        ),
        AssistantProvider(
            id = "openrouter", title = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1",
            docsUrl = "https://openrouter.ai/keys", keyHint = "sk-or-v1-…",
            defaultModel = "openai/gpt-4o-mini",
            models = listOf(
                AssistantModel("openai/gpt-4o-mini", "GPT-4o mini", "быстрый и недорогой"),
                AssistantModel("openai/gpt-4o", "GPT-4o", "универсальный"),
                AssistantModel("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", "длинные отчёты"),
                AssistantModel("google/gemini-2.0-flash-001", "Gemini 2.0 Flash", "большой контекст"),
                AssistantModel("deepseek/deepseek-chat", "DeepSeek Chat", "цена и качество")
            )
        ),
        AssistantProvider(
            id = "openai", title = "OpenAI", baseUrl = "https://api.openai.com/v1",
            docsUrl = "https://platform.openai.com/api-keys", keyHint = "sk-…",
            defaultModel = "gpt-4o-mini",
            models = listOf(
                AssistantModel("gpt-4o-mini", "GPT-4o mini", "быстрый и недорогой"),
                AssistantModel("gpt-4o", "GPT-4o", "универсальный"),
                AssistantModel("gpt-4.1-mini", "GPT-4.1 mini", "новое поколение")
            )
        ),
        AssistantProvider(
            id = "groq", title = "Groq", baseUrl = "https://api.groq.com/openai/v1",
            docsUrl = "https://console.groq.com/keys", keyHint = "gsk_…",
            defaultModel = "llama-3.3-70b-versatile",
            models = listOf(
                AssistantModel("llama-3.3-70b-versatile", "Llama 3.3 70B", "очень быстрый"),
                AssistantModel("llama-3.1-8b-instant", "Llama 3.1 8B", "мгновенный")
            )
        ),
        AssistantProvider(
            id = "deepseek", title = "DeepSeek", baseUrl = "https://api.deepseek.com/v1",
            docsUrl = "https://platform.deepseek.com/api_keys", keyHint = "sk-…",
            defaultModel = "deepseek-chat",
            models = listOf(
                AssistantModel("deepseek-chat", "DeepSeek Chat", "цена и качество"),
                AssistantModel("deepseek-reasoner", "DeepSeek Reasoner", "пошаговые выводы")
            )
        ),
        AssistantProvider(
            id = "mistral", title = "Mistral", baseUrl = "https://api.mistral.ai/v1",
            docsUrl = "https://console.mistral.ai/api-keys/", keyHint = "…",
            defaultModel = "mistral-small-latest",
            models = listOf(
                AssistantModel("mistral-small-latest", "Mistral Small", "быстрый"),
                AssistantModel("mistral-large-latest", "Mistral Large", "глубокий анализ")
            )
        ),
        AssistantProvider(
            id = "custom", title = "Свой сервер", baseUrl = "",
            docsUrl = "https://platform.openai.com/docs/api-reference/chat", keyHint = "ключ сервера",
            defaultModel = "", models = emptyList(), custom = true
        )
    )

    fun provider(id: String): AssistantProvider =
        providers.firstOrNull { it.id == id } ?: providers.first()

    fun titleFor(id: String): String =
        models.firstOrNull { it.id == id }?.title
            ?: providers.firstNotNullOfOrNull { p -> p.models.firstOrNull { it.id == id }?.title }
            ?: id

    fun isKnown(id: String): Boolean = models.any { it.id == id }

    /** Нормализует базовый адрес: только https, без хвостового слэша и без /chat/completions. */
    fun normaliseBase(raw: String): String? {
        val clean = raw.trim().trimEnd('/')
        if (clean.isBlank()) return null
        val url = if (clean.startsWith("http", ignoreCase = true)) clean else "https://$clean"
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val host = url.removePrefix("https://").substringBefore('/')
        if (host.isBlank() || !host.contains('.')) return null
        return url.removeSuffix("/chat/completions")
    }

    /** Угадывает поставщика по вставленному адресу. */
    fun byBaseUrl(raw: String): AssistantProvider? {
        val url = normaliseBase(raw) ?: return null
        return providers.firstOrNull { it.baseUrl.isNotBlank() && url.startsWith(it.baseUrl) }
    }

    /** Угадывает поставщика по префиксу ключа. */
    fun byKey(key: String): AssistantProvider? = when {
        key.startsWith("hf_") -> provider("hf")
        key.startsWith("sk-or-v1-") -> provider("openrouter")
        key.startsWith("gsk_") -> provider("groq")
        key.startsWith("sk-") -> provider("openai")
        else -> null
    }
}

/** Один результат классификации: тип данных и релевантность. */
data class AssistantClassification(val type: String, val relevance: String) {
    val category: FactCategory
        get() = when (type.lowercase()) {
            "email", "phone", "username", "messenger", "telegram" -> FactCategory.CONTACT
            "person", "name", "full_name" -> FactCategory.PERSON
            "domain", "ip", "url", "asn", "hash" -> FactCategory.TECHNICAL
            "address", "city", "country", "geo" -> FactCategory.LOCATION
            "company", "organization", "org" -> FactCategory.ORGANIZATION
            "document", "passport", "inn", "snils" -> FactCategory.DOCUMENT
            else -> FactCategory.OTHER
        }

    val relevanceLabel: String
        get() = when (relevance.lowercase()) {
            "high" -> "высокая"
            "medium" -> "средняя"
            "low" -> "низкая"
            else -> relevance
        }

    val typeLabel: String
        get() = when (type.lowercase()) {
            "email" -> "Почта"
            "phone" -> "Телефон"
            "username" -> "Никнейм"
            "telegram" -> "Telegram"
            "person", "name", "full_name" -> "Имя"
            "domain" -> "Домен"
            "ip" -> "IP-адрес"
            "url" -> "Ссылка"
            "address" -> "Адрес"
            "company", "organization", "org" -> "Организация"
            "document" -> "Документ"
            else -> "Прочее"
        }
}

/** Инструкция модулю: что искать. */
data class QueryInstruction(val module: String, val query: String)

/** Промты ролей. Все ответы — по-русски, без выдуманных фактов. */
object AssistantPrompts {
    const val COMMON = "Ты — модуль аналитической системы OSINT, работаешь только с открытыми " +
        "источниками. Отвечай по-русски, кратко и по делу. Не выдумывай сведения: если данных " +
        "недостаточно, так и напиши. Не упоминай названия коммерческих сервисов-источников."

    fun system(role: AssistantRole): String = when (role) {
        AssistantRole.ANALYST -> "$COMMON Ты аналитик: связываешь цифровые следы в связную " +
            "картину, отделяешь установленные факты от предположений, указываешь степень " +
            "уверенности. Пиши 3–6 абзацев без маркдауна и без нумерованных списков."
        AssistantRole.RESEARCHER -> "$COMMON Ты исследователь: формулируешь точные поисковые " +
            "запросы и суммируешь найденное своими словами, без копирования текста источников."
        AssistantRole.CLASSIFIER -> "$COMMON Ты классификатор: определяешь тип значения и его " +
            "релевантность для OSINT-исследования. Отвечаешь только JSON."
        AssistantRole.QUERY_PLANNER -> "$COMMON Ты планировщик: превращаешь задачу пользователя " +
            "в конкретные запросы к модулям. Отвечаешь только JSON."
        AssistantRole.SYNTHESIS -> "$COMMON Ты редактор отчёта: выстраиваешь материал в " +
            "структуру с заголовками, таблицами и выводами, не добавляя новых фактов."
    }

    private fun factsPayload(facts: List<Fact>, limit: Int = 160): JSONArray {
        val array = JSONArray()
        facts.take(limit).forEach { fact ->
            val obj = JSONObject()
                .put("type", typeOf(fact))
                .put("value", fact.value.take(300))
            if (fact.kind.isNotBlank()) obj.put("kind", fact.kind)
            if (fact.subject.isNotBlank()) obj.put("subject", fact.subject.take(200))
            obj.put("confidence", fact.confidence.title)
            obj.put("sources", fact.sourceCount)
            if (fact.attributes.isNotEmpty()) {
                val attrs = JSONObject()
                fact.attributes.entries.take(6).forEach { (k, v) ->
                    attrs.put(k.take(60), v.take(300))
                }
                obj.put("attributes", attrs)
            }
            array.put(obj)
        }
        return array
    }

    private fun typeOf(fact: Fact): String {
        val probe = (fact.kind + " " + fact.value).lowercase()
        return when {
            probe.contains("почт") || probe.contains("@") -> "email"
            probe.contains("телефон") || Regex("\\+?\\d[\\d\\s()\\-]{9,}").containsMatchIn(fact.value) -> "phone"
            probe.contains("домен") -> "domain"
            probe.contains("ip") -> "ip"
            probe.contains("ник") || probe.contains("логин") -> "username"
            probe.contains("имя") || probe.contains("фио") -> "name"
            probe.contains("адрес") || probe.contains("город") -> "address"
            probe.contains("компан") || probe.contains("организац") -> "organization"
            else -> "other"
        }
    }

    fun analyze(query: String, facts: List<Fact>, links: List<Link>): String {
        val payload = JSONObject()
            .put("task", "analyze")
            .put("data", factsPayload(facts))
        if (links.isNotEmpty()) {
            val array = JSONArray()
            links.take(40).forEach { link ->
                array.put(
                    JSONObject()
                        .put("from", link.from.take(200))
                        .put("to", link.to.take(200))
                        .put("relation", link.relation)
                        .put("sources", link.sourceCount)
                )
            }
            payload.put("links", array)
        }
        return payload.toString() +
            "\n\nПроанализируй предоставленные цифровые следы объекта «${query.take(200)}» и сделай " +
            "выводы о личности субъекта. Определи возможные связи между фактами, укажи, какие " +
            "сведения подтверждены несколькими источниками, а какие требуют проверки. Не добавляй " +
            "фактов, которых нет во входных данных."
    }

    fun researchQueries(userInput: String, context: String): String {
        val payload = JSONObject()
            .put("task", "search")
            .put("query", userInput.take(1200))
            .put("sources", JSONArray().put("duckduckgo"))
        if (context.isNotBlank()) payload.put("context", context.take(200))
        return payload.toString() +
            "\n\nСоставь 3 коротких поисковых запроса (2–6 слов каждый), которые помогут найти " +
            "публичную информацию по этой задаче. Верни строго JSON-массив строк: " +
            "[\"запрос\", \"запрос\", \"запрос\"]."
    }

    fun researchSummary(userInput: String, digest: String): String {
        val payload = JSONObject()
            .put("task", "search")
            .put("query", userInput.take(1200))
            .put("sources", JSONArray().put("duckduckgo"))
            .put("results", digest.take(9000))
        return payload.toString() +
            "\n\nНиже — фрагменты, найденные в открытых источниках. Сделай краткую выжимку " +
            "(5–10 предложений) своими словами: что подтверждается, что противоречит, чего не " +
            "хватает. Не копируй текст источников дословно и не выдумывай сведений."
    }

    fun classify(value: String): String =
        JSONObject()
            .put("task", "classify")
            .put("input", value.take(400))
            .toString() +
            "\n\nОпредели тип значения и его релевантность для OSINT-исследования. Верни строго " +
            "JSON без пояснений: {\"type\": \"username\", \"relevance\": \"high\"}. Тип — одно из: " +
            "email, phone, username, telegram, person, domain, ip, url, address, company, document, " +
            "other. relevance — high, medium или low."

    fun classifyBatch(values: List<String>): String {
        val array = JSONArray()
        values.take(12).forEach { array.put(it.take(400)) }
        return JSONObject().put("task", "classify").put("input", array).toString() +
            "\n\nОпредели тип и релевантность каждого значения. Верни строго JSON-массив тех же " +
            "длин: [{\"type\": \"username\", \"relevance\": \"high\"}, …]. Тип — одно из: email, phone, " +
            "username, telegram, person, domain, ip, url, address, company, document, other."
    }

    fun queries(userInput: String, modules: List<Pair<String, String>>, context: String): String {
        val array = JSONArray()
        modules.take(30).forEach { (name, capabilities) ->
            array.put(JSONObject().put("name", name.take(60)).put("capabilities", capabilities.take(140)))
        }
        val payload = JSONObject()
            .put("task", "generate_queries")
            .put("modules", array)
            .put("user_input", userInput.take(1200))
        if (context.isNotBlank()) payload.put("context", context.take(200))
        return payload.toString() +
            "\n\nВыбери подходящие модули и сформулируй для каждого один точный запрос. Значение " +
            "запроса — только идентификатор из задачи (email, телефон, домен, IP, ник или имя), " +
            "без лишних слов. Верни строго JSON-массив: " +
            "[{\"module\": \"название модуля\", \"query\": \"значение\"}]. Не больше 6 инструкций."
    }

    fun synthesize(query: String, data: AggregatedData, analysis: String): String {
        val payload = JSONObject()
            .put("task", "synthesize")
            .put("query", query.take(200))
        payload.put("facts", factsPayload(data.facts, 120))
        val links = JSONArray()
        data.links.take(30).forEach { link ->
            links.put(
                JSONObject()
                    .put("from", link.from.take(160))
                    .put("to", link.to.take(160))
                    .put("relation", link.relation)
            )
        }
        payload.put("links", links)
        payload.put("statistics", JSONObject()
            .put("sources_attempted", data.stats.sourcesAttempted)
            .put("sources_responded", data.stats.sourcesResponded)
            .put("facts", data.facts.size)
            .put("links", data.links.size))
        if (analysis.isNotBlank()) payload.put("analysis", analysis.take(6000))
        return payload.toString() +
            "\n\nОформи аналитическое резюме исследования в Markdown: заголовки уровня ##, короткие " +
            "абзацы, при необходимости список «- ». Структура: «Ключевые выводы», «Установленные " +
            "связи», «Что требует проверки». Только сведения из входных данных, без новых фактов и " +
            "без таблиц."
    }
}

/** Разбор ответов модели: код-фенсы, лишний текст и вложенные обёртки отбрасываются. */
object AssistantParser {

    fun content(responseBody: String): String? {
        val root = runCatching { JSONObject(responseBody) }.getOrNull() ?: return null
        val error = root.opt("error")
        if (error != null && root.optJSONArray("choices") == null) return null
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        val raw = message.opt("content")
        val text = when (raw) {
            is String -> raw
            is JSONArray -> buildString {
                for (i in 0 until raw.length()) {
                    val part = raw.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }
            else -> ""
        }
        return text.trim().takeIf { it.isNotBlank() }
    }

    /** Убирает ```json … ``` и служебные префиксы вида «Ответ:». */
    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            val firstBreak = text.indexOf('\n')
            if (firstBreak >= 0) text = text.substring(firstBreak + 1)
            text = text.removeSuffix("```").trim()
        }
        text = text.removePrefix("Ответ:").removePrefix("ответ:").trim()
        return text.trim().trim('"').trim()
    }

    fun analysis(raw: String): String? {
        val text = clean(raw)
        if (text.length < 40) return null
        return text.take(8000)
    }

    fun classification(raw: String): AssistantClassification? {
        val json = firstJsonObject(clean(raw)) ?: return null
        val type = json.optString("type").trim().lowercase()
        if (type.isBlank()) return null
        val relevance = json.optString("relevance").trim().lowercase()
            .takeIf { it in setOf("high", "medium", "low") } ?: "medium"
        return AssistantClassification(type, relevance)
    }

    fun classifications(raw: String): List<AssistantClassification> {
        val text = clean(raw)
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: firstJsonArray(text)
            ?: firstJsonObject(text)?.optJSONArray("results")
            ?: firstJsonObject(text)?.optJSONArray("data")
        if (array != null) {
            val result = ArrayList<AssistantClassification>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val type = item.optString("type").trim().lowercase()
                if (type.isBlank()) continue
                val relevance = item.optString("relevance").trim().lowercase()
                    .takeIf { it in setOf("high", "medium", "low") } ?: "medium"
                result += AssistantClassification(type, relevance)
            }
            return result
        }
        return classification(text)?.let { listOf(it) } ?: emptyList()
    }

    fun queries(raw: String): List<QueryInstruction> {
        val text = clean(raw)
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: firstJsonArray(text)
            ?: firstJsonObject(text)?.optJSONArray("queries")
        if (array == null) {
            // Модель могла вернуть одну строку-запрос без JSON.
            val single = clean(raw).takeIf { it.length in 2..200 && !it.contains('\n') }
            return single?.let { listOf(QueryInstruction("", it)) } ?: emptyList()
        }
        val result = ArrayList<QueryInstruction>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            if (item != null) {
                val module = item.optString("module").trim()
                val query = item.optString("query").trim()
                if (query.isNotBlank()) result += QueryInstruction(module, query)
            } else {
                val plain = array.optString(i).trim()
                if (plain.isNotBlank()) result += QueryInstruction("", plain)
            }
        }
        return result.distinctBy { it.module.lowercase() to it.query.lowercase() }.take(6)
    }

    /** Поисковые строки из ответа на research-шаг: массив строк, объекты или строки через перевод строки. */
    fun searchStrings(raw: String): List<String> {
        val text = clean(raw)
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: firstJsonArray(text)
            ?: firstJsonObject(text)?.optJSONArray("queries")
        if (array != null) {
            val result = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) result += value
            }
            if (result.isNotEmpty()) return result.take(4)
        }
        return text.split('\n', ';')
            .map { it.trim().trim('-', '•', '*', '1', '2', '3', '4', '5', '.', ')').trim() }
            .filter { it.length in 2..120 }
            .distinct()
            .take(4)
    }

    fun error(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body) }.getOrNull()?.let { json ->
            val error = json.opt("error")
            when (error) {
                is String -> error
                is JSONObject -> error.optString("message")
                else -> json.optString("message")
            }
        }.orEmpty().take(160)
        return when (code) {
            401, 403 -> "Hugging Face не принял ключ (код $code)" + detail.suffix()
            402 -> "Недостаточно средств или квоты на Hugging Face" + detail.suffix()
            404 -> "Модель не найдена — проверьте ID" + detail.suffix()
            429 -> "Слишком много запросов, попробуйте позже"
            in 500..599 -> "Сервис модели временно недоступен (код $code)"
            400, 422 -> "Модель отклонила запрос" + detail.suffix()
            else -> "Ответ сервиса $code" + detail.suffix()
        }
    }

    private fun String.suffix(): String = if (isBlank()) "" else ": $this"

    /** Первый JSON-массив в тексте: модель иногда предваряет его фразой. */
    private fun firstJsonArray(text: String): JSONArray? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return runCatching { JSONArray(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun firstJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}

/** Настройки ассистента: ключ шифруется, модель и сценарии — в обычных настройках. */
class AssistantStore(context: Context) {

    private val secure = SecureStore(context)
    private val prefs = context.getSharedPreferences("jb_assistant", Context.MODE_PRIVATE)

    /** Поставщик модели: id из [AssistantCatalog.providers]. */
    var provider: String
        get() = prefs.getString("provider", AssistantCatalog.DEFAULT_PROVIDER).orEmpty()
            .ifBlank { AssistantCatalog.DEFAULT_PROVIDER }
            .let { id -> if (AssistantCatalog.providers.any { it.id == id }) id else AssistantCatalog.DEFAULT_PROVIDER }
        set(value) = prefs.edit().putString("provider", value.trim().take(40)).apply()

    /** Базовый адрес для «своего сервера» (только https, проверяется при сохранении). */
    var customBaseUrl: String
        get() = prefs.getString("base_url", "").orEmpty()
        set(value) = prefs.edit().putString("base_url", value.trim().take(200)).apply()

    /** Базовый адрес текущего поставщика: у известных он из каталога, у своего — сохранённый. */
    fun baseUrl(): String {
        val spec = AssistantCatalog.provider(provider)
        val raw = if (spec.custom) customBaseUrl else spec.baseUrl
        return AssistantCatalog.normaliseBase(raw).orEmpty()
    }

    fun chatUrl(): String = baseUrl().trimEnd('/') + "/chat/completions"

    fun keyName(providerId: String = provider): String =
        if (providerId == "hf") LEGACY_KEY else "${LEGACY_KEY}_$providerId"

    fun key(): String = secure.get(keyName()).orEmpty()

    fun hasKey(providerId: String = provider): Boolean = secure.get(keyName(providerId)).orEmpty().isNotBlank()

    fun saveKey(value: String, providerId: String = provider) {
        secure.put(keyName(providerId), value.trim())
        prefs.edit().remove("state").remove("checked").apply()
    }

    fun clearKey(providerId: String = provider) {
        secure.remove(keyName(providerId))
        prefs.edit().remove("state").remove("checked").apply()
    }

    var model: String
        get() = prefs.getString(modelKey(), null)?.takeIf { it.isNotBlank() }
            ?: AssistantCatalog.provider(provider).defaultModel.ifBlank { AssistantCatalog.DEFAULT_MODEL }
        set(value) = prefs.edit().putString(modelKey(), value.trim().take(160)).apply()

    private fun modelKey(): String = "model_$provider"

    var autoAnalyze: Boolean
        get() = prefs.getBoolean("auto_analyze", false)
        set(value) = prefs.edit().putBoolean("auto_analyze", value).apply()

    var autoClassify: Boolean
        get() = prefs.getBoolean("auto_classify", false)
        set(value) = prefs.edit().putBoolean("auto_classify", value).apply()

    var autoQueries: Boolean
        get() = prefs.getBoolean("auto_queries", false)
        set(value) = prefs.edit().putBoolean("auto_queries", value).apply()

    var polishReport: Boolean
        get() = prefs.getBoolean("polish_report", false)
        set(value) = prefs.edit().putBoolean("polish_report", value).apply()

    var autoResearch: Boolean
        get() = prefs.getBoolean("auto_research", false)
        set(value) = prefs.edit().putBoolean("auto_research", value).apply()

    fun state(): KeyState = runCatching {
        KeyState.valueOf(prefs.getString("state", null) ?: KeyState.UNCHECKED.name)
    }.getOrDefault(KeyState.UNCHECKED)

    fun setState(value: KeyState) {
        prefs.edit().putString("state", value.name).putLong("checked", System.currentTimeMillis()).apply()
    }

    fun checkedAt(): Long = prefs.getLong("checked", 0L)

    fun configured(): Boolean = key().isNotBlank()

    /** Есть ли ключ хотя бы у одного поставщика — для подсказки «другой ИИ уже настроен». */
    fun anyConfigured(): Boolean = AssistantCatalog.providers.any { hasKey(it.id) }

    private companion object {
        const val LEGACY_KEY = "assistant_key"
    }
}

/** Сетевой клиент ассистента: один POST на роль, разбор ошибок без утечки ключа. */
class AssistantService(private val store: AssistantStore) {

    suspend fun chat(role: AssistantRole, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = store.key()
            if (key.isBlank()) throw IllegalStateException("Ключ ассистента не задан")
            val base = store.baseUrl()
            if (base.isBlank()) throw IllegalStateException("Укажите базовый адрес сервера (https)")
            if (store.model.isBlank()) throw IllegalStateException("Выберите модель")
            val body = JSONObject()
                .put("model", store.model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", AssistantPrompts.system(role)))
                    .put(JSONObject().put("role", "user").put("content", prompt)))
                .put("max_tokens", role.maxTokens)
                .put("temperature", if (role == AssistantRole.CLASSIFIER || role == AssistantRole.QUERY_PLANNER) 0.1 else 0.5)
                .put("stream", false)
                .toString()
            val response = Net.fetch(
                base.trimEnd('/') + "/chat/completions",
                mapOf(
                    "Authorization" to "Bearer $key",
                    "Content-Type" to "application/json"
                ),
                attempts = 2,
                method = "POST",
                body = body
            )
            response.crossHostRedirect?.let {
                throw IllegalStateException("Сервис перенаправляет на $it — ключ не отправлен")
            }
            if (response.code !in 200..299) {
                throw IllegalStateException(AssistantParser.error(response.code, response.body))
            }
            AssistantParser.content(response.body)
                ?: throw IllegalStateException("Модель вернула пустой ответ")
        }
    }

    /**
     * Проверка ключа: Hugging Face — через whoami, остальные — через список
     * моделей `GET {base}/models` (200 только с валидным ключом).
     */
    suspend fun probe(): KeyCheck = withContext(Dispatchers.IO) {
        val key = store.key()
        if (key.isBlank()) return@withContext KeyCheck(KeyState.EMPTY, "Ключ не задан")
        val spec = AssistantCatalog.provider(store.provider)
        val base = store.baseUrl()
        if (base.isBlank()) {
            return@withContext KeyCheck(KeyState.INVALID, "Укажите базовый адрес сервера (https)")
        }
        val url = when (spec.probe) {
            AssistantProbe.HUGGINGFACE_WHOAMI -> AssistantCatalog.WHOAMI_URL
            AssistantProbe.NONE, AssistantProbe.OPENAI_MODELS -> base.trimEnd('/') + "/models"
        }
        val response = runCatching {
            Net.fetch(url, mapOf("Authorization" to "Bearer $key"), attempts = 1)
        }.getOrElse {
            store.setState(KeyState.OFFLINE)
            return@withContext KeyCheck(KeyState.OFFLINE, "Нет связи с ${spec.title}")
        }
        response.crossHostRedirect?.let {
            store.setState(KeyState.INVALID)
            return@withContext KeyCheck(KeyState.INVALID, "Сервис перенаправляет на $it — ключ не отправлен")
        }
        val result = when {
            response.code in 200..299 -> {
                val name = runCatching { JSONObject(response.body) }.getOrNull()
                    ?.optString("name").orEmpty()
                KeyCheck(
                    KeyState.VALID,
                    when {
                        spec.probe == AssistantProbe.HUGGINGFACE_WHOAMI && name.isNotBlank() -> "Ключ принят: $name"
                        spec.probe == AssistantProbe.HUGGINGFACE_WHOAMI -> "Ключ принят Hugging Face"
                        else -> "Ключ принят: ${spec.title}, модель ${store.model}"
                    }
                )
            }
            response.code == 401 || response.code == 403 ->
                KeyCheck(KeyState.INVALID, "${spec.title} отклонил ключ (${response.code})")
            response.code == 429 -> KeyCheck(KeyState.VALID, "Ключ активен, превышен лимит запросов")
            response.code == 404 -> KeyCheck(KeyState.INVALID, "Адрес ${url} не найден (404) — проверьте базовый адрес")
            else -> KeyCheck(KeyState.INVALID, "Ответ сервиса ${response.code}")
        }
        store.setState(result.state)
        result
    }
}

/** Результат веб-поиска для роли «Исследователь». */
data class WebHit(val title: String, val url: String, val snippet: String)

/**
 * Публичный веб-поиск без API-ключа (HTML-выдача DuckDuckGo).
 * Используется только ролью «Исследователь»: модель формулирует запрос,
 * приложение забирает фрагменты, модель делает выжимку.
 */
object WebResearch {

    private const val HTML_ENDPOINT = "https://html.duckduckgo.com/html/"
    private const val LITE_ENDPOINT = "https://lite.duckduckgo.com/lite/"

    suspend fun search(query: String, limit: Int = 5): List<WebHit> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val html = runCatching {
            Net.fetch(
                HTML_ENDPOINT + "?q=" + Net.urlEncode(trimmed) + "&kl=ru-ru",
                mapOf(
                    "Accept" to "text/html,application/xhtml+xml",
                    "Accept-Language" to "ru,en;q=0.8"
                ),
                attempts = 2
            )
        }.getOrNull()?.takeIf { it.code in 200..299 }?.body
            ?: runCatching {
                Net.fetch(
                    LITE_ENDPOINT + "?q=" + Net.urlEncode(trimmed) + "&kl=ru-ru",
                    mapOf("Accept" to "text/html"),
                    attempts = 2
                )
            }.getOrNull()?.takeIf { it.code in 200..299 }?.body
            ?: return emptyList()
        return parse(html).take(limit)
    }

    fun parse(html: String): List<WebHit> {
        val hits = ArrayList<WebHit>()
        val linkPattern = Regex(
            "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippetPattern = Regex(
            "<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippets = snippetPattern.findAll(html).map { cleanText(it.groupValues[1]) }.toList()
        linkPattern.findAll(html).forEachIndexed { index, match ->
            val url = unwrap(match.groupValues[1])
            if (url.isBlank() || url.startsWith("https://duckduckgo.com/y.js")) return@forEachIndexed
            val title = cleanText(match.groupValues[2])
            if (title.isBlank()) return@forEachIndexed
            hits += WebHit(title.take(160), url, snippets.getOrElse(index) { "" }.take(400))
        }
        if (hits.isNotEmpty()) return hits.distinctBy { it.url }

        // Резервный разбор lite-версии: ссылки в таблице, класс link-result-url.
        Regex(
            "<a[^>]*class=\"[^\"]*result-link[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(html).forEach { match ->
            val url = unwrap(match.groupValues[1])
            val title = cleanText(match.groupValues[2])
            if (url.isNotBlank() && title.isNotBlank()) hits += WebHit(title.take(160), url, "")
        }
        return hits.distinctBy { it.url }
    }

    /** DuckDuckGo отдаёт ссылки в редиректе /l/?uddg=<url>. */
    private fun unwrap(href: String): String {
        val raw = decode(href)
        val marker = raw.indexOf("uddg=")
        if (marker < 0) return if (raw.startsWith("http")) raw else ""
        val tail = raw.substring(marker + 5)
        val end = tail.indexOf('&').let { if (it < 0) tail.length else it }
        return runCatching { java.net.URLDecoder.decode(tail.substring(0, end), "UTF-8") }
            .getOrDefault(tail.substring(0, end))
    }

    private fun cleanText(html: String): String =
        decode(html.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun decode(text: String): String = text
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&#x2F;", "/")

    /** Текстовая выжимка найденного для передачи модели. */
    fun digest(hits: List<WebHit>): String = buildString {
        hits.forEachIndexed { index, hit ->
            append(index + 1).append(". ").append(hit.title)
            if (hit.snippet.isNotBlank()) append(" — ").append(hit.snippet)
            append('\n')
        }
    }
}
