package ru.jbosint.osint.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.SearchStats

/**
 * «Цифровой детектор связей»: строит карту цифрового присутствия по одному параметру
 * (телефон, email, ник) и добирает утечки по найденным контактам.
 *
 * Алгоритм «от одного — ко всему» в два прохода:
 *  1. Идентификация — модули, применимые к типу входа (телефон → VK/TeleSINT/утечки и т. д.).
 *  2. Расширение — из найденных фактов берутся точные идентификаторы (почта, телефон, ник)
 *     и по ним запускаются только модули утечек. Имена и адреса страниц зацепками не считаются:
 *     по ним поиск размывается и жжёт квоты.
 *
 * Класс ничего не рисует и ничего не сохраняет: на выходе — структура и JSON для отчёта.
 * Сеть инжектируется через [modules], поэтому алгоритм тестируется без запросов.
 */
class DigitalFootprintGraph(
    private val keys: KeySource,
    private val modules: List<Connector> = Connectors.all,
    private val limits: Limits = Limits()
) {

    data class Limits(
        /** 0 — только идентификация, без расширения. */
        val maxDepth: Int = 1,
        /** Сколько связанных контактов проверять на утечки за один проход. */
        val maxPivots: Int = 3,
        /** Таймаут одного модуля: не даём одному источнику держать весь граф. */
        val moduleTimeoutMs: Long = 15_000,
        /** Потолок числа утечек в отчёте. */
        val maxLeaks: Int = 60
    )

    data class Node(
        val key: String,
        val kind: String,
        val value: String,
        val depth: Int,
        val sources: Set<String> = emptySet()
    )

    data class Edge(val from: String, val to: String, val relation: String)

    data class Leak(
        val source: String,
        val resource: String,
        val date: String,
        val dataTypes: String,
        val subject: String,
        /** Пароль показывается клиенту без маски: его задача — сменить этот же пароль везде. */
        val password: String,
        val hasPassword: Boolean
    )

    data class Risk(val level: String, val title: String, val detail: String) {
        companion object {
            const val HIGH = "HIGH"
            const val MEDIUM = "MEDIUM"
            const val LOW = "LOW"
            const val INFO = "INFO"
        }
    }

    data class Footprint(
        val seed: String,
        val seedKind: String,
        val nodes: List<Node>,
        val edges: List<Edge>,
        val leaks: List<Leak>,
        val risks: List<Risk>,
        val recommendations: List<String>,
        val stats: SearchStats
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("schema", SCHEMA)
            put("seed", seed)
            put("seed_kind", seedKind)
            put("generated_at", System.currentTimeMillis())
            put("nodes", JSONArray().apply {
                nodes.forEach { node ->
                    put(JSONObject().apply {
                        put("key", node.key)
                        put("kind", node.kind)
                        put("value", node.value)
                        put("depth", node.depth)
                        put("sources", JSONArray(node.sources.toList()))
                    })
                }
            })
            put("edges", JSONArray().apply {
                edges.forEach { edge ->
                    put(JSONObject().apply {
                        put("from", edge.from)
                        put("to", edge.to)
                        put("relation", edge.relation)
                    })
                }
            })
            put("leaks", JSONArray().apply {
                leaks.forEach { leak ->
                    put(JSONObject().apply {
                        put("source", leak.source)
                        put("resource", leak.resource)
                        put("date", leak.date)
                        put("data_types", leak.dataTypes)
                        put("subject", leak.subject)
                        put("password", leak.password)
                        put("has_password", leak.hasPassword)
                    })
                }
            })
            put("risks", JSONArray().apply {
                risks.forEach { risk ->
                    put(JSONObject().apply {
                        put("level", risk.level)
                        put("title", risk.title)
                        put("detail", risk.detail)
                    })
                }
            })
            put("recommendations", JSONArray().apply { recommendations.forEach { put(it) } })
            put("stats", JSONObject().apply {
                put("sources_attempted", stats.sourcesAttempted)
                put("sources_responded", stats.sourcesResponded)
                put("sources_failed", stats.sourcesFailed)
                put("duration_ms", stats.durationMs)
            })
        }

        companion object {
            const val SCHEMA = "jb-osint/footprint/1"
        }
    }

    private data class RunResult(
        val facts: List<RawFact> = emptyList(),
        val attempted: Int = 0,
        val responded: Int = 0,
        val failed: Int = 0
    ) {
        operator fun plus(other: RunResult) = RunResult(
            facts = facts + other.facts,
            attempted = attempted + other.attempted,
            responded = responded + other.responded,
            failed = failed + other.failed
        )
    }

    /** Какие модули имеют смысл для каждого типа входа. Порядок — приоритет. */
    private val route: Map<QueryKind, List<String>> = mapOf(
        QueryKind.EMAIL to listOf("hibp", "leakcheck", "hudsonrock", "dyxless", "hunter"),
        QueryKind.PHONE to listOf("numverify", "vk", "telesint", "dyxless", "leakcheck", "tgstat"),
        QueryKind.USERNAME to listOf("github", "gitlab", "vk", "tgstat", "telesint", "reddit", "hudsonrock"),
        QueryKind.NAME to listOf("vk", "tgstat", "wikipedia", "ddg"),
        QueryKind.DOMAIN to listOf("rdap", "urlscan", "securitytrails", "virustotal", "shodan", "ipinfo"),
        QueryKind.IP to listOf("shodan", "virustotal", "abuseipdb", "ipinfo", "urlscan")
    )

    /** Второй проход: только то, что реально ищет учётные данные. */
    private val leakRoute: Map<String, List<String>> = mapOf(
        "EMAIL" to listOf("hibp", "leakcheck", "hudsonrock", "dyxless"),
        "PHONE" to listOf("leakcheck", "dyxless"),
        "USERNAME" to listOf("leakcheck", "hudsonrock", "dyxless")
    )

    private val prettySource: Map<String, String> = mapOf(
        "hibp" to "HIBP",
        "leakcheck" to "LeakCheck",
        "hudsonrock" to "Hudson Rock",
        "dyxless" to "Dyxless",
        "vk" to "VK",
        "telesint" to "TeleSINT",
        "github" to "GitHub",
        "gitlab" to "GitLab",
        "tgstat" to "TGStat",
        "numverify" to "Numverify",
        "hunter" to "Hunter",
        "urlscan" to "urlscan",
        "shodan" to "Shodan",
        "virustotal" to "VirusTotal",
        "ipinfo" to "IPinfo",
        "rdap" to "RDAP",
        "reddit" to "Reddit",
        "wikipedia" to "Wikipedia",
        "ddg" to "DuckDuckGo",
        "securitytrails" to "SecurityTrails",
        "abuseipdb" to "AbuseIPDB"
    )

    private val keyless: Set<String> = SearchEngine.KEYLESS_FALLBACK.toSet() + "rdap"

    // ---------------------------------------------------------------- API

    /** Полный проход: идентификация + расширение + синтез. */
    suspend fun build(
        seed: String,
        onStage: (String, Int) -> Unit = { _, _ -> }
    ): Footprint {
        val clean = seed.trim()
        val kind = QueryDetector.detect(clean)
        val started = System.currentTimeMillis()

        onStage("Идентификация: ${kind.title}", 5)
        val first = runModules(clean, route[kind].orEmpty(), onStage, 5, 40)

        val pivots = pivotsOf(first.facts, exclude = setOf(keyOf(kind, clean)), limit = limits.maxPivots)
        onStage(
            if (pivots.isEmpty()) "Связанных контактов не найдено" else "Проверяю связанные контакты: ${pivots.size}",
            50
        )
        val second = if (limits.maxDepth >= 1 && pivots.isNotEmpty()) {
            collectLeaks(pivots, onStage, 50, 45)
        } else {
            RunResult()
        }

        val nodes = nodesOf(clean, kind, pivots)
        val edges = edgesOf(clean, kind, pivots)
        val leaks = leaksOf(first.facts + second.facts)
        val risks = risksOf(leaks, pivots)

        return Footprint(
            seed = clean,
            seedKind = kind.name,
            nodes = nodes,
            edges = edges,
            leaks = leaks,
            risks = risks,
            recommendations = recommendationsOf(risks, leaks),
            stats = SearchStats(
                sourcesAttempted = first.attempted + second.attempted,
                sourcesResponded = first.responded + second.responded,
                sourcesFailed = first.failed + second.failed,
                rawFacts = first.facts.size + second.facts.size,
                durationMs = System.currentTimeMillis() - started
            )
        )
    }

    /**
     * Второй проход поверх готового поиска: берёт зацепки из уже собранных фактов
     * и добирает утечки. Возвращает только новые факты — их можно подмешать
     * в основной результат и пересобрать отчёт.
     */
    suspend fun expand(
        base: AggregatedData,
        onStage: (String, Int) -> Unit = { _, _ -> }
    ): List<RawFact> {
        if (limits.maxDepth < 1 || base.facts.isEmpty()) return emptyList()
        val seedKey = keyOf(QueryDetector.detect(base.query), base.query)
        val pivots = EntityResolver.pivots(
            facts = base.facts,
            exclude = setOf(seedKey),
            limit = limits.maxPivots
        )
        if (pivots.isEmpty()) return emptyList()
        onStage("Проверяю связанные контакты: ${pivots.size}", 90)
        return collectLeaks(pivots, onStage, 90, 8).facts
    }

    /**
     * Прогон по контактам из внешнего отчёта (PDF/HTML/текст): вытаскиваем почты, телефоны,
     * ники и пары «ресурс — логин», затем проверяем их в модулях утечек.
     * Пароли возвращаются как есть: клиенту нужно видеть, какой именно пароль утёк,
     * чтобы сменить его везде, где он использовался.
     */
    suspend fun runFromText(
        text: String,
        onStage: (String, Int) -> Unit = { _, _ -> }
    ): Footprint {
        val started = System.currentTimeMillis()
        val extracted = ContactsExtractor.extract(text)
        onStage(
            "Из отчёта: ${extracted.emails.size} почт, ${extracted.phones.size} телефонов, " +
                "${extracted.usernames.size} ников",
            10
        )
        val pivots = extracted.pivots()
        val result = if (limits.maxDepth >= 1 && pivots.isNotEmpty()) {
            collectLeaks(pivots.take(limits.maxPivots), onStage, 20, 70)
        } else {
            RunResult()
        }

        val nodes = ArrayList<Node>()
        nodes += Node("import:report", "IMPORT", "Отчёт клиента", 0)
        extracted.emails.forEach { nodes += Node("email:$it", "EMAIL", it, 1, setOf("report")) }
        extracted.phones.forEach { nodes += Node("phone:$it", "PHONE", it, 1, setOf("report")) }
        extracted.usernames.forEach { nodes += Node("handle:$it", "USERNAME", it, 1, setOf("report")) }
        extracted.accounts.forEach {
            nodes += Node(
                "account:${it.resource}/${it.login.lowercase()}",
                "ACCOUNT",
                "${it.resource} · ${it.login}",
                1,
                setOf("report")
            )
        }
        val edges = nodes.drop(1).map { Edge("import:report", it.key, "из отчёта") }
        val leaks = leaksOf(result.facts)
        val risks = risksOf(leaks, pivots)

        return Footprint(
            seed = extracted.names.firstOrNull().orEmpty().ifBlank { "Отчёт клиента" },
            seedKind = "IMPORT",
            nodes = nodes.distinctBy { it.key },
            edges = edges,
            leaks = leaks,
            risks = risks,
            recommendations = recommendationsOf(risks, leaks),
            stats = SearchStats(
                sourcesAttempted = result.attempted,
                sourcesResponded = result.responded,
                sourcesFailed = result.failed,
                rawFacts = result.facts.size,
                durationMs = System.currentTimeMillis() - started
            )
        )
    }

    // ------------------------------------------------------------- сбоp

    private suspend fun runModules(
        query: String,
        ids: Collection<String>,
        onStage: (String, Int) -> Unit,
        progressFrom: Int,
        progressSpan: Int
    ): RunResult {
        val enabled = keys.enabledWithKeys()
        val selected = ids.distinct()
            .filter { id -> id in keyless || enabled.containsKey(id) }
            .mapNotNull { id -> modules.firstOrNull { it.id == id } }
        if (selected.isEmpty()) return RunResult()

        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val results = coroutineScope {
            selected.map { module ->
                async {
                    val run = withTimeoutOrNull(limits.moduleTimeoutMs) {
                        runCatching { module.collect(query, keys) }
                    }
                    val current = done.incrementAndGet()
                    onStage(
                        "Модуль $current из ${selected.size}",
                        progressFrom + progressSpan * current / selected.size
                    )
                    when {
                        run == null -> module.id to (null as List<RawFact>?)
                        else -> module.id to run.getOrNull()
                    }
                }
            }.awaitAll()
        }

        var attempted = 0
        var responded = 0
        var failed = 0
        val facts = ArrayList<RawFact>()
        results.forEach { (_, list) ->
            attempted++
            if (list == null) {
                failed++
            } else {
                responded++
                facts += list
            }
        }
        return RunResult(facts = facts.distinctBy { "${it.sourceId}|${it.kind}|${it.value}" }, attempted = attempted, responded = responded, failed = failed)
    }

    private suspend fun collectLeaks(
        pivots: List<EntityResolver.Pivot>,
        onStage: (String, Int) -> Unit,
        progressFrom: Int,
        progressSpan: Int
    ): RunResult {
        var acc = RunResult()
        pivots.forEachIndexed { index, pivot ->
            val ids = leakRoute[pivot.kind].orEmpty()
            if (ids.isEmpty()) return@forEachIndexed
            val part = runModules(
                query = pivot.query,
                ids = ids,
                onStage = onStage,
                progressFrom = progressFrom + progressSpan * index / pivots.size.coerceAtLeast(1),
                progressSpan = progressSpan / pivots.size.coerceAtLeast(1)
            )
            acc += part
        }
        return acc.copy(facts = acc.facts.distinctBy { "${it.sourceId}|${it.kind}|${it.value}" })
    }

    // -------------------------------------------------------- нормализация

    private fun keyOf(kind: QueryKind, value: String): String = when (kind) {
        QueryKind.EMAIL -> EntityResolver.email(value)?.let { "email:$it" }
        QueryKind.PHONE -> EntityResolver.phone(value)?.let { "phone:$it" }
        QueryKind.USERNAME -> EntityResolver.handle(value)?.let { "handle:$it" }
        QueryKind.DOMAIN -> EntityResolver.domain(value)?.let { "domain:$it" }
        QueryKind.IP -> "ip:${value.trim().lowercase()}"
        QueryKind.NAME -> EntityResolver.name(value).let { if (it.isBlank()) null else "name:$it" }
    } ?: "seed:${value.trim().lowercase()}"

    private fun pivotsOf(
        facts: List<RawFact>,
        exclude: Set<String>,
        limit: Int
    ): List<EntityResolver.Pivot> {
        val out = LinkedHashMap<String, EntityResolver.Pivot>()
        facts.forEach { fact ->
            val value = fact.value.trim()
            if (value.isBlank()) return@forEach
            val pivot = when {
                EntityResolver.email(value) != null ->
                    EntityResolver.Pivot("EMAIL", EntityResolver.email(value)!!, EntityResolver.email(value)!!)
                fact.category == FactCategory.CONTACT || fact.kind.contains("Телефон", true) ->
                    EntityResolver.phone(value)?.let { EntityResolver.Pivot("PHONE", it, it) }
                fact.category == FactCategory.PERSON || fact.kind.contains("Ник", true) ->
                    EntityResolver.handle(value)?.let { EntityResolver.Pivot("USERNAME", it, "@$it") }
                else -> null
            } ?: return@forEach
            if (pivot.key in exclude) return@forEach
            out.putIfAbsent(pivot.key, pivot)
        }
        return out.values.take(limit)
    }

    private fun nodesOf(seed: String, kind: QueryKind, pivots: List<EntityResolver.Pivot>): List<Node> {
        val nodes = LinkedHashMap<String, Node>()
        val seedKey = keyOf(kind, seed)
        nodes[seedKey] = Node(seedKey, kind.name, seed.trim(), depth = 0)
        pivots.forEach { pivot ->
            nodes.putIfAbsent(
                pivot.key,
                Node(pivot.key, pivot.kind, pivot.value, depth = 1, sources = setOf("graph"))
            )
        }
        return nodes.values.toList()
    }

    private fun edgesOf(seed: String, kind: QueryKind, pivots: List<EntityResolver.Pivot>): List<Edge> {
        val seedKey = keyOf(kind, seed)
        return pivots.map { Edge(seedKey, it.key, "связан") }
    }

    private fun leaksOf(facts: List<RawFact>): List<Leak> {
        val out = LinkedHashMap<String, Leak>()
        facts.forEach { fact ->
            val isLeak = fact.kind.equals("Утечка", true) || fact.kind.contains("утечк", true)
            if (!isLeak) return@forEach
            val resource = fact.value.trim()
            if (resource.isBlank()) return@forEach
            val date = fact.attributes.entries.firstOrNull { it.key.equals("Дата", true) }?.value.orEmpty()
            val types = fact.attributes.entries.firstOrNull {
                it.key.equals("Данные", true) || it.key.equals("Наборы данных", true)
            }?.value.orEmpty()
            val rawPassword = fact.attributes.entries.firstOrNull {
                it.key.equals("Пароль", true) || it.key.equals("password", true)
            }?.value.orEmpty()
            val key = "${fact.sourceId}|${resource}|${fact.subject}".lowercase()
            out.putIfAbsent(
                key,
                Leak(
                    source = prettySource[fact.sourceId] ?: fact.sourceId,
                    resource = resource,
                    date = date,
                    dataTypes = types,
                    subject = fact.subject.trim(),
                    password = rawPassword.trim(),
                    hasPassword = rawPassword.isNotBlank()
                )
            )
        }
        return out.values.take(limits.maxLeaks)
    }

    private fun risksOf(leaks: List<Leak>, pivots: List<EntityResolver.Pivot>): List<Risk> {
        val out = ArrayList<Risk>()
        val withPassword = leaks.count { it.hasPassword }
        if (withPassword > 0) {
            out += Risk(
                Risk.HIGH,
                "Пароль найден в утечке",
                "Обнаружено $withPassword ${plural(withPassword, "запись", "записи", "записей")} с паролем. " +
                    "Пароль мог быть опубликован в открытом виде — его нужно сменить везде, где он использовался."
            )
        }
        if (leaks.isNotEmpty() && withPassword == 0) {
            out += Risk(
                Risk.MEDIUM,
                "Адрес засветился в утечках",
                "Найдено ${leaks.size} ${plural(leaks.size, "утечка", "утечки", "утечек")}. " +
                    "Пароли источник не отдаёт, но сам факт попадания означает спам-риски и попытки подбора."
            )
        }
        if (pivots.size >= 2) {
            out += Risk(
                Risk.MEDIUM,
                "Широкий цифровой след",
                "С исходным параметром связано ${pivots.size} ${plural(pivots.size, "контакт", "контакта", "контактов")}. " +
                    "Каждый связанный контакт — дополнительная точка входа для злоумышленника."
            )
        }
        if (out.isEmpty()) {
            out += Risk(Risk.INFO, "Утечек не найдено", "По доступным источникам совпадений нет. Ограничение: проверены только подключённые модули.")
        }
        return out
    }

    private fun recommendationsOf(risks: List<Risk>, leaks: List<Leak>): List<String> {
        val out = ArrayList<String>()
        if (leaks.any { it.hasPassword }) {
            out += "Сменить пароль от почты и от всех сервисов, где использовался тот же пароль."
            out += "Включить двухфакторную аутентификацию на почте, в банке и в основных соцсетях."
        }
        if (leaks.isNotEmpty()) {
            out += "Проверить адрес на haveibeenpwned.com и настроить уведомления о новых утечках."
            out += "Не использовать один пароль на нескольких сайтах: менеджер паролей решает это за один вечер."
        }
        if (risks.any { it.level == Risk.MEDIUM && it.title.contains("след") }) {
            out += "Проверить связанные контакты: незнакомые номера и почты могут быть чужими аккаунтами, привязанными по ошибке."
        }
        if (out.isEmpty()) {
            out += "Данных для тревоги нет. Повторить проверку через месяц: базы утечек пополняются постоянно."
        }
        return out
    }

    // ------------------------------------------------------------ утилиты


    private fun plural(count: Int, one: String, few: String, many: String): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
