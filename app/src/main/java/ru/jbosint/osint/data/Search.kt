package ru.jbosint.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.aggregate.Aggregator
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.SearchStats
import kotlin.math.absoluteValue

enum class QueryKind(val title: String) {
    EMAIL("Адрес электронной почты"),
    PHONE("Номер телефона"),
    DOMAIN("Домен"),
    IP("IP-адрес"),
    USERNAME("Никнейм"),
    NAME("Имя или название")
}

object QueryDetector {
    private val email = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
    private val phone = Regex("^\\+?[\\d][\\d\\s()\\-]{8,18}$")
    private val ip = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
    private val domain = Regex("^(([A-Za-z0-9]([A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?)\\.)+[A-Za-z]{2,}$")
    private val username = Regex("^@?[A-Za-z0-9_.\\-]{2,32}$")

    fun detect(raw: String): QueryKind {
        val q = raw.trim()
        return when {
            email.matches(q) -> QueryKind.EMAIL
            ip.matches(q) -> QueryKind.IP
            phone.matches(q) && q.count { it.isDigit() } >= 9 -> QueryKind.PHONE
            domain.matches(q) -> QueryKind.DOMAIN
            username.matches(q) && !q.contains(' ') -> QueryKind.USERNAME
            else -> QueryKind.NAME
        }
    }
}

/** Модуль сбора. Название модуля пользователю не показывается — только «Поиск JB OSINT». */
interface Connector {
    val id: String
    suspend fun collect(query: String, key: KeySource): List<RawFact>
}

private fun fact(
    category: FactCategory,
    kind: String,
    value: String,
    subject: String,
    source: String,
    attrs: Map<String, String> = emptyMap()
) = RawFact(category, kind, value.trim(), subject.trim(), attrs, source)


/** Склейка значений JSONArray (org.json не является Iterable). */
internal fun JSONArray?.joined(separator: String = ", "): String {
    if (this == null) return ""
    val values = ArrayList<String>(length())
    for (i in 0 until length()) {
        val value = optString(i)
        if (value.isNotBlank()) values += value
    }
    return values.joinToString(separator)
}

object Connectors {

    val all: List<Connector> = listOf(
        GithubConnector, HibpConnector, LeakCheckConnector, HudsonRockConnector, VirusTotalConnector, ShodanConnector,
        NumverifyConnector, HunterConnector, IpinfoConnector, TgstatConnector,
        VkConnector, DyxlessConnector, OpenCorporatesConnector, GoogleConnector, YandexConnector,
        BraveConnector, SerpApiConnector, DdgConnector, WikipediaConnector,
        UrlscanConnector, SecurityTrailsConnector, AbuseIpdbConnector, GitlabConnector,
        TeleSintConnector, TronkConnector, DadataConnector, RedditConnector,
        TelelogConnector, FunstatConnector
    )

    fun byId(id: String): Connector? = all.firstOrNull { it.id == id }
}

/**
 * TeleSINT: поиск профилей и каналов Telegram по нику, телефону или имени.
 * Запрос платный (1 ₽ за успешный ответ), поэтому модуль работает только при
 * заданном ключе и только для типов запросов, которые сервис принимает.
 */

/**
 * DaData: подсказки/стандартизация ФИО, телефонов, email, адресов и организаций.
 * API: https://dadata.ru/api/suggest/
 */
private object DadataConnector : Connector {
    override val id = "dadata"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val token = key.key(id)
        if (token.isBlank()) return emptyList()
        val secret = key.secondField(id)
        val headers = buildMap {
            put("Authorization", "Token $token")
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            if (secret.isNotBlank()) put("X-Secret", secret)
        }
        val q = query.trim()
        val kind = QueryDetector.detect(q)
        val facts = ArrayList<RawFact>()
        when (kind) {
            QueryKind.EMAIL -> facts += suggestEmail(q, headers)
            QueryKind.PHONE -> facts += suggestPhone(q, headers)
            QueryKind.NAME -> {
                facts += suggestFio(q, headers)
                facts += suggestParty(q, headers)
            }
            QueryKind.DOMAIN -> facts += suggestParty(q, headers)
            else -> {
                facts += suggestFio(q, headers)
                facts += suggestParty(q, headers)
            }
        }
        return facts.distinctBy { it.kind to it.value }.take(24)
    }

    private suspend fun suggestFio(query: String, headers: Map<String, String>): List<RawFact> {
        val json = postSuggest("https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/fio", query, headers) ?: return emptyList()
        val suggestions = json.optJSONArray("suggestions") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(suggestions.length(), 5)) {
            val item = suggestions.optJSONObject(i) ?: continue
            val value = item.optString("value").ifBlank { item.optString("unrestricted_value") }
            if (value.isBlank()) continue
            val data = item.optJSONObject("data")
            facts += fact(FactCategory.PERSON, "Полное имя", value, value, id, buildMap {
                data?.optString("gender")?.takeIf { it.isNotBlank() && it != "UNKNOWN" }?.let { put("Пол", genderRu(it)) }
                data?.optString("qc")?.takeIf { it.isNotBlank() }?.let { put("Код качества", it) }
            })
        }
        return facts
    }

    private suspend fun suggestPhone(query: String, headers: Map<String, String>): List<RawFact> {
        // clean endpoint gives standardized phone; fallback to suggest
        val clean = Net.fetch(
            "https://cleaner.dadata.ru/api/v1/clean/phone",
            headers, attempts = 1, method = "POST", body = JSONArray().put(query).toString()
        )
        val facts = ArrayList<RawFact>()
        if (clean.ok) {
            val arr = runCatching { JSONArray(clean.body) }.getOrNull()
            val item = arr?.optJSONObject(0)
            if (item != null) {
                val phone = item.optString("phone").ifBlank { item.optString("number") }
                val number = item.optString("number").ifBlank { query }
                val subject = item.optString("country").ifBlank { query }
                if (phone.isNotBlank() || number.isNotBlank()) {
                    facts += fact(FactCategory.CONTACT, "Телефон", phone.ifBlank { number }, subject.ifBlank { query }, id, buildMap {
                        item.optString("country").takeIf { it.isNotBlank() }?.let { put("Страна", it) }
                        item.optString("region").takeIf { it.isNotBlank() }?.let { put("Регион", it) }
                        item.optString("city").takeIf { it.isNotBlank() }?.let { put("Город", it) }
                        item.optString("provider").takeIf { it.isNotBlank() }?.let { put("Оператор", it) }
                        item.optString("type").takeIf { it.isNotBlank() }?.let { put("Тип", it) }
                        item.optString("timezone").takeIf { it.isNotBlank() }?.let { put("Часовой пояс", it) }
                        item.optString("qc").takeIf { it.isNotBlank() }?.let { put("Код качества", it) }
                    })
                }
            }
        }
        if (facts.isEmpty()) {
            val json = postSuggest("https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/phone", query, headers) ?: return emptyList()
            val suggestions = json.optJSONArray("suggestions") ?: return emptyList()
            for (i in 0 until minOf(suggestions.length(), 3)) {
                val item = suggestions.optJSONObject(i) ?: continue
                val value = item.optString("value")
                if (value.isBlank()) continue
                facts += fact(FactCategory.CONTACT, "Телефон", value, query, id)
            }
        }
        return facts
    }

    private suspend fun suggestEmail(query: String, headers: Map<String, String>): List<RawFact> {
        val clean = Net.fetch(
            "https://cleaner.dadata.ru/api/v1/clean/email",
            headers, attempts = 1, method = "POST", body = JSONArray().put(query).toString()
        )
        val facts = ArrayList<RawFact>()
        if (clean.ok) {
            val arr = runCatching { JSONArray(clean.body) }.getOrNull()
            val item = arr?.optJSONObject(0)
            if (item != null) {
                val email = item.optString("email").ifBlank { query }
                facts += fact(FactCategory.CONTACT, "Почта", email, query, id, buildMap {
                    item.optString("type").takeIf { it.isNotBlank() }?.let { put("Тип", it) }
                    item.optString("domain").takeIf { it.isNotBlank() }?.let { put("Домен", it) }
                    item.optString("local").takeIf { it.isNotBlank() }?.let { put("Локальная часть", it) }
                    item.optString("qc").takeIf { it.isNotBlank() }?.let { put("Код качества", it) }
                })
            }
        }
        if (facts.isEmpty()) {
            val json = postSuggest("https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/email", query, headers) ?: return emptyList()
            val suggestions = json.optJSONArray("suggestions") ?: return emptyList()
            for (i in 0 until minOf(suggestions.length(), 3)) {
                val item = suggestions.optJSONObject(i) ?: continue
                val value = item.optString("value")
                if (value.isBlank()) continue
                facts += fact(FactCategory.CONTACT, "Почта", value, query, id)
            }
        }
        return facts
    }

    private suspend fun suggestParty(query: String, headers: Map<String, String>): List<RawFact> {
        val json = postSuggest("https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/party", query, headers) ?: return emptyList()
        val suggestions = json.optJSONArray("suggestions") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(suggestions.length(), 5)) {
            val item = suggestions.optJSONObject(i) ?: continue
            val value = item.optString("value")
            if (value.isBlank()) continue
            val data = item.optJSONObject("data")
            val subject = value
            facts += fact(FactCategory.ORGANIZATION, "Организация", value, subject, id, buildMap {
                data?.optString("inn")?.takeIf { it.isNotBlank() }?.let { put("ИНН", it) }
                data?.optString("ogrn")?.takeIf { it.isNotBlank() }?.let { put("ОГРН", it) }
                data?.optString("kpp")?.takeIf { it.isNotBlank() }?.let { put("КПП", it) }
                data?.optJSONObject("address")?.optString("value")?.takeIf { it.isNotBlank() }?.let { put("Адрес", it) }
                data?.optString("state")?.takeIf { it.isNotBlank() }?.let { put("Статус", it) }
                data?.optJSONObject("management")?.optString("name")?.takeIf { it.isNotBlank() }?.let { put("Руководитель", it) }
                data?.optJSONObject("management")?.optString("post")?.takeIf { it.isNotBlank() }?.let { put("Должность", it) }
            })
            data?.optJSONObject("management")?.optString("name")?.takeIf { it.isNotBlank() }?.let { manager ->
                facts += fact(FactCategory.PERSON, "Руководитель", manager, value, id)
            }
        }
        return facts
    }

    private suspend fun postSuggest(url: String, query: String, headers: Map<String, String>): JSONObject? {
        val response = Net.fetch(url, headers, attempts = 1, method = "POST", body = JSONObject().put("query", query).put("count", 5).toString())
        if (!response.ok) return null
        return runCatching { JSONObject(response.body) }.getOrNull()
    }

    private fun genderRu(code: String): String = when (code.uppercase()) {
        "MALE" -> "мужской"
        "FEMALE" -> "женский"
        else -> code
    }
}
private object TeleSintConnector : Connector {
    override val id = "telesint"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val token = key.key(id)
        if (token.isBlank()) return emptyList()
        val kind = QueryDetector.detect(query)
        if (kind !in setOf(QueryKind.USERNAME, QueryKind.PHONE, QueryKind.NAME)) return emptyList()
        return runCatching { TeleSint.search(token, query, limit = 20) }
            .getOrElse { emptyList() }
            .take(40)
    }
}

private object TelelogConnector : Connector {
    override val id = "telelog"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val token = key.key(id)
        if (token.isBlank()) return emptyList()
        val parsed = TelegramRules.parse(query)
        if (!parsed.valid) return emptyList()
        return runCatching { TelelogLike.query(id, token, parsed) }.getOrDefault(emptyList()).take(30)
    }
}

private object FunstatConnector : Connector {
    override val id = "funstat"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val token = key.key(id)
        if (token.isBlank()) return emptyList()
        val parsed = TelegramRules.parse(query)
        if (!parsed.valid) return emptyList()
        return runCatching { TelelogLike.query(id, token, parsed) }.getOrDefault(emptyList()).take(30)
    }
}

private object GithubConnector : Connector {
    override val id = "github"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val token = key.key(id)
        val headers = if (token.isNotBlank()) mapOf("Authorization" to "token $token") else emptyMap()
        val kind = QueryDetector.detect(query)
        val profiles = if (kind == QueryKind.USERNAME) {
            listOfNotNull(Net.json("https://api.github.com/users/${Net.urlEncode(query.trimStart('@'))}", headers).getOrNull())
        } else {
            val search = Net.json(
                "https://api.github.com/search/users?q=${Net.urlEncode(query)}&per_page=3",
                headers
            ).getOrNull() ?: return emptyList()
            search.optJSONArray("items").let { items ->
                (0 until (items?.length() ?: 0)).mapNotNull { i ->
                    val login = items!!.getJSONObject(i).optString("login")
                    if (login.isBlank()) null
                    else Net.json("https://api.github.com/users/$login", headers).getOrNull()
                }
            }
        }
        val facts = ArrayList<RawFact>()
        profiles.forEach { p ->
            val login = p.optString("login").ifBlank { return@forEach }
            val subject = p.optString("name").ifBlank { login }
            facts += fact(FactCategory.PERSON, "Профиль", login, subject, id, buildMap {
                p.optString("html_url").takeIf { it.isNotBlank() }?.let { put("Профиль", it) }
                p.optString("created_at").takeIf { it.isNotBlank() }?.let { put("Создан", it.take(10)) }
                p.optInt("public_repos", 0).let { put("Публичных репозиториев", it.toString()) }
                p.optInt("followers", 0).let { put("Подписчиков", it.toString()) }
            })
            p.optString("name").takeIf { it.isNotBlank() }?.let {
                facts += fact(FactCategory.PERSON, "Полное имя", it, subject, id)
            }
            p.optString("company").takeIf { it.isNotBlank() }?.let {
                facts += fact(FactCategory.ORGANIZATION, "Организация", it.trimStart('@'), subject, id)
            }
            p.optString("location").takeIf { it.isNotBlank() }?.let {
                facts += fact(FactCategory.LOCATION, "Город", it, subject, id)
            }
            p.optString("blog").takeIf { it.isNotBlank() }?.let {
                facts += fact(FactCategory.TECHNICAL, "Сайт", it, subject, id)
            }
            p.optString("email").takeIf { it.isNotBlank() }?.let {
                facts += fact(FactCategory.CONTACT, "Почта", it, subject, id)
            }
            p.optString("twitter_username").takeIf { it.isNotBlank() }?.let {
                facts += fact(FactCategory.PERSON, "Аккаунт X", "@$it", subject, id)
            }
        }
        // Поиск по коду: токен даёт доступ к /search/code — упоминания глубже профиля.
        if (token.isNotBlank()) {
            runCatching {
                val code = Net.json(
                    "https://api.github.com/search/code?q=${Net.urlEncode("\"" + query.trim() + "\"")}&per_page=5",
                    headers
                ).getOrNull()
                val items = code?.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until minOf(items.length(), 5)) {
                        val item = items.optJSONObject(i) ?: continue
                        val url = item.optString("html_url")
                        if (url.isBlank()) continue
                        facts += fact(
                            FactCategory.TECHNICAL, "Упоминание в коде GitHub", url, query, id,
                            buildMap {
                                item.optString("name").takeIf { it.isNotBlank() }?.let { put("Файл", it) }
                                item.optJSONObject("repository")?.optString("full_name")
                                    ?.takeIf { it.isNotBlank() }?.let { put("Репозиторий", it) }
                            }
                        )
                    }
                }
            }
        }
        return facts
    }
}

private object HibpConnector : Connector {
    override val id = "hibp"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        if (QueryDetector.detect(query) != QueryKind.EMAIL) return emptyList()
        val token = key.key(id)
        val response = Net.fetch(
            "https://haveibeenpwned.com/api/v3/breachedaccount/${Net.urlEncode(query)}?truncateResponse=false",
            mapOf("hibp-api-key" to token)
        )
        if (response.code == 404) return emptyList()
        if (!response.ok) return emptyList()
        val array = JSONArray(response.body)
        return (0 until array.length()).take(12).map { i ->
            val breach = array.getJSONObject(i)
            fact(
                FactCategory.OTHER, "Утечка", breach.optString("Title"), query, id,
                mapOf(
                    "Дата" to breach.optString("BreachDate"),
                    "Домен" to breach.optString("Domain"),
                    "Записей" to breach.optInt("PwnCount").toString(),
                    "Данные" to breach.optJSONArray("DataClasses").joined()
                ).filterValues { it.isNotBlank() }
            )
        }
    }
}

/**
 * LeakCheck Public API: участие адреса, телефона, ника или домена в утечках.
 * Отдаёт только сводку — сколько записей, какие наборы данных и какие типы полей.
 * Используется как бесплатный режим и как откат, когда тариф без полных записей.
 */
internal object LeakCheckApi {

    const val BASE = "https://leakcheck.io/api/public"
    private const val MAX_SOURCES = 12

    private val typeByKind = mapOf(
        QueryKind.EMAIL to "email",
        QueryKind.PHONE to "phone",
        QueryKind.USERNAME to "username",
        QueryKind.DOMAIN to "domain"
    )

    internal val fieldTitles = mapOf(
        "email" to "почта", "phone" to "телефон", "name" to "имя", "first_name" to "имя",
        "last_name" to "фамилия", "middle_name" to "отчество", "dob" to "дата рождения",
        "address" to "адрес", "city" to "город", "state" to "регион", "zip" to "индекс",
        "country" to "страна", "ip" to "IP", "ip1" to "IP", "ip2" to "IP",
        "username" to "ник", "password" to "пароль", "gender" to "пол",
        "company_name" to "компания", "origin" to "источник", "profile_name" to "профиль",
        "ssn" to "SSN", "id" to "ID"
    )

    /** Адрес запроса или null, если такой тип значения сервис не проверяет. */
    fun url(query: String, kind: QueryKind, key: String): String? {
        val type = typeByKind[kind] ?: return null
        return "$BASE?key=${Net.urlEncode(key)}&check=${Net.urlEncode(query)}&type=$type"
    }

    fun parse(body: String, query: String, moduleId: String = "leakcheck"): List<RawFact> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!json.optBoolean("success")) return emptyList()
        val found = json.optInt("found", 0)
        if (found <= 0) return emptyList()

        val facts = ArrayList<RawFact>()
        val names = ArrayList<String>()
        val sources = json.optJSONArray("sources")
        if (sources != null) {
            for (i in 0 until minOf(sources.length(), MAX_SOURCES)) {
                val source = sources.optJSONObject(i) ?: continue
                val name = source.optString("name").trim()
                if (name.isBlank() || names.contains(name)) continue
                names += name
                facts += fact(
                    FactCategory.OTHER, "Утечка", name, query, moduleId,
                    mapOf("Дата" to source.optString("date").trim()).filterValues { it.isNotBlank() }
                )
            }
        }
        facts += fact(
            FactCategory.OTHER, "Записей в утечках", found.toString(), query, moduleId,
            buildMap {
                if (names.isNotEmpty()) put("Наборы данных", names.joinToString(", "))
                if (sources != null && sources.length() > MAX_SOURCES) {
                    put("Показано наборов", "$MAX_SOURCES из ${sources.length()}")
                }
            }
        )
        val fields = json.optJSONArray("fields").joined()
        if (fields.isNotBlank()) {
            val titles = fields.split(", ").map { it.trim() }.filter { it.isNotBlank() }
                .map { fieldTitles[it] ?: it }.distinct().joinToString(", ")
            if (titles.isNotBlank()) facts += fact(FactCategory.OTHER, "Типы данных", titles, query, moduleId)
        }
        return facts
    }
}

/**
 * LeakCheck Pro API v2 — полные записи, включая пароли.
 *
 * GET https://leakcheck.io/api/v2/query/{query}?type=…&limit=…
 * Ключ идёт заголовком X-API-Key, в адрес не попадает (адрес виден в логах и прокси).
 * Тариф Basic отдаёт только признак пароля; Monthly/Lifetime — сами записи.
 */
internal object LeakCheckPro {

    const val BASE = "https://leakcheck.io/api/v2/query/"
    private const val MAX_RECORDS = 60
    private const val MAX_LOGINS = 3
    private const val MAX_PASSWORDS = 6

    private val typeByKind = mapOf(
        QueryKind.EMAIL to "email",
        QueryKind.PHONE to "phone",
        QueryKind.USERNAME to "username",
        QueryKind.DOMAIN to "domain",
        QueryKind.NAME to "keyword"
    )

    /** Адрес запроса или null, если сервис такой тип не ищет. */
    fun url(query: String, kind: QueryKind, limit: Int = 100): String? {
        val type = typeByKind[kind] ?: return null
        return BASE + Net.urlEncode(query.trim()) + "?type=" + type + "&limit=" + limit
    }

    fun headers(key: String): Map<String, String> =
        mapOf("X-API-Key" to key, "Accept" to "application/json")

    private class SourceAcc {
        var date: String = ""
        val logins = LinkedHashSet<String>()
        val passwords = LinkedHashSet<String>()
        val extras = LinkedHashSet<String>()
        var records = 0
        var passwordless = 0
    }

    fun parse(body: String, subject: String, moduleId: String = "leakcheck"): List<RawFact> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!json.optBoolean("success")) return emptyList()
        val found = json.optInt("found", 0)
        val result = json.optJSONArray("result") ?: return emptyList()
        if (found <= 0 || result.length() == 0) return emptyList()

        val sources = LinkedHashMap<String, SourceAcc>()
        val shown = minOf(result.length(), MAX_RECORDS)
        for (i in 0 until shown) {
            val record = result.optJSONObject(i) ?: continue
            val source = record.optJSONObject("source")
            val name = source?.optString("name").orEmpty().trim().ifBlank { "Набор без названия" }
            val acc = sources.getOrPut(name) { SourceAcc() }
            acc.records++
            if (acc.date.isBlank()) acc.date = source?.optString("breach_date").orEmpty().trim()
            listOf("email", "username", "phone").forEach { field ->
                record.optString(field).trim().takeIf { it.isNotBlank() }?.let { acc.logins += it }
            }
            val password = record.optString("password").trim()
            if (password.isNotBlank()) acc.passwords += password
            else if (record.optInt("passwordless", 0) == 1) acc.passwordless++
            record.optJSONArray("fields")?.let { fields ->
                for (j in 0 until fields.length()) {
                    val field = fields.optString(j).trim()
                    if (field.isBlank() || field == "email" || field == "username" ||
                        field == "phone" || field == "password"
                    ) continue
                    acc.extras += LeakCheckApi.fieldTitles[field] ?: field
                }
            }
        }

        val facts = ArrayList<RawFact>()
        for ((name, acc) in sources) {
            val attrs = LinkedHashMap<String, String>()
            if (acc.date.isNotBlank()) attrs["Дата"] = acc.date
            if (acc.logins.isNotEmpty()) attrs["Логин"] = clipped(acc.logins, MAX_LOGINS)
            if (acc.passwords.isNotEmpty()) attrs["Пароль"] = clipped(acc.passwords, MAX_PASSWORDS)
            if (acc.passwordless > 0) attrs["Без пароля"] = acc.passwordless.toString()
            if (acc.extras.isNotEmpty()) attrs["Ещё"] = acc.extras.joinToString(", ")
            attrs["Записей"] = acc.records.toString()
            facts += fact(FactCategory.OTHER, "Утечка", name, subject, moduleId, attrs)
        }

        val summary = LinkedHashMap<String, String>()
        if (found > shown) summary["Показано записей"] = "$shown из $found"
        val quota = json.optInt("quota", -1)
        if (quota >= 0) summary["Остаток запросов"] = quota.toString()
        facts += fact(FactCategory.OTHER, "Записей в утечках", found.toString(), subject, moduleId, summary)
        return facts
    }

    private fun clipped(values: Set<String>, limit: Int): String {
        val head = values.take(limit).joinToString(", ")
        return if (values.size > limit) "$head …" else head
    }
}

/** Служебная пометка модуля: попала в результат — значит, источник честно отказал. */
private fun status(subject: String, moduleId: String, text: String): RawFact =
    fact(FactCategory.OTHER, "Статус", text, subject, moduleId)

private object LeakCheckConnector : Connector {
    override val id = "leakcheck"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val subject = query.trim()
        val secret = key.key(id)
        if (secret.isBlank()) return emptyList()
        val kind = QueryDetector.detect(subject)
        val url = LeakCheckPro.url(subject, kind) ?: return emptyList()
        val response = Net.fetch(url, LeakCheckPro.headers(secret), attempts = 2)
        if (response.ok) return LeakCheckPro.parse(response.body, subject, id)

        return when (response.code) {
            401 -> listOf(status(subject, id, "Ключ LeakCheck отклонён (401) — проверьте ключ в настройках"))
            403 -> {
                val limited = response.body.contains("limit", ignoreCase = true)
                val note = if (limited) "Лимит запросов по тарифу исчерпан (403)"
                else "Тариф без полных записей (403) — показана бесплатная сводка"
                val public = LeakCheckApi.url(subject, kind, secret)
                    ?.let { Net.fetch(it) }
                    ?.takeIf { it.ok }
                    ?.let { LeakCheckApi.parse(it.body, subject, id) }
                    .orEmpty()
                listOf(status(subject, id, note)) + public
            }
            429 -> listOf(status(subject, id, "LeakCheck: слишком много запросов (429), повторите позже"))
            else -> emptyList()
        }
    }
}

/**
 * Hudson Rock Cavalier OSINT Tools — бесплатный источник без ключа.
 *
 * Показывает компьютеры, заражённые инфостилерами (RedLine, Lumma, Raccoon…):
 * дату заражения, имя машины, ОС, IP, семейство вредоноса и то, что стилер увёл,
 * — логины и пароли. Это комплементарен утечкам баз: HIBP/LeakCheck знают про
 * взломы сервисов, Hudson Rock — про заражённые устройства.
 *
 * GET /api/json/v2/osint-tools/search-by-email?email=…
 * GET /api/json/v2/osint-tools/search-by-username?username=…
 */
internal object HudsonRockApi {

    const val BASE = "https://cavalier.hudsonrock.com/api/json/v2/osint-tools"
    private const val MAX_STEALERS = 10
    private const val MAX_VALUES = 8

    fun url(query: String, kind: QueryKind): String? = when (kind) {
        QueryKind.EMAIL -> "$BASE/search-by-email?email=${Net.urlEncode(query.trim())}"
        QueryKind.USERNAME ->
            "$BASE/search-by-username?username=${Net.urlEncode(query.trim().trimStart('@'))}"
        else -> null
    }

    fun parse(body: String, subject: String, moduleId: String = "hudsonrock"): List<RawFact> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val stealers = json.optJSONArray("stealers") ?: return emptyList()
        if (stealers.length() == 0) return emptyList()

        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(stealers.length(), MAX_STEALERS)) {
            val stealer = stealers.optJSONObject(i) ?: continue
            val date = stealer.optString("date_compromised").trim().take(10)
            val machine = stealer.optString("computer_name").trim()
            val attrs = LinkedHashMap<String, String>()
            if (date.isNotBlank()) attrs["Дата"] = date
            stealer.optString("stealer").trim().ifBlank { stealer.optString("stealer_family").trim() }
                .takeIf { it.isNotBlank() }?.let { attrs["Вредонос"] = it }
            machine.takeIf { it.isNotBlank() }?.let { attrs["Компьютер"] = it }
            stealer.optString("operating_system").trim().takeIf { it.isNotBlank() }
                ?.let { attrs["ОС"] = it }
            stealer.optString("ip").trim().takeIf { it.isNotBlank() }?.let { attrs["IP"] = it }
            stealer.optString("malware_path").trim().takeIf { it.isNotBlank() }
                ?.let { attrs["Файл"] = it }
            values(stealer, "antiviruses").takeIf { it.isNotBlank() }?.let { attrs["Антивирусы"] = it }
            values(stealer, "top_logins").takeIf { it.isNotBlank() }?.let { attrs["Логины"] = it }
            values(stealer, "top_passwords").takeIf { it.isNotBlank() }?.let { attrs["Пароли"] = it }
            facts += fact(
                FactCategory.OTHER, "Заражение инфостилером",
                machine.ifBlank { date.ifBlank { "заражённое устройство" } },
                subject, moduleId, attrs
            )
        }
        facts += fact(
            FactCategory.OTHER, "Всего заражений", stealers.length().toString(), subject, moduleId,
            mapOf(
                "Корпоративных сервисов" to json.optInt("total_corporate_services", 0).toString(),
                "Личных сервисов" to json.optInt("total_user_services", 0).toString()
            ).filterValues { it != "0" }
        )
        return facts
    }

    private fun values(node: JSONObject, key: String, limit: Int = MAX_VALUES): String {
        val array = node.optJSONArray(key) ?: return ""
        val items = ArrayList<String>()
        for (i in 0 until array.length()) {
            array.optString(i).trim().takeIf { it.isNotBlank() }?.let { items += it }
        }
        val distinct = items.distinct()
        if (distinct.isEmpty()) return ""
        val head = distinct.take(limit).joinToString(", ")
        return if (distinct.size > limit) "$head …" else head
    }
}

private object HudsonRockConnector : Connector {
    override val id = "hudsonrock"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val subject = query.trim()
        val url = HudsonRockApi.url(subject, QueryDetector.detect(subject)) ?: return emptyList()
        val response = Net.fetch(url, mapOf("Accept" to "application/json"))
        if (!response.ok) return emptyList()
        return HudsonRockApi.parse(response.body, subject, id)
    }
}

private object VirusTotalConnector : Connector {
    override val id = "virustotal"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val kind = QueryDetector.detect(query)
        if (kind != QueryKind.DOMAIN && kind != QueryKind.IP) return emptyList()
        val path = if (kind == QueryKind.IP) "ip_addresses" else "domains"
        val json = Net.json(
            "https://www.virustotal.com/api/v3/$path/${Net.urlEncode(query)}",
            mapOf("x-apikey" to key.key(id))
        ).getOrNull() ?: return emptyList()
        val attrs = json.optJSONObject("data")?.optJSONObject("attributes") ?: return emptyList()
        val stats = attrs.optJSONObject("last_analysis_stats")
        val detected = (stats?.optInt("malicious") ?: 0) + (stats?.optInt("suspicious") ?: 0)
        val facts = ArrayList<RawFact>()
        facts += fact(
            FactCategory.TECHNICAL, if (kind == QueryKind.IP) "IP-адрес" else "Домен", query, query, id,
            buildMap {
                stats?.optInt("harmless")?.let { put("Безопасных детектов", it.toString()) }
                put("Подозрительных детектов", detected.toString())
                attrs.optString("registrar").takeIf { it.isNotBlank() }?.let { put("Регистратор", it) }
                attrs.optLong("reputation", 0).takeIf { it != 0L }?.let { put("Репутация", it.toString()) }
            }
        )
        attrs.optJSONArray("tags").joined().takeIf { it.isNotBlank() }?.let {
            facts += fact(FactCategory.TECHNICAL, "Теги", it, query, id)
        }
        return facts
    }
}

private object ShodanConnector : Connector {
    override val id = "shodan"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val kind = QueryDetector.detect(query)
        if (kind != QueryKind.IP && kind != QueryKind.DOMAIN) return emptyList()
        val json = Net.json(
            if (kind == QueryKind.IP)
                "https://api.shodan.io/shodan/host/${Net.urlEncode(query)}?key=${Net.urlEncode(key.key(id))}"
            else
                "https://api.shodan.io/dns/domain/${Net.urlEncode(query)}?key=${Net.urlEncode(key.key(id))}"
        ).getOrNull() ?: return emptyList()
        if (json.has("error")) return emptyList()
        val facts = ArrayList<RawFact>()
        val host = json.optString("ip_str")
        if (host.isNotBlank()) {
            facts += fact(
                FactCategory.TECHNICAL, "IP-адрес", host, query, id,
                buildMap {
                    json.optString("org").takeIf { it.isNotBlank() }?.let { put("Организация", it) }
                    json.optString("isp").takeIf { it.isNotBlank() }?.let { put("Провайдер", it) }
                    json.optString("os").takeIf { it.isNotBlank() }?.let { put("ОС", it) }
                    json.optString("country_name").takeIf { it.isNotBlank() }?.let { put("Страна", it) }
                }
            )
            json.optJSONArray("ports").joined().takeIf { it.isNotBlank() }?.let {
                facts += fact(FactCategory.TECHNICAL, "Открытые порты", it, query, id)
            }
            json.optJSONArray("hostnames").joined().takeIf { it.isNotBlank() }?.let {
                facts += fact(FactCategory.TECHNICAL, "Хосты", it, query, id)
            }
        }
        json.optJSONArray("domain").joined().takeIf { it.isNotBlank() }?.let {
            facts += fact(FactCategory.TECHNICAL, "Поддомены", it, query, id)
        }
        return facts
    }
}

private object NumverifyConnector : Connector {
    override val id = "numverify"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        if (QueryDetector.detect(query) != QueryKind.PHONE) return emptyList()
        val json = Net.json(
            "https://apilayer.net/api/validate?access_key=${Net.urlEncode(key.key(id))}" +
                "&number=${Net.urlEncode(query.filter { it.isDigit() || it == '+' })}&format=1"
        ).getOrNull() ?: return emptyList()
        if (!json.optBoolean("valid", false)) return emptyList()
        val facts = ArrayList<RawFact>()
        facts += fact(
            FactCategory.CONTACT, "Телефон", json.optString("international_format", query), query, id,
            buildMap {
                json.optString("country_name").takeIf { it.isNotBlank() }?.let { put("Страна", it) }
                json.optString("location").takeIf { it.isNotBlank() }?.let { put("Регион", it) }
            }
        )
        json.optString("carrier").takeIf { it.isNotBlank() }?.let {
            facts += fact(FactCategory.CONTACT, "Оператор связи", it, query, id)
        }
        json.optString("line_type").takeIf { it.isNotBlank() }?.let {
            facts += fact(FactCategory.CONTACT, "Тип линии", it, query, id)
        }
        return facts
    }
}

private object HunterConnector : Connector {
    override val id = "hunter"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val kind = QueryDetector.detect(query)
        if (kind != QueryKind.DOMAIN) return emptyList()
        val json = Net.json(
            "https://api.hunter.io/v2/domain-search?domain=${Net.urlEncode(query)}&limit=10&api_key=${Net.urlEncode(key.key(id))}"
        ).getOrNull() ?: return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        val org = data.optString("organization").ifBlank { query }
        facts += fact(FactCategory.ORGANIZATION, "Организация", org, query, id)
        data.optJSONArray("emails")?.let { emails ->
            for (i in 0 until minOf(emails.length(), 10)) {
                val item = emails.getJSONObject(i)
                val email = item.optString("value")
                if (email.isBlank()) continue
                val person = listOf(item.optString("first_name"), item.optString("last_name"))
                    .filter { it.isNotBlank() }.joinToString(" ")
                facts += fact(
                    FactCategory.CONTACT, "Почта", email, person.ifBlank { org }, id,
                    buildMap {
                        item.optString("position").takeIf { it.isNotBlank() }?.let { put("Должность", it) }
                        item.optString("confidence").takeIf { it.isNotBlank() }?.let { put("Достоверность", "$it%") }
                    }
                )
                if (person.isNotBlank()) {
                    facts += fact(FactCategory.PERSON, "Сотрудник", person, person, id)
                }
            }
        }
        data.optString("pattern").takeIf { it.isNotBlank() }?.let {
            facts += fact(FactCategory.CONTACT, "Шаблон адресов", it, org, id)
        }
        return facts
    }
}

private object IpinfoConnector : Connector {
    override val id = "ipinfo"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val kind = QueryDetector.detect(query)
        if (kind != QueryKind.IP) return emptyList()
        val token = key.key(id)
        val url = "https://ipinfo.io/${Net.urlEncode(query)}/json" + if (token.isNotBlank()) "?token=${Net.urlEncode(token)}" else ""
        val json = Net.json(url).getOrNull() ?: return emptyList()
        if (json.has("bogon") && json.optBoolean("bogon")) return emptyList()
        val facts = ArrayList<RawFact>()
        facts += fact(
            FactCategory.TECHNICAL, "IP-адрес", query, query, id,
            buildMap {
                json.optString("org").takeIf { it.isNotBlank() }?.let { put("Организация", it) }
                json.optString("hostname").takeIf { it.isNotBlank() }?.let { put("PTR", it) }
            }
        )
        val city = json.optString("city")
        val region = json.optString("region")
        val country = json.optString("country")
        if (city.isNotBlank() || country.isNotBlank()) {
            facts += fact(
                FactCategory.LOCATION, "Геолокация",
                listOf(city, region, country).filter { it.isNotBlank() }.joinToString(", "), query, id
            )
        }
        json.optString("loc").takeIf { it.isNotBlank() }?.let {
            facts += fact(FactCategory.LOCATION, "Координаты", it, query, id)
        }
        json.optString("timezone").takeIf { it.isNotBlank() }?.let {
            facts += fact(FactCategory.LOCATION, "Часовой пояс", it, query, id)
        }
        return facts
    }
}

private object TgstatConnector : Connector {
    override val id = "tgstat"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val json = Net.json(
            "https://api.tgstat.ru/channels/search?token=${Net.urlEncode(key.key(id))}&q=${Net.urlEncode(query)}&limit=8"
        ).getOrNull() ?: return emptyList()
        val items = json.optJSONObject("response")?.optJSONArray("items") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(items.length(), 8)) {
            val channel = items.getJSONObject(i)
            val title = channel.optString("title")
            if (title.isBlank()) continue
            facts += fact(
                FactCategory.OTHER, "Telegram-канал", title, query, id,
                buildMap {
                    channel.optString("username").takeIf { it.isNotBlank() }?.let { put("Ссылка", "t.me/$it") }
                    channel.optInt("participants_count", 0).takeIf { it > 0 }
                        ?.let { put("Подписчиков", it.toString()) }
                    channel.optString("description").takeIf { it.isNotBlank() }
                        ?.let { put("Описание", it.take(160)) }
                }
            )
        }
        // Упоминания в постах каналов — то, чего не даёт поиск по карточкам.
        runCatching {
            val posts = Net.json(
                "https://api.tgstat.ru/posts/search?token=${Net.urlEncode(key.key(id))}" +
                    "&q=${Net.urlEncode(query)}&limit=10"
            ).getOrNull()
            val items = posts?.optJSONObject("response")?.optJSONArray("items")
            if (items != null) {
                for (i in 0 until minOf(items.length(), 10)) {
                    val post = items.optJSONObject(i) ?: continue
                    val link = post.optString("link")
                    if (link.isBlank()) continue
                    facts += fact(
                        FactCategory.OTHER, "Упоминание в Telegram", link, query, id,
                        buildMap {
                            post.optString("channel_title").takeIf { it.isNotBlank() }
                                ?.let { put("Канал", it) }
                            post.optString("text").takeIf { it.isNotBlank() }
                                ?.let { put("Текст", it.take(220)) }
                            post.optLong("date", 0L).takeIf { it > 0 }
                                ?.let { put("Дата", java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US).format(java.util.Date(it * 1000))) }
                        }
                    )
                }
            }
        }
        return facts
    }
}

private object VkConnector : Connector {
    override val id = "vk"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val json = Net.json(
            "https://api.vk.com/method/users.search?q=${Net.urlEncode(query)}&count=5&fields=city,domain,bdate,about" +
                "&access_token=${Net.urlEncode(key.key(id))}&v=5.199"
        ).getOrNull() ?: return emptyList()
        if (json.has("error")) return emptyList()
        val items = json.optJSONObject("response")?.optJSONArray("items") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        // Посты: пользовательский токен VK умеет искать по стене — это глубже карточки профиля.
        runCatching {
            val token = key.key(id)
            if (token.isNotBlank()) {
                val wall = Net.json(
                    "https://api.vk.com/method/wall.search?q=${Net.urlEncode(query)}&count=10&extended=0" +
                        "&access_token=${Net.urlEncode(token)}&v=5.199"
                ).getOrNull()
                val posts = wall?.optJSONObject("response")?.optJSONArray("items")
                if (posts != null) {
                    for (i in 0 until minOf(posts.length(), 10)) {
                        val post = posts.optJSONObject(i) ?: continue
                        val text = post.optString("text").take(200)
                        if (text.isBlank()) continue
                        facts += fact(
                            FactCategory.OTHER, "Пост ВКонтакте", "vk.com/wall${post.optLong("owner_id")}_${post.optLong("id")}",
                            query, id,
                            buildMap {
                                put("Текст", text)
                                post.optInt("likes", 0).takeIf { it > 0 }?.let { put("Лайков", it.toString()) }
                                post.optInt("date", 0).takeIf { it > 0 }?.let {
                                    put("Дата", java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US)
                                        .format(java.util.Date(it * 1000L)))
                                }
                            }
                        )
                    }
                }
            }
        }
        for (i in 0 until minOf(items.length(), 5)) {
            val user = items.getJSONObject(i)
            val name = listOf(user.optString("first_name"), user.optString("last_name"))
                .filter { it.isNotBlank() }.joinToString(" ")
            if (name.isBlank()) continue
            facts += fact(
                FactCategory.PERSON, "Профиль", "vk.com/${user.optString("domain")}", name, id,
                buildMap {
                    user.optJSONObject("city")?.optString("title")?.takeIf { it.isNotBlank() }
                        ?.let { put("Город", it) }
                    user.optString("bdate").takeIf { it.isNotBlank() }?.let { put("Дата рождения", it) }
                    put("ID", user.optLong("id").toString())
                }
            )
        }
        return facts
    }
}

/**
 * Dyxless API v3 — платный поиск по записям. Устроен не как остальные:
 * поиск асинхронный (создать → дождаться state → забрать результаты),
 * поэтому модуль работает в три шага и тратит деньги владельца ключа.
 *
 * Защита от лишних трат: без второго поля «Максимум ₽ на запрос» модуль
 * молчит, а сам запрос строится только по точным идентификаторам
 * (email, телефон, IP, полное ФИО, Telegram-ник) — на произвольный текст
 * и домены Dyxless не ищет, и деньги за такой запрос не списываются.
 */
private object DyxlessConnector : Connector {
    override val id = "dyxless"
    private const val BASE = "https://api.dyxless.im"

    /** Сколько всего ждём результат. Telegram-поиск бывает медленнее обычного. */
    private const val DEADLINE_MS = 60_000L
    private const val MIN_DELAY_MS = 1_200L
    private const val MAX_DELAY_MS = 8_000L

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val token = key.key(id)
        if (token.isBlank()) return emptyList()
        val cap = key.secondField(id).replace(',', '.').trim().toDoubleOrNull()
            ?.takeIf { it > 0 } ?: return emptyList()
        val body = DyxlessApi.request(query.trim(), cap) ?: return emptyList()
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Idempotency-Key" to "jb-" + java.util.UUID.randomUUID().toString(),
            "Prefer" to "wait=5"
        )

        val created = Net.fetch(
            "$BASE/v3/searches", headers, attempts = 1, method = "POST", body = body.toString()
        )
        // 401/402/403 — ключ, баланс или права: повторять и опрашивать нечего.
        if (created.code !in 200..299) return emptyList()
        val meta = runCatching { JSONObject(created.body) }.getOrNull() ?: return emptyList()
        val operationId = meta.optString("operationId").ifBlank { meta.optString("requestId") }
        if (operationId.isBlank()) return emptyList()

        var state = meta.optString("state")
        var resultUrl = meta.optString("resultUrl")
        var wait = created.retryAfter?.times(1000L) ?: MIN_DELAY_MS
        val until = System.currentTimeMillis() + DEADLINE_MS
        while (state !in DyxlessApi.TERMINAL_OK) {
            if (state in DyxlessApi.TERMINAL_FAIL) return emptyList()
            if (System.currentTimeMillis() >= until) return emptyList()
            delay(wait.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS))
            val status = Net.fetch("$BASE/v3/searches/$operationId", headers, attempts = 1)
            if (status.code !in 200..299) continue
            val json = runCatching { JSONObject(status.body) }.getOrNull() ?: continue
            state = json.optString("state")
            if (resultUrl.isBlank()) resultUrl = json.optString("resultUrl")
            wait = (status.retryAfter?.times(1000L) ?: (wait + 800L))
        }

        val page = Net.fetch(
            if (resultUrl.isNotBlank()) DyxlessApi.absolute(resultUrl)
            else "$BASE/v3/searches/$operationId/results",
            headers,
            attempts = 1
        )
        if (page.code !in 200..299) return emptyList()
        val json = runCatching { JSONObject(page.body) }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()
        return DyxlessApi.parse(data, query)
    }
}

/** Тело запроса и разбор ответа Dyxless: чистые функции, их удобно проверять тестами. */
internal object DyxlessApi {

    const val BASE = "https://api.dyxless.im"
    val TERMINAL_OK = setOf("succeeded", "partially_succeeded")
    val TERMINAL_FAIL = setOf("failed", "cancelled", "expired")
    private const val MAX_FACTS = 30

    private val titles = mapOf(
        "email" to "Почта",
        "phone" to "Телефон",
        "last_name" to "Фамилия",
        "first_name" to "Имя",
        "sur_name" to "Отчество",
        "birthday" to "Дата рождения",
        "inn" to "ИНН",
        "snils" to "СНИЛС",
        "passport" to "Паспорт",
        "vin" to "VIN",
        "car_number" to "Госномер",
        "ip" to "IP-адрес",
        "address" to "Адрес",
        "region" to "Регион",
        "city" to "Город",
        "table_name" to "База",
        "username" to "Никнейм",
        "telegram_id" to "Telegram ID"
    )

    fun absolute(path: String): String =
        if (path.startsWith("https://")) path
        else if (path.startsWith("/")) BASE + path
        else "$BASE/$path"

    /** Тело запроса: тип поиска, условие и жёсткий лимит расхода. Null — искать нечего. */
    fun request(query: String, cap: Double): JSONObject? {
        val raw = query.trim()
        // Явные подсказки: `id:123456789` — Telegram по ID, `tg:@ник` — по нику.
        // Без них номер из 9–11 цифр не отличить от телефона.
        val forcedId = when {
            raw.startsWith("id:", ignoreCase = true) -> raw.substring(3).trim()
            raw.startsWith("tgid:", ignoreCase = true) -> raw.substring(5).trim()
            else -> null
        }
        val kind = if (forcedId != null) QueryKind.USERNAME else QueryDetector.detect(raw)
        var product = "records.search"
        val condition: JSONObject = when {
            forcedId != null -> {
                if (forcedId.isEmpty() || !forcedId.all { it.isDigit() }) return null
                product = "telegram.search"
                cond("id", forcedId)
            }
            kind == QueryKind.EMAIL -> cond("email", raw)
            kind == QueryKind.PHONE -> cond("phone", phoneDigits(raw))
            kind == QueryKind.IP -> cond("ip", raw)
            kind == QueryKind.USERNAME -> {
                product = "telegram.search"
                cond("username", raw.trimStart('@'))
            }
            kind == QueryKind.NAME -> {
                val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                // Dyxless не ищет по частичному имени: нужны три поля ФИО.
                if (parts.size < 3) return null
                JSONObject().put(
                    "all",
                    JSONArray().apply {
                        put(cond("last_name", parts[0]))
                        put(cond("first_name", parts[1]))
                        put(cond("sur_name", parts[2]))
                    }
                )
            }
            else -> return null
        }
        val select = if (product == "records.search") {
            JSONArray().put("all")
        } else {
            JSONArray().put("baseName").put("fields")
        }
        val amount = String.format(java.util.Locale.US, "%.6f", cap.coerceIn(0.01, 10_000.0))
        return JSONObject()
            .put("product", product)
            .put("query", condition)
            .put("select", select)
            .put("result", JSONObject().put("maxRecords", 50).put("pageSize", 50))
            .put("billing", JSONObject().put("maxAmount", amount).put("currency", "RUB"))
    }

    private fun cond(field: String, value: String) =
        JSONObject().put("field", field).put("op", "eq").put("value", value)

    fun phoneDigits(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (raw.trim().startsWith("+")) "+$digits" else digits
    }

    fun parse(data: JSONArray, query: String): List<RawFact> {
        val out = ArrayList<RawFact>()
        for (i in 0 until minOf(data.length(), MAX_FACTS)) {
            val record = data.optJSONObject(i) ?: continue
            val attrs = LinkedHashMap<String, String>()
            val fields = record.optJSONObject("fields")
            if (fields != null) {
                fields.keys().forEach { name ->
                    val value = fields.opt(name)?.toString().orEmpty()
                    if (value.isNotBlank() && value != "null") attrs[title(name)] = value
                }
            } else {
                record.keys().forEach { name ->
                    val value = record.opt(name)?.toString().orEmpty()
                    val flat = value.isNotBlank() && value != "null" &&
                        !value.startsWith("{") && !value.startsWith("[")
                    if (flat) attrs[title(name)] = value
                }
            }
            if (attrs.isEmpty()) continue
            val baseName = record.optString("baseName")
            val email = attrs["Почта"].orEmpty()
            val phone = attrs["Телефон"].orEmpty()
            val fio = listOf(attrs["Фамилия"], attrs["Имя"], attrs["Отчество"])
                .filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
            val value = when {
                email.isNotBlank() -> email
                phone.isNotBlank() -> phone
                fio.isNotBlank() -> fio
                baseName.isNotBlank() -> baseName
                else -> query
            }
            val category = when {
                email.isNotBlank() || phone.isNotBlank() -> FactCategory.CONTACT
                fio.isNotBlank() -> FactCategory.PERSON
                else -> FactCategory.OTHER
            }
            val kind = if (baseName.isNotBlank()) baseName.take(60) else "Запись"
            out += RawFact(
                category = category,
                kind = kind,
                value = value,
                subject = fio.ifBlank { value },
                attributes = attrs.entries.take(10).associate { it.key to it.value.take(120) },
                sourceId = "dyxless"
            )
        }
        return out
    }

    fun title(name: String): String =
        titles[name.lowercase()] ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}


private object OpenCorporatesConnector : Connector {
    override val id = "opencorporates"
    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val json = Net.json(
            "https://api.opencorporates.com/v0.4/companies/search?q=${Net.urlEncode(query)}" +
                "&per_page=5&api_token=${Net.urlEncode(key.key(id))}"
        ).getOrNull() ?: return emptyList()
        val results = json.optJSONObject("results")?.optJSONArray("companies") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(results.length(), 5)) {
            val company = results.getJSONObject(i).optJSONObject("company") ?: continue
            val name = company.optString("name")
            if (name.isBlank()) continue
            facts += fact(
                FactCategory.ORGANIZATION, "Организация", name, name, id,
                buildMap {
                    company.optString("company_number").takeIf { it.isNotBlank() }?.let { put("Номер", it) }
                    company.optString("jurisdiction_code").takeIf { it.isNotBlank() }?.let { put("Юрисдикция", it) }
                    company.optString("incorporation_date").takeIf { it.isNotBlank() }?.let { put("Регистрация", it) }
                    company.optString("address_inline").takeIf { it.isNotBlank() }?.let { put("Адрес", it) }
                }
            )
            company.optString("opencorporates_url").takeIf { it.isNotBlank() }?.let {
                facts += fact(FactCategory.ORGANIZATION, "Карточка", it, name, id)
            }
        }
        return facts
    }
}

private object GoogleConnector : Connector {
    override val id = "google"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val cx = key.secondField(id)
        if (cx.isBlank()) return emptyList()
        val json = Net.json(
            "https://www.googleapis.com/customsearch/v1?key=${Net.urlEncode(key.key(id))}" +
                "&cx=${Net.urlEncode(cx)}&num=8&q=${Net.urlEncode("\"$query\"")}"
        ).getOrNull() ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(items.length(), 8)) {
            val item = items.getJSONObject(i)
            val link = item.optString("link")
            if (link.isBlank()) continue
            facts += fact(
                FactCategory.OTHER, "Упоминание", link, query, id,
                buildMap {
                    item.optString("title").takeIf { it.isNotBlank() }?.let { put("Заголовок", it.take(120)) }
                    item.optString("snippet").takeIf { it.isNotBlank() }?.let { put("Фрагмент", it.take(200)) }
                }
            )
        }
        return facts
    }
}

private object YandexConnector : Connector {
    override val id = "yandex"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val folder = key.secondField(id)
        if (folder.isBlank()) return emptyList()
        val response = Net.fetch(
            "https://yandex.ru/search/xml?folderid=${Net.urlEncode(folder)}" +
                "&apikey=${Net.urlEncode(key.key(id))}&query=${Net.urlEncode(query)}&lr=213&groupby=attr%3D%22%22.mode%3Dflat.groups-on-page%3D8"
        )
        if (!response.ok) return emptyList()
        val docRegex = Regex("<doc>(.*?)</doc>", RegexOption.DOT_MATCHES_ALL)
        val urlRegex = Regex("<url>(.*?)</url>")
        val titleRegex = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        return docRegex.findAll(response.body).take(8).mapNotNull { match ->
            val block = match.groupValues[1]
            val url = urlRegex.find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val title = titleRegex.find(block)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
            fact(
                FactCategory.OTHER, "Упоминание", url, query, id,
                buildMap { if (title.isNotBlank()) put("Заголовок", title.take(120)) }
            )
        }.toList()
    }
}


private object BraveConnector : Connector {
    override val id = "brave"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val json = Net.json(
            "https://api.search.brave.com/res/v1/web/search?q=${Net.urlEncode(query)}&count=8",
            mapOf("X-Subscription-Token" to key.key(id), "Accept" to "application/json")
        ).getOrNull() ?: return emptyList()
        val results = json.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(results.length(), 8)) {
            val item = results.getJSONObject(i)
            val link = item.optString("url")
            if (link.isBlank()) continue
            facts += fact(
                FactCategory.OTHER, "Упоминание", link, query, id,
                buildMap {
                    item.optString("title").takeIf { it.isNotBlank() }?.let { put("Заголовок", it.take(120)) }
                    item.optString("description").takeIf { it.isNotBlank() }?.let { put("Фрагмент", it.take(200)) }
                }
            )
        }
        return facts
    }
}

private object SerpApiConnector : Connector {
    override val id = "serpapi"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val json = Net.json(
            "https://serpapi.com/search.json?engine=google&num=8&q=${Net.urlEncode(query)}" +
                "&api_key=${Net.urlEncode(key.key(id))}"
        ).getOrNull() ?: return emptyList()
        val results = json.optJSONArray("organic_results") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(results.length(), 8)) {
            val item = results.getJSONObject(i)
            val link = item.optString("link")
            if (link.isBlank()) continue
            facts += fact(
                FactCategory.OTHER, "Упоминание", link, query, id,
                buildMap {
                    item.optString("title").takeIf { it.isNotBlank() }?.let { put("Заголовок", it.take(120)) }
                    item.optString("snippet").takeIf { it.isNotBlank() }?.let { put("Фрагмент", it.take(200)) }
                }
            )
        }
        return facts
    }
}

/** Публичный API без ключа: мгновенный ответ и связанные темы. */
private object DdgConnector : Connector {
    override val id = "ddg"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val json = Net.json(
            "https://api.duckduckgo.com/?q=${Net.urlEncode(query)}&format=json&no_html=1&skip_disambig=1"
        ).getOrNull() ?: return emptyList()
        val facts = ArrayList<RawFact>()
        val abstractText = json.optString("AbstractText")
        val abstractUrl = json.optString("AbstractURL")
        if (abstractUrl.isNotBlank()) {
            facts += fact(
                FactCategory.OTHER, "Упоминание", abstractUrl, query, id,
                buildMap { if (abstractText.isNotBlank()) put("Фрагмент", abstractText.take(240)) }
            )
        }
        val topics = ArrayList<JSONObject>()
        fun walk(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val nested = item.optJSONArray("Topics")
                if (nested != null) walk(nested) else topics += item
            }
        }
        walk(json.optJSONArray("RelatedTopics"))
        topics.take(8).forEach { topic ->
            val url = topic.optString("FirstURL")
            if (url.isBlank()) return@forEach
            val text = topic.optString("Text")
            facts += fact(
                FactCategory.OTHER, "Упоминание", url, query, id,
                buildMap { if (text.isNotBlank()) put("Фрагмент", text.take(200)) }
            )
        }
        return facts.distinctBy { it.value }.take(8)
    }
}

/** Публичный поиск MediaWiki: статьи и совпадения по запросу. */
private object WikipediaConnector : Connector {
    override val id = "wikipedia"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val json = Net.json(
            "https://ru.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1" +
                "&srlimit=8&srsearch=${Net.urlEncode(query)}"
        ).getOrNull() ?: return emptyList()
        val items = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val title = item.optString("title")
            if (title.isBlank()) continue
            val url = "https://ru.wikipedia.org/wiki/" + Net.urlEncode(title.replace(' ', '_'))
            val snippet = item.optString("snippet").replace(Regex("<[^>]+>"), "").trim()
            facts += fact(
                FactCategory.OTHER, "Упоминание", url, query, id,
                buildMap {
                    put("Заголовок", title.take(120))
                    if (snippet.isNotBlank()) put("Фрагмент", snippet.take(200))
                }
            )
        }
        return facts
    }
}

/** Публичный поиск сканов urlscan.io. */
private object UrlscanConnector : Connector {
    override val id = "urlscan"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val json = Net.json(
            "https://urlscan.io/api/v1/search/?q=${Net.urlEncode(query)}&size=8"
        ).getOrNull() ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(results.length(), 8)) {
            val item = results.getJSONObject(i)
            val page = item.optJSONObject("page") ?: continue
            val url = page.optString("url")
            if (url.isBlank()) continue
            facts += fact(
                FactCategory.OTHER, "Упоминание", url, query, id,
                buildMap {
                    page.optString("domain").takeIf { it.isNotBlank() }?.let { put("Домен", it) }
                    page.optString("ip").takeIf { it.isNotBlank() }?.let { put("IP", it) }
                    page.optString("country").takeIf { it.isNotBlank() }?.let { put("Страна", it) }
                }
            )
        }
        return facts
    }
}

/** Поддомены домена по данным SecurityTrails. */
private object SecurityTrailsConnector : Connector {
    override val id = "securitytrails"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        if (QueryDetector.detect(query) != QueryKind.DOMAIN) return emptyList()
        val json = Net.json(
            "https://api.securitytrails.com/v1/domain/${Net.urlEncode(query)}/subdomains",
            mapOf("APIKEY" to key.key(id), "Accept" to "application/json")
        ).getOrNull() ?: return emptyList()
        val subs = json.optJSONArray("subdomains") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(subs.length(), 12)) {
            val sub = subs.optString(i)
            if (sub.isBlank()) continue
            facts += fact(
                FactCategory.TECHNICAL, "Поддомен", "$sub.$query", query, id
            )
        }
        return facts
    }
}

/** Репутация IP-адреса по данным AbuseIPDB. */
private object AbuseIpdbConnector : Connector {
    override val id = "abuseipdb"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        if (QueryDetector.detect(query) != QueryKind.IP) return emptyList()
        val json = Net.json(
            "https://api.abuseipdb.com/api/v2/check?ipAddress=${Net.urlEncode(query)}&maxAgeInDays=90",
            mapOf("Key" to key.key(id), "Accept" to "application/json")
        ).getOrNull() ?: return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        val score = data.optInt("abuseConfidenceScore", -1)
        val attrs = buildMap {
            if (score >= 0) put("Оценка злоупотреблений", "$score%")
            data.optString("countryCode").takeIf { it.isNotBlank() }?.let { put("Страна", it) }
            data.optString("isp").takeIf { it.isNotBlank() }?.let { put("Провайдер", it) }
            data.optString("usageType").takeIf { it.isNotBlank() }?.let { put("Тип использования", it) }
            if (data.has("totalReports")) put("Жалоб", data.optInt("totalReports").toString())
            data.optString("domain").takeIf { it.isNotBlank() }?.let { put("Домен", it) }
        }
        return listOf(fact(FactCategory.TECHNICAL, "IP-адрес", query, query, id, attrs))
    }
}

/** Публичные профили GitLab по нику. */
private object GitlabConnector : Connector {
    override val id = "gitlab"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val username = query.removePrefix("@").substringBefore(' ').trim()
        if (username.isBlank()) return emptyList()
        val response = runCatching {
            Net.fetch("https://gitlab.com/api/v4/users?username=${Net.urlEncode(username)}")
        }.getOrNull() ?: return emptyList()
        if (!response.ok) return emptyList()
        val users = runCatching { JSONArray(response.body) }.getOrNull() ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(users.length(), 5)) {
            val user = users.optJSONObject(i) ?: continue
            val nick = user.optString("username")
            if (nick.isBlank()) continue
            val url = user.optString("web_url").ifBlank { "https://gitlab.com/$nick" }
            facts += fact(
                FactCategory.PERSON, "Профиль", url, query, id,
                buildMap {
                    user.optString("name").takeIf { it.isNotBlank() }?.let { put("Имя", it) }
                    put("Никнейм", nick)
                    user.optString("public_email").takeIf { it.isNotBlank() }?.let { put("Почта", it) }
                }
            )
        }
        return facts
    }
}

/** RDAP/WHOIS — встроенный модуль без ключа, работает для доменов. */
object RdapConnector : Connector {
    override val id = "rdap"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        if (QueryDetector.detect(query) != QueryKind.DOMAIN) return emptyList()
        val json = Net.json("https://rdap.org/domain/${Net.urlEncode(query)}").getOrNull() ?: return emptyList()
        val facts = ArrayList<RawFact>()
        val attrs = LinkedHashMap<String, String>()
        json.optJSONArray("events")?.let { events ->
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val action = event.optString("eventAction")
                val date = event.optString("eventDate").take(10)
                val label = when (action) {
                    "registration" -> "Создан"
                    "expiration" -> "Истекает"
                    "last changed" -> "Обновлён"
                    else -> action
                }
                if (date.isNotBlank()) attrs[label] = date
            }
        }
        json.optJSONArray("nameservers")?.let { ns ->
            val list = ArrayList<String>()
            for (i in 0 until ns.length()) {
                ns.getJSONObject(i).optString("ldhName").takeIf { it.isNotBlank() }?.let { list += it.lowercase() }
            }
            if (list.isNotEmpty()) attrs["NS"] = list.take(4).joinToString(", ")
        }
        json.optJSONArray("status").joined().takeIf { it.isNotBlank() }?.let {
            attrs["Статус"] = it.replace(" ", "")
        }
        facts += fact(FactCategory.TECHNICAL, "Домен", query, query, id, attrs)
        json.optJSONArray("entities")?.let { entities ->
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val roles = entity.optJSONArray("roles").joined(",")
                if (!roles.contains("registrar")) continue
                val vcard = entity.optJSONArray("vcardArray")
                val name = vcard?.optJSONArray(1)?.let { items ->
                    (0 until items.length()).mapNotNull { j ->
                        val entry = items.getJSONArray(j)
                        if (entry.optString(0) == "fn") entry.optString(3) else null
                    }.firstOrNull()
                }
                if (!name.isNullOrBlank()) {
                    facts += fact(FactCategory.ORGANIZATION, "Регистратор", name, query, id)
                }
            }
        }
        return facts
    }
}

/**
 * Демонстрационный модуль: детерминированный набор сведений без обращения к сети.
 * Нужен для знакомства с приложением и проверки отчётов без ключей.
 */
object DemoConnector : Connector {
    override val id = "demo"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> = withContext(Dispatchers.Default) {
        val seed = query.lowercase().hashCode().absoluteValue
        val subject = if (QueryDetector.detect(query) == QueryKind.NAME) query else "Объект «$query»"
        val cities = listOf("Москва", "Санкт-Петербург", "Казань", "Екатеринбург", "Новосибирск")
        val orgs = listOf("ООО «Вектор»", "ООО «Ромашка»", "АО «Северный поток»", "ИП Смирнов А. В.")
        val sites = listOf("example.ru", "demo-site.org", "portal-24.net")
        val facts = ArrayList<RawFact>()
        fun rnd(bound: Int, salt: Int) = ((seed / (salt + 1) + salt * 37) % bound + bound) % bound

        facts += fact(FactCategory.PERSON, "Полное имя", subject, subject, id)
        facts += fact(FactCategory.PERSON, "Профиль", "user_${seed % 9000}", subject, id,
            mapOf("Сеть" to "демо-источник", "Активность" to "2024-2026"))
        facts += fact(FactCategory.PERSON, "Профиль", "user_${seed % 9000}", subject, id)
        facts += fact(FactCategory.CONTACT, "Почта", "contact${seed % 1000}@mail.example", subject, id)
        facts += fact(FactCategory.CONTACT, "Почта", "contact${seed % 1000}@mail.example", subject, id)
        facts += fact(FactCategory.CONTACT, "Телефон", "+7 (9${seed % 10}) ${100 + seed % 899}-${10 + seed % 89}-${10 + seed % 89}", subject, id)
        facts += fact(FactCategory.LOCATION, "Город", cities[rnd(cities.size, 1)], subject, id)
        facts += fact(FactCategory.LOCATION, "Город", cities[rnd(cities.size, 1)], subject, id)
        facts += fact(FactCategory.ORGANIZATION, "Организация", orgs[rnd(orgs.size, 2)], subject, id,
            mapOf("ИНН" to "77${seed % 10000000}".padEnd(10, '0').take(10)))
        facts += fact(FactCategory.ORGANIZATION, "Организация", orgs[rnd(orgs.size, 2)], subject, id)
        facts += fact(FactCategory.TECHNICAL, "Домен", sites[rnd(sites.size, 3)], subject, id,
            mapOf("Регистратор" to "RU-CENTER", "Создан" to "201${seed % 9}-0${1 + seed % 9}-1${seed % 9}"))
        facts += fact(FactCategory.TECHNICAL, "Домен", sites[rnd(sites.size, 3)], subject, id)
        facts += fact(FactCategory.TECHNICAL, "IP-адрес", "203.0.113.${seed % 250 + 1}", subject, id,
            mapOf("ASN" to "AS${10000 + seed % 5000}", "Страна" to "RU"))
        facts += fact(FactCategory.TECHNICAL, "MX-запись", "mx1.${sites[rnd(sites.size, 3)]}", subject, id)
        facts += fact(FactCategory.DOCUMENT, "Изображение", "photo_${seed % 1000}.jpg", subject, id,
            mapOf("Устройство" to "Canon EOS 5D Mark IV", "GPS" to "55.7${seed % 9}, 37.6${seed % 9}"))
        facts += fact(FactCategory.OTHER, "Упоминание", "публикация на отраслевом портале", subject, id)

        // Второй субъект — чтобы в отчёте появились перекрёстные связи.
        val second = "Связанный объект ${seed % 100}"
        facts += fact(FactCategory.PERSON, "Полное имя", second, second, id)
        facts += fact(FactCategory.CONTACT, "Почта", "contact${seed % 1000}@mail.example", second, id)
        facts += fact(FactCategory.TECHNICAL, "Домен", sites[rnd(sites.size, 3)], second, id)
        facts
    }
}



/** Reddit: публичный JSON-поиск без ключа — посты и обсуждения по запросу. */
private object RedditConnector : Connector {
    override val id = "reddit"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        // Reddit блокирует часть дата-центров: пробуем оба хоста, иначе честно пусто.
        val headers = mapOf("User-Agent" to "JBOSINT/1.0 (osint research)")
        val json = listOf("https://www.reddit.com", "https://old.reddit.com")
            .firstNotNullOfOrNull { host ->
                runCatching {
                    Net.json("$host/search.json?q=${Net.urlEncode(query)}&limit=10&sort=relevance", headers)
                        .getOrNull()
                }.getOrNull()
            } ?: return emptyList()
        val children = json.optJSONObject("data")?.optJSONArray("children") ?: return emptyList()
        val facts = ArrayList<RawFact>()
        for (i in 0 until minOf(children.length(), 10)) {
            val post = children.optJSONObject(i)?.optJSONObject("data") ?: continue
            val permalink = post.optString("permalink")
            val title = post.optString("title")
            if (permalink.isBlank() || title.isBlank()) continue
            facts += fact(
                FactCategory.OTHER, "Пост Reddit", "https://www.reddit.com$permalink", query, id,
                buildMap {
                    put("Заголовок", title.take(160))
                    post.optString("subreddit").takeIf { it.isNotBlank() }?.let { put("Сабреддит", "r/$it") }
                    post.optInt("num_comments", 0).takeIf { it > 0 }?.let { put("Комментариев", it.toString()) }
                    post.optString("author").takeIf { it.isNotBlank() }?.let { put("Автор", "u/$it") }
                    post.optLong("created_utc", 0L).takeIf { it > 0 }?.let {
                        put("Дата", java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US).format(java.util.Date(it * 1000)))
                    }
                }
            )
            post.optString("author").takeIf { it.isNotBlank() && it != "[deleted]" }?.let {
                facts += fact(FactCategory.PERSON, "Никнейм", "@$it", title, id)
            }
        }
        return facts.distinctBy { it.kind to it.value }
    }
}

class SearchEngine(
    private val keys: KeySource,
    private val settings: SettingsStore
) {

    suspend fun search(
        query: String,
        onStage: (String, Int) -> Unit = { _, _ -> },
        customModules: List<Pair<CustomModule, String>> = emptyList()
    ): AggregatedData = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val cleanQuery = query.trim()
        val active = keys.enabledWithKeys()
        val modules = ArrayList<Connector>()
        val rdap = QueryDetector.detect(cleanQuery) == QueryKind.DOMAIN
        if (rdap) modules += RdapConnector
        ProviderCatalog.all.forEach { spec ->
            if (active.containsKey(spec.id)) Connectors.byId(spec.id)?.let { modules += it }
        }
        customModules.forEach { (module, key) ->
            // Стабильный нейтральный слот: не зависит от порядка и не раскрывает сервис.
            modules += CustomConnector(module, key, "custom_" + module.id.take(8))
        }
        val demo = settings.demoMode
        if (demo) modules += DemoConnector

        // Открытые источники работают всегда: без ключей у пользователя иначе не
        // запускается ни один модуль, и поиск выглядит «сломанным».
        val freeFallback = modules.isEmpty() || modules.all { it.id == "rdap" || it.id == "demo" }
        if (freeFallback) {
            KEYLESS_FALLBACK.forEach { id -> Connectors.byId(id)?.let { modules += it } }
        }
        val sourceNames = customModules.associate { "custom_" + it.first.id.take(8) to it.first.title }

        onStage(if (freeFallback) "Открытые источники (ключи не заданы)" else "Запуск модулей", 0)
        var responded = 0
        var failed = 0
        val rawFacts = java.util.Collections.synchronizedList(ArrayList<RawFact>())

        coroutineScope {
            modules.mapIndexed { index, module ->
                async {
                    val startedAt = System.currentTimeMillis()
                    val result = runCatching {
                        kotlinx.coroutines.withTimeout(25_000) { module.collect(cleanQuery, keys) }
                    }
                    val took = System.currentTimeMillis() - startedAt
                    if (result.isSuccess) {
                        val items = result.getOrThrow()
                        rawFacts += items.map { item ->
                            if (item.sourceUrl.isNotBlank()) item
                            else item.copy(sourceUrl = EvidenceLinks.url(module.id, cleanQuery, item.value))
                        }
                        SearchJournal.record(module.id, cleanQuery, items.size, items.isNotEmpty(), took)
                        ModuleRunLog.add(ModuleRunEntry(moduleId = module.id, action = "search_collect", ok = true, durationMs = took, facts = items.size))
                        synchronized(this@SearchEngine) { responded++ }
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "ошибка запроса"
                        SearchJournal.record(module.id, cleanQuery, 0, false, took, err)
                        ModuleRunLog.add(ModuleRunEntry(moduleId = module.id, action = "search_collect", ok = false, durationMs = took, message = err))
                        synchronized(this@SearchEngine) { failed++ }
                    }
                    onStage("Модуль ${index + 1} из ${modules.size}", ((index + 1) * 100) / modules.size.coerceAtLeast(1))
                }
            }.awaitAll()
        }

        val stats = SearchStats(
            sourcesAttempted = modules.size,
            sourcesResponded = responded,
            sourcesFailed = failed,
            durationMs = System.currentTimeMillis() - started
        )
        onStage("Агрегация и проверка", 100)
        val sources = modules.map { module ->
            ru.jbosint.osint.core.model.SourceRef(
                id = module.id,
                name = sourceNames[module.id] ?: ru.jbosint.osint.core.report.SourceCatalog.ref(module.id).name,
                url = ru.jbosint.osint.core.report.SourceCatalog.ref(module.id).url
            )
        }.distinctBy { it.id }
        val aggregate = Aggregator.aggregate(cleanQuery, rawFacts.toList(), stats).copy(sources = sources)

        // «От одного — ко всему»: из найденных контактов берём точные идентификаторы
        // (почта, телефон, ник) и добираем по ним утечки. Только модули утечек и только
        // при наличии ключей, поэтому в пустой конфигурации проход ничего не стоит.
        onStage("Проверка связанных контактов", 95)
        val extra = runCatching { DigitalFootprintGraph(keys).expand(aggregate) }.getOrDefault(emptyList())
        if (extra.isEmpty()) return@withContext aggregate
        val mergedStats = stats.copy(rawFacts = extra.size)
        Aggregator.aggregate(cleanQuery, rawFacts.toList() + extra, mergedStats).copy(sources = sources)
    }

    companion object {
        /**
         * Открытые модули, которые запускаются даже без ключей. Именно они
         * спасают ситуацию «нет ни одного включённого модуля — поиск молчит».
         */
        val KEYLESS_FALLBACK = listOf("ddg", "wikipedia", "gitlab", "urlscan", "hudsonrock")

        /** Нужен ли запуск открытых источников вместо пустого списка модулей. */
        fun needsFallback(current: Collection<String>): Boolean =
            current.isEmpty() || current.all { it == "rdap" || it == "demo" }

        fun fallbackModules(): List<String> = KEYLESS_FALLBACK
    }
}
