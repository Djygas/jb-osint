package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.report.ModuleReportData
import ru.jbosint.osint.core.report.ModuleReportRow
import java.io.File
import java.security.MessageDigest

/**
 * ИИ-ассистент настройки модулей с экономией токенов.
 *
 * Принцип: сначала считается всё, что можно посчитать локально (код ответа,
 * структура JSON, число разобранных фактов), затем смотрится кэш рекомендаций
 * по отпечатку проблемы, и только оставшееся уходит в модель — одной пачкой на
 * несколько модулей, с коротким структурированным промтом.
 *
 * Здесь нет ни сети, ни Android API: логика проверяется unit-тестами, а вызов
 * модели делает AppViewModel через [AssistantService].
 */

/** Класс проблемы модуля, определённый локально — без обращения к модели. */
enum class ModuleIssue(val code: String, val label: String) {
    OK("ok", "работает"),
    NO_KEY("no_key", "ключ не задан"),
    KEY_REJECTED("key_rejected", "ключ отклонён"),
    OFFLINE("offline", "нет связи"),
    RATE_LIMIT("rate_limit", "лимит запросов"),
    AUTH("auth", "ключ не принят"),
    ENDPOINT("endpoint", "адрес или метод"),
    HTTP_CLIENT("http", "ответ 4xx"),
    SERVER("server", "ошибка сервиса"),
    NOT_JSON("not_json", "ответ не JSON"),
    EMPTY("empty", "0 фактов"),
    UNPARSED("unparsed", "поля не распознаны");

    companion object {
        fun of(code: String): ModuleIssue = entries.firstOrNull { it.code == code } ?: OK
    }
}

/** Откуда взялась рекомендация — это видно в интерфейсе и в отчёте. */
enum class AdviceOrigin(val label: String) {
    LOCAL("локально"),
    CACHE("из кэша"),
    AI("ИИ")
}

/** Разбор состояния одного модуля: проблемы, локальный совет, отпечаток для кэша. */
object ModuleAnalyzer {

    fun issues(block: DiagBlock): List<ModuleIssue> {
        val out = ArrayList<ModuleIssue>(2)
        val state = runCatching { KeyState.valueOf(block.keyState) }.getOrDefault(KeyState.UNCHECKED)
        when {
            state == KeyState.EMPTY -> out += ModuleIssue.NO_KEY
            state == KeyState.INVALID -> out += ModuleIssue.KEY_REJECTED
            state == KeyState.OFFLINE -> out += ModuleIssue.OFFLINE
        }
        when {
            block.code == 429 -> out += ModuleIssue.RATE_LIMIT
            block.code == 401 || block.code == 403 -> out += ModuleIssue.AUTH
            block.code == 404 || block.code == 405 -> out += ModuleIssue.ENDPOINT
            block.code in 400..499 && block.code != 429 && block.code != 401 &&
                block.code != 403 && block.code != 404 && block.code != 405 -> out += ModuleIssue.HTTP_CLIENT
            block.code >= 500 -> out += ModuleIssue.SERVER
        }
        val error = block.error.orEmpty().lowercase()
        if (error.contains("не является json") || error.contains("не json")) out += ModuleIssue.NOT_JSON
        // «0 фактов» — отдельная проблема только у исправного модуля: если ключ
        // отклонён или адрес неверен, вторая запись лишь запутает разбор.
        if (block.facts == 0 && out.isEmpty()) {
            val parsedNothing = error.contains("поля не распознаны") || error.contains("не распознаны")
            if (parsedNothing) out += ModuleIssue.UNPARSED else out += ModuleIssue.EMPTY
        }
        if (out.isEmpty()) out += ModuleIssue.OK
        return out.distinct()
    }

    /** Локальный совет: детерминированный, стоит ноль токенов. */
    fun localAdvice(block: DiagBlock, issues: List<ModuleIssue>): String = when {
        issues.contains(ModuleIssue.OK) -> buildString {
            append("Модуль отвечает")
            if (block.facts > 0) append(": разобрано фактов — ${block.facts}")
            if (block.elapsedMs > 0) append(", ответ за ${block.elapsedMs} мс")
            append('.')
        }
        issues.contains(ModuleIssue.NO_KEY) ->
            "Ключ не задан. Откройте карточку модуля, вставьте ключ из кабинета сервиса и нажмите «Проверить»."
        issues.contains(ModuleIssue.KEY_REJECTED) ->
            "Сервис отклонил ключ. Перевыпустите его в кабинете, вставьте целиком (без пробелов и обрезки). " +
                "Для VK нужен пользовательский токен, сервисный не подойдёт."
        issues.contains(ModuleIssue.OFFLINE) ->
            "Нет связи с сервисом. Проверьте интернет или VPN; ключ при этом не меняйте — он не проверялся."
        issues.contains(ModuleIssue.RATE_LIMIT) ->
            "Сервис ограничил частоту запросов (429). Повторите через 5–15 минут и не запускайте модуль в цикле."
        issues.contains(ModuleIssue.AUTH) ->
            "Ключ не принят (401/403): неверный способ передачи. Нажмите «Починить» — приложение подберёт заголовок " +
                "или параметр адреса заново."
        issues.contains(ModuleIssue.ENDPOINT) ->
            "Адрес или метод неверны (404/405). «Починить» переберёт путь и метод; если не поможет — сверьте путь " +
                "с документацией сервиса."
        issues.contains(ModuleIssue.NOT_JSON) ->
            "Сервис вернул не JSON — такой модуль не подходит: приложение разбирает только JSON API."
        issues.contains(ModuleIssue.SERVER) ->
            "Ошибка на стороне сервиса (${block.code}). Подождите и повторите; ключ и адрес менять не нужно."
        issues.contains(ModuleIssue.HTTP_CLIENT) ->
            "Запрос отклонён (${block.code}). Обычно причина — параметры запроса или тип данных: проверьте " +
                "«Тестовый запрос модуля» и правила разбора."
        issues.contains(ModuleIssue.UNPARSED) ->
            "Ответ получен, но поля не распознаны. Задайте правила «JSON-путь → поле» — структура ответа в отчёте " +
                "диагностики."
        else -> buildString {
            append("Ответ получен, фактов 0.")
            DiagSamples.acceptedFor(block.moduleId)?.let { append(" Модуль принимает $it.") }
            if (!block.testQuery.isNullOrBlank()) append(" Проверяли запросом «${block.testQuery}».")
        }
    }

    /**
     * Нужен ли ИИ. Смысл есть только там, где локальное правило не даёт ответа:
     * нестандартное тело ответа, неизвестные поля, отклонённые параметры.
     * Всё остальное (нет ключа, нет сети, лимит, 5xx) решается правилом.
     */
    fun needsAi(block: DiagBlock, issues: List<ModuleIssue>): Boolean {
        if (block.code >= 500) return false
        return issues.any {
            it == ModuleIssue.UNPARSED ||
                it == ModuleIssue.HTTP_CLIENT ||
                (it == ModuleIssue.EMPTY && block.kind.contains("пользовательск") && block.fields.isBlank())
        }
    }

    /** Класс кода ответа: 4xx разных видов не должны делить одну запись кэша. */
    private fun codeClass(code: Int): String = when {
        code == 0 -> "none"
        code in 200..299 -> "2xx"
        code == 401 || code == 403 -> "auth"
        code == 404 || code == 405 -> "route"
        code == 429 -> "rate"
        code in 400..499 -> "4xx"
        code >= 500 -> "5xx"
        else -> "other"
    }

    /**
     * Отпечаток проблемы: одинаковые условия дают одинаковый ключ кэша.
     * Числа из структуры выбрасываются — «массив, 3» и «массив, 7» это один
     * и тот же ответ по форме.
     */
    fun fingerprint(block: DiagBlock, issues: List<ModuleIssue>): String {
        val shape = block.structure.replace(Regex("\\d+"), "#").take(400)
        val raw = listOf(
            block.moduleId.ifBlank { block.title },
            issues.map { it.code }.sorted().joinToString("+"),
            codeClass(block.code),
            block.kind,
            shape,
            block.fields.replace(Regex("\\s+"), " ").take(200)
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}

/** Готовая рекомендация по модулю. */
data class ModuleAdvice(
    val moduleId: String,
    val title: String,
    val kind: String,
    val status: String,
    val issues: List<ModuleIssue>,
    val code: Int,
    val facts: Int,
    val advice: String,
    val steps: List<String> = emptyList(),
    val origin: AdviceOrigin = AdviceOrigin.LOCAL,
    val elapsedMs: Long = 0L,
    val fingerprint: String = ""
) {
    val issuesText: String get() = issues.filter { it != ModuleIssue.OK }.joinToString(", ") { it.label }
}

/** Запись кэша рекомендаций: одна проблема модуля — один ответ модели. */
data class ModuleCacheEntry(
    val fingerprint: String,
    val moduleId: String,
    val diagnosis: String,
    val steps: List<String>,
    val provider: String,
    val model: String,
    val tokens: Int,
    val createdAt: Long,
    val hits: Int = 0
)

/** Один фактический вызов модели — для аудита и оценки экономии. */
data class AiCallEntry(
    val at: Long,
    val purpose: String,
    val modules: List<String>,
    val promptChars: Int,
    val answerChars: Int,
    val tokensIn: Int,
    val tokensOut: Int,
    val provider: String,
    val model: String,
    val ok: Boolean,
    val note: String = ""
)

/** Счётчики экономии: показываются в интерфейсе и попадают в отчёт. */
data class AssistantStats(
    val aiCalls: Int = 0,
    val cacheHits: Int = 0,
    val localOnly: Int = 0,
    val tokensSpent: Int = 0,
    val tokensSaved: Int = 0,
    val lastRunAt: Long = 0L
) {
    /** Сколько запросов к модели удалось не делать. */
    val avoidedCalls: Int get() = cacheHits + localOnly

    val savedShare: Int
        get() {
            val total = tokensSpent + tokensSaved
            return if (total <= 0) 0 else (tokensSaved * 100 / total)
        }
}

/** Оценка токенов без токенизатора: 3 символа ≈ 1 токен для русского JSON. */
object TokenEstimate {
    fun of(chars: Int): Int = if (chars <= 0) 0 else (chars + 2) / 3
}

/**
 * Хранилище кэша рекомендаций и журнала обращений к ИИ.
 * Один JSON-файл во внутренней папке приложения; секретов в нём нет —
 * только тексты проблем и ответы модели.
 */
class ModuleAssistantStore(private val file: File) {

    private val cache = LinkedHashMap<String, ModuleCacheEntry>()
    private val journal = ArrayList<AiCallEntry>()
    private var stats = AssistantStats()

    init {
        load()
    }

    fun cached(fingerprint: String, now: Long = System.currentTimeMillis()): ModuleCacheEntry? {
        val entry = cache[fingerprint] ?: return null
        if (now - entry.createdAt > TTL_MS) {
            cache.remove(fingerprint)
            return null
        }
        return entry
    }

    fun put(entry: ModuleCacheEntry) {
        cache[entry.fingerprint] = entry
        while (cache.size > MAX_ENTRIES) {
            val oldest = cache.values.minByOrNull { it.createdAt } ?: break
            cache.remove(oldest.fingerprint)
        }
        save()
    }

    fun touch(fingerprint: String) {
        val entry = cache[fingerprint] ?: return
        cache[fingerprint] = entry.copy(hits = entry.hits + 1)
        stats = stats.copy(
            cacheHits = stats.cacheHits + 1,
            // Ответ из кэша — это запрос, который не пришлось оплачивать.
            tokensSaved = stats.tokensSaved + entry.tokens
        )
        save()
    }

    fun recordLocal(count: Int) {
        if (count <= 0) return
        stats = stats.copy(localOnly = stats.localOnly + count, lastRunAt = System.currentTimeMillis())
        save()
    }

    fun recordCall(entry: AiCallEntry) {
        journal += entry
        while (journal.size > MAX_JOURNAL) journal.removeAt(0)
        stats = stats.copy(
            aiCalls = stats.aiCalls + if (entry.ok) 1 else 0,
            tokensSpent = stats.tokensSpent + entry.tokensIn + entry.tokensOut,
            lastRunAt = entry.at
        )
        save()
    }

    fun recordSaved(tokens: Int) {
        if (tokens <= 0) return
        stats = stats.copy(tokensSaved = stats.tokensSaved + tokens)
        save()
    }

    fun journal(): List<AiCallEntry> = journal.toList().asReversed()

    fun stats(): AssistantStats = stats

    fun cacheSize(): Int = cache.size

    fun clearCache() {
        cache.clear()
        save()
    }

    fun clearJournal() {
        journal.clear()
        stats = AssistantStats()
        save()
    }

    // ------------------------------------------------------------- сохранение

    private fun load() {
        if (!file.exists()) return
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        root.optJSONArray("cache")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val fingerprint = obj.optString("fp")
                if (fingerprint.isBlank()) continue
                cache[fingerprint] = ModuleCacheEntry(
                    fingerprint = fingerprint,
                    moduleId = obj.optString("module"),
                    diagnosis = obj.optString("diagnosis"),
                    steps = obj.optJSONArray("steps").toStringList(),
                    provider = obj.optString("provider"),
                    model = obj.optString("model"),
                    tokens = obj.optInt("tokens"),
                    createdAt = obj.optLong("at"),
                    hits = obj.optInt("hits")
                )
            }
        }
        root.optJSONArray("journal")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                journal += AiCallEntry(
                    at = obj.optLong("at"),
                    purpose = obj.optString("purpose"),
                    modules = obj.optJSONArray("modules").toStringList(),
                    promptChars = obj.optInt("prompt_chars"),
                    answerChars = obj.optInt("answer_chars"),
                    tokensIn = obj.optInt("tokens_in"),
                    tokensOut = obj.optInt("tokens_out"),
                    provider = obj.optString("provider"),
                    model = obj.optString("model"),
                    ok = obj.optBoolean("ok", true),
                    note = obj.optString("note")
                )
            }
        }
        root.optJSONObject("stats")?.let { obj ->
            stats = AssistantStats(
                aiCalls = obj.optInt("ai_calls"),
                cacheHits = obj.optInt("cache_hits"),
                localOnly = obj.optInt("local_only"),
                tokensSpent = obj.optInt("tokens_spent"),
                tokensSaved = obj.optInt("tokens_saved"),
                lastRunAt = obj.optLong("last_run")
            )
        }
    }

    private fun save() {
        val root = JSONObject()
        root.put("version", 1)
        root.put("cache", JSONArray().also { array ->
            cache.values.forEach { entry ->
                array.put(JSONObject()
                    .put("fp", entry.fingerprint)
                    .put("module", entry.moduleId)
                    .put("diagnosis", entry.diagnosis)
                    .put("steps", JSONArray().also { steps -> entry.steps.forEach { steps.put(it) } })
                    .put("provider", entry.provider)
                    .put("model", entry.model)
                    .put("tokens", entry.tokens)
                    .put("at", entry.createdAt)
                    .put("hits", entry.hits))
            }
        })
        root.put("journal", JSONArray().also { array ->
            journal.forEach { entry ->
                array.put(JSONObject()
                    .put("at", entry.at)
                    .put("purpose", entry.purpose)
                    .put("modules", JSONArray().also { names -> entry.modules.forEach { names.put(it) } })
                    .put("prompt_chars", entry.promptChars)
                    .put("answer_chars", entry.answerChars)
                    .put("tokens_in", entry.tokensIn)
                    .put("tokens_out", entry.tokensOut)
                    .put("provider", entry.provider)
                    .put("model", entry.model)
                    .put("ok", entry.ok)
                    .put("note", entry.note))
            }
        })
        root.put("stats", JSONObject()
            .put("ai_calls", stats.aiCalls)
            .put("cache_hits", stats.cacheHits)
            .put("local_only", stats.localOnly)
            .put("tokens_spent", stats.tokensSpent)
            .put("tokens_saved", stats.tokensSaved)
            .put("last_run", stats.lastRunAt))
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i).trim()
            if (value.isNotEmpty()) out += value
        }
        return out
    }

    companion object {
        /** Рекомендация «стареет» месяц: за это время меняются и API, и тарифы. */
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRIES = 240
        private const val MAX_JOURNAL = 100
    }
}

/** Что уходит в модель по одному модулю. */
data class ModulePromptItem(
    val number: Int,
    val title: String,
    val kind: String,
    val status: String,
    val code: Int,
    val facts: Int,
    val structure: String,
    val fields: String,
    val error: String
)

/** Промты модульного ассистента: коротко, структурированно, с жёстким форматом ответа. */
object ModulePrompts {

    const val SYSTEM = "Ты — инженер поддержки OSINT-приложения. По состоянию модулей " +
        "(код ответа, структура JSON, поля) определи причину сбоя и дай короткие шаги настройки. " +
        "Не выдумывай адреса API и не упоминай ключи. Отвечай только JSON."

    private const val FIELD_LIMIT = 420
    private const val STRUCTURE_LIMIT = 520

    fun batch(items: List<ModulePromptItem>): String {
        val modules = JSONArray()
        items.forEach { item ->
            modules.put(JSONObject()
                .put("n", item.number)
                .put("name", item.title.take(60))
                .put("kind", if (item.kind.contains("пользовательск")) "custom" else "builtin")
                .put("status", item.status.take(60))
                .put("code", item.code)
                .put("facts", item.facts)
                .apply {
                    if (item.structure.isNotBlank()) put("json", item.structure.take(STRUCTURE_LIMIT))
                    if (item.fields.isNotBlank()) put("rules", item.fields.take(FIELD_LIMIT))
                    if (item.error.isNotBlank()) put("error", DiagMask.text(item.error).take(200))
                })
        }
        val payload = JSONObject()
            .put("task", "diagnose_modules")
            .put("modules", modules)
        return payload.toString() + "\n\n" +
            "Для каждого модуля верни причину и до двух шагов настройки. Если данных мало — шаг «уточнить " +
            "у сервиса». Если у модуля нестандартный JSON, добавь поле rules со строкой вида " +
            "\"data.items[].email → email; data.items[].phone → phone\". Ответ — строго JSON-массив без " +
            "пояснений: [{\"n\":1,\"cause\":\"…\",\"steps\":[\"…\"],\"rules\":\"…\"}]. Каждое поле — до 140 символов."
    }
}

/** Разбор ответа модели: устойчив к ```json, префиксам и одиночному объекту. */
object ModuleAnswerParser {

    data class Answer(val cause: String, val steps: List<String>, val rules: String)

    fun parse(raw: String, expected: Set<Int>): Map<Int, Answer> {
        val text = AssistantParser.clean(raw)
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: firstArray(text)
            ?: firstObject(text)?.let { JSONArray().put(it) }
            ?: return emptyMap()
        val out = LinkedHashMap<Int, Answer>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val n = obj.optInt("n", obj.optInt("number", 0))
            if (n <= 0 || (expected.isNotEmpty() && n !in expected)) continue
            val cause = obj.optString("cause").ifBlank { obj.optString("diagnosis") }
                .trim().take(300)
            val steps = ArrayList<String>()
            obj.optJSONArray("steps")?.let { list ->
                for (j in 0 until list.length()) {
                    val step = list.optString(j).trim().take(240)
                    if (step.isNotEmpty()) steps += step
                }
            }
            if (steps.isEmpty()) {
                obj.optString("steps").split('\n').forEach { line ->
                    val clean = line.trim().trim('-', '•', '*', ' ').take(240)
                    if (clean.isNotEmpty()) steps += clean
                }
            }
            val rules = obj.optString("rules").trim().take(300)
            if (cause.isBlank() && steps.isEmpty() && rules.isBlank()) continue
            out[n] = Answer(cause, steps.take(3), rules)
        }
        return out
    }

    private fun firstArray(text: String): JSONArray? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return runCatching { JSONArray(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun firstObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}

/** Итог разбора пачки модулей до обращения к модели. */
data class ModulePlan(
    val advices: List<ModuleAdvice>,
    /** Пачки, которые надо отправить модели: не больше [BATCH_SIZE] модулей в каждой. */
    val batches: List<List<ModulePromptItem>>,
    val cacheHits: Int,
    val localCount: Int,
    val aiItems: Int
) {
    val needAi: Boolean get() = batches.isNotEmpty()
}

/**
 * Планировщик: локальный разбор → кэш → пачки для модели.
 * Никакой сети; всё, что можно решить без модели, решается здесь.
 */
object ModulePlanner {

    const val BATCH_SIZE = 5

    fun plan(blocks: List<DiagBlock>, store: ModuleAssistantStore, now: Long = System.currentTimeMillis()): ModulePlan {
        val advices = ArrayList<ModuleAdvice>(blocks.size)
        val pending = ArrayList<Pair<Int, ModuleAdvice>>()
        var cacheHits = 0
        var local = 0

        blocks.forEachIndexed { index, block ->
            val issues = ModuleAnalyzer.issues(block)
            val fingerprint = ModuleAnalyzer.fingerprint(block, issues)
            val cached = if (ModuleAnalyzer.needsAi(block, issues)) store.cached(fingerprint, now) else null
            when {
                cached != null -> {
                    cacheHits++
                    advices += adviceOf(block, issues, fingerprint, AdviceOrigin.CACHE,
                        diagnosis = cached.diagnosis, steps = cached.steps)
                }
                ModuleAnalyzer.needsAi(block, issues) -> {
                    val advice = adviceOf(block, issues, fingerprint, AdviceOrigin.LOCAL,
                        diagnosis = ModuleAnalyzer.localAdvice(block, issues))
                    advices += advice
                    pending += (index + 1) to advice
                }
                else -> {
                    local++
                    advices += adviceOf(block, issues, fingerprint, AdviceOrigin.LOCAL,
                        diagnosis = ModuleAnalyzer.localAdvice(block, issues))
                }
            }
        }

        val byNumber = blocks.indices.associateBy { it + 1 }
        val batches = pending.chunked(BATCH_SIZE).map { chunk ->
            chunk.map { (number, advice) ->
                val block = blocks[byNumber.getValue(number)]
                ModulePromptItem(
                    number = number,
                    title = advice.title,
                    kind = advice.kind,
                    status = advice.status,
                    code = advice.code,
                    facts = advice.facts,
                    structure = block.structure,
                    fields = block.fields,
                    error = block.error.orEmpty()
                )
            }
        }
        return ModulePlan(advices, batches, cacheHits, local, pending.size)
    }

    private fun adviceOf(
        block: DiagBlock,
        issues: List<ModuleIssue>,
        fingerprint: String,
        origin: AdviceOrigin,
        diagnosis: String,
        steps: List<String> = emptyList()
    ): ModuleAdvice = ModuleAdvice(
        moduleId = block.moduleId.ifBlank { block.title },
        title = block.title,
        kind = block.kind,
        status = issues.firstOrNull { it != ModuleIssue.OK }?.label ?: ModuleIssue.OK.label,
        issues = issues,
        code = block.code,
        facts = block.facts,
        advice = diagnosis,
        steps = steps,
        origin = origin,
        elapsedMs = block.elapsedMs,
        fingerprint = fingerprint
    )
}

/**
 * Применение ответов модели: обновляет рекомендации, пишет кэш и журнал.
 * Чистая функция над планом — вызывается из ViewModel после сетевого вызова.
 */
object ModuleApply {

    fun apply(
        plan: ModulePlan,
        answers: Map<Int, ModuleAnswerParser.Answer>,
        provider: String,
        model: String
    ): List<ModuleAdvice> {
        return plan.advices.mapIndexed { index, advice ->
            val answer = answers[index + 1] ?: return@mapIndexed advice
            val text = buildString {
                if (answer.cause.isNotBlank()) append(answer.cause)
                if (answer.rules.isNotBlank()) {
                    if (isNotEmpty()) append(' ')
                    append("Правила разбора: ").append(answer.rules)
                }
            }.trim()
            if (text.isBlank() && answer.steps.isEmpty()) advice
            else advice.copy(advice = text.ifBlank { advice.advice }, steps = answer.steps, origin = AdviceOrigin.AI)
        }
    }

    fun cacheEntries(
        plan: ModulePlan,
        answers: Map<Int, ModuleAnswerParser.Answer>,
        provider: String,
        model: String,
        tokens: Int,
        now: Long = System.currentTimeMillis()
    ): List<ModuleCacheEntry> = plan.advices.mapIndexedNotNull { index, advice ->
        val answer = answers[index + 1] ?: return@mapIndexedNotNull null
        val text = buildString {
            if (answer.cause.isNotBlank()) append(answer.cause)
            if (answer.rules.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append("Правила разбора: ").append(answer.rules)
            }
        }.trim()
        if (text.isBlank()) return@mapIndexedNotNull null
        ModuleCacheEntry(
            fingerprint = advice.fingerprint,
            moduleId = advice.moduleId,
            diagnosis = text,
            steps = answer.steps,
            provider = provider,
            model = model,
            // Стоимость одного ответа: токены пачки делятся на число разобранных модулей.
            tokens = tokens / answers.size.coerceAtLeast(1),
            createdAt = now
        )
    }
}

/** Сборка отчёта по модулям из готовых рекомендаций. */
object ModuleReportBuilder {

    fun data(
        query: String,
        appVersion: String,
        android: String,
        advices: List<ModuleAdvice>,
        stats: AssistantStats,
        now: Long = System.currentTimeMillis()
    ): ModuleReportData {
        val rows = advices.mapIndexed { index, advice ->
            ModuleReportRow(
                number = index + 1,
                title = advice.title,
                kind = advice.kind,
                status = advice.status,
                code = advice.code,
                facts = advice.facts,
                issues = advice.issuesText,
                advice = advice.advice,
                source = advice.origin.label,
                steps = advice.steps
            )
        }
        val working = advices.count { it.issues.contains(ModuleIssue.OK) }
        val failed = advices.count { advice ->
            advice.issues.any { it != ModuleIssue.OK && it != ModuleIssue.EMPTY }
        }
        val empty = advices.count { it.issues.contains(ModuleIssue.EMPTY) }
        return ModuleReportData(
            query = query,
            appVersion = appVersion,
            android = android,
            generatedAt = now,
            rows = rows,
            stats = listOf(
                "Модулей проверено" to advices.size.toString(),
                "Работают" to working.toString(),
                "Требуют настройки" to failed.toString(),
                "Отвечают, но 0 фактов" to empty.toString(),
                "Обращений к ИИ" to stats.aiCalls.toString(),
                "Ответов из кэша" to stats.cacheHits.toString(),
                "Решено локально" to stats.localOnly.toString(),
                "Токенов потрачено" to stats.tokensSpent.toString(),
                "Токенов сэкономлено" to stats.tokensSaved.toString(),
                "Экономия" to "${stats.savedShare}%"
            ),
            notes = listOf(
                "Локальный анализ (коды ответов, структура JSON, число фактов) выполняется без обращения к модели.",
                "Рекомендации кэшируются по отпечатку проблемы на 30 дней: повторная проверка тех же условий не оплачивается.",
                "Секреты в отчёт не попадают: адреса показаны без параметров авторизации, значения ключей заменены на ***."
            )
        )
    }
}
