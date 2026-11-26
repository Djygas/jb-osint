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
    val origin: String? = null
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
        val error: String? = null
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
                val password = hit.password?.takeIf { it.isNotBlank() }
                val seenPassword = password != null || hit.hasSecret
                add(
                    RawFact(
                        category = FactCategory.OTHER,
                        kind = if (hit.kind == "phone") "Утечка: телефон" else "Утечка: почта",
                        value = query,
                        subject = query,
                        attributes = buildMap {
                            put("where", origin)
                            put("Файл", hit.file.substringAfterLast('/'))
                            put("Строка", hit.line.toString())
                            put("Маска", hit.mask)
                            put("hadPassword", seenPassword.toString())
                            if (password != null) put("password", password)
                        },
                        sourceId = INDEX_ID
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
            val cachedBody = runCatching { store.cacheGet(cacheKey, LeakRules.TTL_MS) }.getOrNull()
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
                val chain = CHAIN.filter { keys.key(it).isNotBlank() }
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
                    if (items.isNotEmpty()) {
                        collected += items
                        rows += SourceRow(id = id, title = title(id), hits = items.size)
                        break
                    } else if (outcome.isSuccess) {
                        rows += SourceRow(id = id, title = title(id), hits = 0, error = "пусто")
                    }
                    // Пауза между сервисами: 429 не обходим прокси, а ждём.
                    if (index < chain.size - 1) delay(700)
                }
                if (collected.isNotEmpty()) {
                    collected.forEach(::add)
                    runCatching { store.cachePut("leak:" + LeakRules.hash(query), encodeFacts(collected)) }
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
}
