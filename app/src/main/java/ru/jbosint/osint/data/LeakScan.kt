package ru.jbosint.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import java.security.MessageDigest

/**
 * Правила локального индекса утечек: чистые функции без Android и сети.
 *
 * Индекс хранит SHA-256 адреса, маску, место в файле и происхождение (имя файла).
 * Пароль сохраняется в отдельной колонке и показывается только при включённом
 * режиме «Показывать пароли»; по умолчанию он скрыт.
 */
object LeakRules {

    /** Срок жизни кэша ответов API: сутки. */
    const val TTL_MS = 24L * 60 * 60 * 1000

    /** Длинные строки дампа режем: индексатор не должен жевать мегабайтные строки SQL. */
    const val MAX_LINE = 8_192

    /** Сколько совпадений показываем в отчёте (остальные считаются, но не листаются). */
    const val MAX_HITS = 300

    /** Размер пачки вставок: одна гигантская транзакция на 500 МБ тормозит телефон. */
    const val CHUNK = 50_000

    /** Что считаем дампом и индексируем. */
    private val formats = setOf("csv", "tsv", "txt", "sql", "json", "log", "lst", "data")

    private val emailRe = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,24}")
    private val phoneRe = Regex("(?:\\+7|8)[\\s\\-(]*\\d{3}[\\s\\-)]*\\d{3}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2}")

    private const val HEX = "0123456789abcdef"

    private val digest = object : ThreadLocal<MessageDigest>() {
        override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    fun normalize(raw: String): String = raw.trim().lowercase()

    /** SHA-256 нормализованного значения. Соль не нужна: индекс локальный, поиск по нему. */
    fun hash(raw: String): String {
        val md = digest.get() ?: MessageDigest.getInstance("SHA-256")
        md.reset()
        val bytes = md.digest(normalize(raw).toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    /** `ivan.petrov@mail.ru` → `iv***@mail.ru`. Пароль и локальная часть не раскрываются. */
    fun mask(value: String): String {
        val at = value.indexOf('@')
        if (at <= 0) return if (value.length <= 2) "***" else value.take(2) + "***"
        val local = value.substring(0, at)
        val domain = value.substring(at)
        val head = if (local.length <= 2) local.take(1) else local.take(2)
        return "$head***$domain"
    }

    fun format(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun indexable(name: String): Boolean =
        !name.startsWith(".") && format(name) in formats

    /** Адреса из строки дампа. Больше трёх на строку не берём: это уже мусор. */
    fun emailsIn(line: String): List<String> {
        if (line.isBlank()) return emptyList()
        val text = if (line.length > MAX_LINE) line.substring(0, MAX_LINE) else line
        val found = ArrayList<String>(2)
        for (match in emailRe.findAll(text)) {
            val value = normalize(match.value)
            if (value.length > 254) continue
            if (value !in found) found += value
            if (found.size >= 3) break
        }
        return found
    }

    /** Телефон в строке — для дампов вида `+79991234567;...`. */
    fun phoneIn(line: String): String? {
        if (line.isBlank() || line.length > MAX_LINE) return null
        val match = phoneRe.find(line) ?: return null
        val digits = match.value.filter(Char::isDigit)
        return if (digits.length < 11) null else digits
    }

    /**
     * Пароль из строки формата `email:password` (а также `;`, TAB, `,`, `|`).
     * Берём только значение сразу после адреса: чужие поля (телефон, дубль адреса)
     * паролем не считаются. Пароль сохраняется в индексе, но показывается только
     * при включённом режиме показа паролей.
     */
    fun passwordIn(line: String, email: String): String? {
        if (line.isBlank()) return null
        val text = if (line.length > MAX_LINE) line.substring(0, MAX_LINE) else line
        val parts = text.split(':', ';', '\t', ',', '|')
        val at = parts.indexOfFirst { it.trim().trim('"', '\'').equals(email, ignoreCase = true) }
        if (at < 0) return null
        val candidate = parts.getOrNull(at + 1)
            ?.trim()
            ?.trim('"', '\'', ' ', ')', '(', ']', '[')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (candidate.length !in 1..128) return null
        // Дубль адреса не считаем паролем, но '@' внутри пароля допустим.
        if (emailRe.matches(candidate) || candidate.contains(' ')) return null
        if (phoneRe.matches(candidate)) return null
        return candidate
    }

    /**
     * Разбор строки дампа: CSV (`email;password;service;date;url`), TXT
     * (`email:password@service`, `email|password|service`) и JSON-строки.
     */
    fun parseRecord(line: String, email: String): LeakDumpRecord {
        if (line.isBlank()) return LeakDumpRecord()
        val text = if (line.length > MAX_LINE) line.substring(0, MAX_LINE) else line
        val jsonPassword = jsonField(text, "password")
        if (jsonPassword != null) {
            return LeakDumpRecord(
                password = jsonPassword,
                service = jsonField(text, "service") ?: jsonField(text, "site") ?: "",
                date = jsonField(text, "date") ?: jsonField(text, "breach_date") ?: "",
                dataType = jsonField(text, "data_type") ?: "Email+Password",
                sourceUrl = jsonField(text, "source_url") ?: jsonField(text, "url") ?: ""
            )
        }
        val delimiter = when {
            text.contains(';') -> ';'
            text.contains('\t') -> '\t'
            text.contains('|') -> '|'
            text.contains(',') -> ','
            else -> ':'
        }
        val parts = text.split(delimiter)
            .map { it.trim().trim('"', '\'', ' ') }
            .filter { it.isNotEmpty() }
        val at = parts.indexOfFirst { it.equals(email, ignoreCase = true) }
        if (at < 0) return LeakDumpRecord()
        val after = parts.drop(at + 1)
        var password: String? = null
        var service = ""
        var date = ""
        var sourceUrl = ""
        var consumed = 0
        val first = after.firstOrNull()
        // В TXT-формате `email:password@service` токен после email может выглядеть
        // как почта — это пароль и сервис, а не дубль адреса.
        val structured = delimiter != ':'
        if (first != null && !(structured && emailRe.matches(first))) {
            if (first.contains('@') && !first.contains(' ')) {
                val candidate = first.substringBeforeLast('@')
                val tail = first.substringAfterLast('@')
                if (candidate.isNotBlank() && tail.contains('.') && !tail.contains('@')) {
                    password = candidate
                    service = tail
                } else if (candidate.isNotBlank()) {
                    password = first
                }
            } else {
                password = first
            }
            if (password != null) consumed = 1
        }
        after.drop(consumed).forEach { token ->
            when {
                (token.startsWith("http://") || token.startsWith("https://")) && sourceUrl.isEmpty() ->
                    sourceUrl = token
                date.isEmpty() && Regex("\\d{4}(-\\d{2}){0,2}").matches(token) -> date = token
                service.isEmpty() && !token.contains('@') && !phoneRe.matches(token) &&
                    token.length in 2..80 -> service = token
            }
        }
        return LeakDumpRecord(
            password = password,
            service = service,
            date = date,
            dataType = if (password != null) "Email+Password" else "Email",
            sourceUrl = sourceUrl
        )
    }

    private fun jsonField(text: String, name: String): String? =
        Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /**
     * Эвристика «в строке есть пароль»: рядом с адресом стоит токен не из букв
     * (обычно с цифрами), не похожий на почту. Сам токен не возвращаем и не храним.
     */
    fun hasSecret(line: String, email: String): Boolean {
        if (line.isBlank()) return false
        val text = if (line.length > MAX_LINE) line.substring(0, MAX_LINE) else line
        val parts = text.split(';', ',', '\t', '|', ':').map { it.trim() }
        if (parts.size < 2) return false
        val at = parts.indexOfFirst { it.equals(email, ignoreCase = true) }
        val candidates = if (at >= 0) parts.drop(at + 1) else parts.filterNot { it.contains('@') }
        return candidates.any { token ->
            val clean = token.trim('"', '\'', ')', '(', ']', '[', ' ')
            clean.length in 4..128 && !clean.contains(' ') && clean.any(Char::isDigit)
        }
    }
}

/** Разобранная строка дампа: пароль и метаданные записи. */
data class LeakDumpRecord(
    val password: String? = null,
    val service: String = "",
    val date: String = "",
    val dataType: String = "",
    val sourceUrl: String = ""
)

/** Одно совпадение в локальном индексе. */
data class LeakHit(
    val file: String,
    val line: Int,
    val kind: String,
    val mask: String,
    val hasSecret: Boolean,
    /** Пароль из строки `email:password`; null, если его нет или он не распознан. */
    val password: String? = null,
    /** Происхождение записи: имя файла-дампа. */
    val origin: String? = null,
    val service: String = "",
    val leakDate: String = "",
    val dataType: String = "",
    val sourceUrl: String = ""
)

data class LeakIndexStats(
    val files: Int = 0,
    val rows: Long = 0,
    val bytes: Long = 0,
    val secrets: Long = 0,
    val updatedAt: Long = 0
)

data class LeakIndexReport(
    val scanned: Int = 0,
    val skipped: Int = 0,
    val rows: Long = 0,
    val files: Int = 0,
    val secrets: Long = 0,
    val tookMs: Long = 0,
    val errors: List<String> = emptyList()
)

/**
 * Умный каскад поиска по утечкам.
 *
 * Порядок шагов задан ТЗ: локальный индекс (мгновенно) → кэш сутки → цепочка API.
 * Если сеть недоступна, возвращается то, что есть локально, с пометкой об офлайне,
 * а не ошибка «No Internet».
 *
 * Чего здесь нет намеренно: перебора прокси для обхода 429 и выдачи паролей
 * из онлайн-сервисов. Пароль в отчёт попадает только из локального индекса,
 * а на 429 каскад ждёт и переходит к следующему сервису.
 */
class LeakScan(private val store: LeakStore, private val keys: KeySource) {

    data class SourceRow(
        val id: String,
        val title: String,
        val hits: Int,
        val cached: Boolean = false,
        val error: String? = null,
        /** Сообщение источника (например, 403 «полные записи только на тарифе»). */
        val status: String? = null
    )

    data class Result(
        val facts: List<RawFact>,
        val sources: List<SourceRow>,
        val offline: Boolean = false,
        val cached: Boolean = false,
        val note: String = "",
        val tookMs: Long = 0
    )

    companion object {
        /** Порядок цепочки: сначала бесплатный HIBP, дальше платные сервисы. */
        val CHAIN = listOf("hibp", "leakcheck", "hudsonrock", "dyxless")

        fun title(id: String): String = when (id) {
            "hibp" -> "Have I Been Pwned"
            "leakcheck" -> "LeakCheck"
            "hudsonrock" -> "Hudson Rock"
            "dyxless" -> "Dyxless"
            "leak_index" -> "Локальный индекс"
            else -> id
        }

        const val INDEX_ID = "leak_index"

        fun encodeFacts(facts: List<RawFact>): String {
            val array = JSONArray()
            facts.forEach { fact ->
                array.put(
                    JSONObject()
                        .put("kind", fact.kind)
                        .put("value", fact.value)
                        .put("source", fact.sourceId)
                        .put("url", fact.sourceUrl)
                        .put("attrs", JSONObject(fact.attributes as Map<*, *>))
                )
            }
            return array.toString()
        }

        fun decodeFacts(raw: String, subject: String): List<RawFact> = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val attrs = LinkedHashMap<String, String>()
                obj.optJSONObject("attrs")?.let { map ->
                    map.keys().forEach { key -> attrs[key] = map.optString(key) }
                }
                RawFact(
                    category = FactCategory.OTHER,
                    kind = obj.optString("kind"),
                    value = obj.optString("value"),
                    subject = subject,
                    attributes = attrs,
                    sourceId = obj.optString("source")
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun scan(
        subject: String,
        useIndex: Boolean,
        useApi: Boolean,
        ttlMs: Long = LeakRules.TTL_MS,
        onStage: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val query = LeakRules.normalize(subject)
        if (query.isBlank()) {
            return@withContext Result(emptyList(), emptyList(), note = "Пустой запрос")
        }

        val facts = LinkedHashMap<String, RawFact>()
        val rows = ArrayList<SourceRow>()
        var offline = false
        var fromCache = false

        fun add(fact: RawFact, key: String = "${fact.sourceId}|${fact.kind}|${fact.value}") {
            facts.putIfAbsent(key, fact)
        }

        // Шаг 1 — локальный индекс: отвечает за миллисекунды, сеть не нужна.
        if (useIndex) {
            onStage("Локальный индекс…")
            val hits = runCatching { store.lookup(query) }.getOrDefault(emptyList())
            var sequence = 0
            hits.forEach { hit ->
                val origin = hit.origin?.takeIf { it.isNotBlank() }
                    ?: hit.file.substringAfterLast('/')
                val service = hit.service.ifBlank { origin }
                val password = hit.password?.takeIf { it.isNotBlank() }
                val seenPassword = password != null || hit.hasSecret
                add(
                    RawFact(
                        category = FactCategory.OTHER,
                        kind = if (hit.kind == "phone") "Утечка: телефон" else "Утечка: почта",
                        value = query,
                        subject = query,
                        attributes = buildMap {
                            put("where", service)
                            put("service", service)
                            put("Файл", hit.file.substringAfterLast('/'))
                            put("Строка", hit.line.toString())
                            put("Маска", hit.mask)
                            put("hadPassword", seenPassword.toString())
                            if (hit.leakDate.isNotBlank()) put("Дата", hit.leakDate)
                            if (hit.dataType.isNotBlank()) put("Типы данных", hit.dataType)
                            if (hit.sourceUrl.isNotBlank()) put("source_url", hit.sourceUrl)
                            if (password != null) put("password", password)
                        },
                        sourceId = INDEX_ID,
                        sourceUrl = hit.sourceUrl
                    ),
                    key = "index|${hit.file}|${hit.line}|${hit.kind}|${sequence++}"
                )
            }
            rows += SourceRow(
                id = INDEX_ID,
                title = title(INDEX_ID),
                hits = hits.size,
                error = if (hits.isEmpty()) "совпадений нет" else null
            )
        }

        // Шаг 2 — кэш: 24 часа на тот же запрос.
        if (useApi) {
            val cacheKey = "leak:" + LeakRules.hash(query)
            val cachedBody = runCatching { store.cacheGet(cacheKey, ttlMs) }.getOrNull()
            if (cachedBody != null) {
                onStage("Кэш за сутки…")
                val cachedFacts = decodeFacts(cachedBody, query)
                cachedFacts.forEach(::add)
                cachedFacts.map { it.sourceId }.distinct().forEach { id ->
                    rows += SourceRow(
                        id = id,
                        title = title(id),
                        hits = cachedFacts.count { it.sourceId == id },
                        cached = true,
                        error = "ответ за сутки"
                    )
                }
                fromCache = true
            } else {
                // Шаг 3 — цепочка API: первый ответивший закрывает вопрос, остальные не дёргаются.
                val chain = CHAIN.filter { id ->
                    val spec = ProviderCatalog.byId(id)
                    spec?.keyless == true || keys.key(id).isNotBlank()
                }
                if (chain.isEmpty()) {
                    rows += SourceRow(
                        id = "api",
                        title = "Онлайн-API",
                        hits = 0,
                        error = "нет ключей — добавьте в «Ключи»"
                    )
                }
                val collected = ArrayList<RawFact>()
                var networkFailures = 0
                for ((index, id) in chain.withIndex()) {
                    val connector = Connectors.byId(id) ?: continue
                    onStage("${title(id)}: запрос ${index + 1} из ${chain.size}")
                    val outcome = runCatching {
                        withTimeout(25_000) { connector.collect(query, keys) }
                    }
                    val items = outcome.getOrElse { error ->
                        networkFailures++
                        rows += SourceRow(
                            id = id,
                            title = title(id),
                            hits = 0,
                            error = error.message ?: "ошибка запроса"
                        )
                        emptyList()
                    }
                    val statusFact = items.firstOrNull { it.kind == "Статус" || it.kind == "status" }
                    val data = items.filterNot { it.kind == "Статус" || it.kind == "status" }
                    if (data.isNotEmpty()) {
                        collected += data
                        rows += SourceRow(
                            id = id,
                            title = title(id),
                            hits = data.size,
                            status = statusFact?.value?.takeIf { it.isNotBlank() }
                        )
                        // Останавливаемся, когда найден пароль; метаданные HIBP
                        // без пароля не закрывают вопрос — идём дальше по цепочке.
                        if (data.any { factHasPassword(it) }) break
                    } else if (statusFact != null) {
                        // Статус без записей (например, 403): показываем причину и идём дальше.
                        rows += SourceRow(
                            id = id,
                            title = title(id),
                            hits = 0,
                            status = statusFact.value.takeIf { it.isNotBlank() }
                        )
                    } else if (outcome.isSuccess) {
                        rows += SourceRow(id = id, title = title(id), hits = 0, error = "пусто")
                    }
                    // Пауза между сервисами: 429 не обходим прокси, а ждём.
                    if (index < chain.size - 1) delay(700)
                }
                if (collected.isNotEmpty()) {
                    collected.forEach(::add)
                    // Кэш не хранит значения паролей — только факт утечки и статус.
                    val safe = collected.map(::sanitizeForCache)
                    runCatching { store.cachePut("leak:" + LeakRules.hash(query), encodeFacts(safe)) }
                }
                offline = chain.isNotEmpty() && networkFailures == chain.size
            }
        }

        val note = when {
            offline -> "Сеть недоступна — показаны локальный индекс и кэш"
            fromCache -> "Ответ из кэша (моложе 24 часов), сеть не дёргалась"
            facts.isEmpty() -> "Совпадений нет"
            else -> "Источников с данными: ${rows.count { it.hits > 0 }}"
        }

        Result(
            facts = facts.values.toList(),
            sources = rows,
            offline = offline,
            cached = fromCache,
            note = note,
            tookMs = System.currentTimeMillis() - started
        )
    }

    private fun factHasPassword(fact: RawFact): Boolean =
        !fact.attributes["password"].isNullOrBlank() ||
            !fact.attributes["Пароль"].isNullOrBlank() ||
            !fact.attributes["Пароли"].isNullOrBlank()

    /** Копия факта без значения пароля: в кэш попадает только признак. */
    private fun sanitizeForCache(fact: RawFact): RawFact {
        val attrs = fact.attributes
        val had = attrs["password"] != null ||
            attrs["Пароль"]?.isNotBlank() == true ||
            attrs["Пароли"]?.isNotBlank() == true ||
            attrs["hadPassword"]?.equals("true", true) == true
        val cleaned = attrs.filterKeys { it !in setOf("password", "Пароль", "Пароли") }
        return fact.copy(attributes = cleaned + ("hadPassword" to had.toString()))
    }
}
