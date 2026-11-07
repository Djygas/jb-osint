package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact

/**
 * Прямой клиент TeleSINT API (https://api.telesint.dev/v1).
 *
 * Авторизация — заголовок `Api-Key` с 32-символьным токеном.
 * Каждый запрос платный (1–15 ₽), поэтому вызывать только по явному действию
 * пользователя и не повторять запросы при ошибках 4xx.
 */
object TeleSint {

    const val BASE = "https://api.telesint.dev/v1"
    const val SITE = "https://telesint.dev"
    const val DOCS = "https://docs.telesint.dev/en/api/"

    /** Размер страницы участников группы: сервис отдаёт максимум 1000. */
    const val MAX_PAGE_SIZE = 1000

    class ApiException(val code: Int, message: String) : Exception(message)

    data class Account(
        val balance: Double,
        val currency: String,
        val login: String,
        val roles: List<String>,
        val resetAt: String
    )

    data class Member(
        val id: String,
        val username: String,
        val firstName: String,
        val lastName: String,
        val phone: String,
        val premium: Boolean,
        val bot: Boolean,
        val lastOnline: String
    ) {
        val displayName: String
            get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { username.ifBlank { id } }
    }

    data class MemberPage(
        val members: List<Member>,
        val page: Int,
        val pageSize: Int,
        val hasNext: Boolean,
        val total: Int
    )

    /** Похож ли ввод на токен TeleSINT: 32 символа, буквы/цифры, дефис, подчёркивание. */
    fun looksLikeKey(raw: String): Boolean {
        val key = raw.trim()
        if (key.length != 32) return false
        return key.all { ch ->
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_'
        }
    }

    /** Бесплатная проверка токена: баланс, логин, роли. */
    suspend fun account(key: String): Account {
        val root = request("$BASE/me/", key)
        val data = payload(root) ?: root
        return Account(
            balance = data.optDouble("balance", data.optDouble("credits", 0.0)),
            currency = data.optString("currency").ifBlank { "RUB" },
            login = data.optString("login").ifBlank { data.optString("username") },
            roles = strings(data.opt("roles")),
            resetAt = data.optString("token_reset_at").ifBlank { data.optString("reset_at") }
        )
    }

    /** Карточка пользователя Telegram по @username, ID или телефону. */
    suspend fun user(key: String, identifier: String): List<RawFact> {
        val data = payload(request("$BASE/users/${Net.urlEncode(identifier.trimStart('@'))}", key))
            ?: return emptyList()
        return userFacts(data, identifier)
    }

    /** Похожие пользователи (по аватару/нику/связям) — бесплатное расширение карточки. */
    suspend fun similarUsers(key: String, identifier: String): List<RawFact> {
        val root = request("$BASE/users/${Net.urlEncode(identifier.trimStart('@'))}/similar", key)
        return items(root).flatMap { userFacts(it, identifier) }
    }

    /** Карточка канала или чата. */
    suspend fun group(key: String, identifier: String): List<RawFact> {
        val data = payload(request("$BASE/groups/${Net.urlEncode(identifier.trimStart('@'))}", key))
            ?: return emptyList()
        return groupFacts(data)
    }

    suspend fun similarGroups(key: String, identifier: String): List<RawFact> {
        val root = request("$BASE/groups/${Net.urlEncode(identifier.trimStart('@'))}/similar", key)
        return items(root).flatMap { groupFacts(it) }
    }

    /**
     * Одна страница участников группы. Первый запрос платный, повторные
     * обращения к той же группе в течение часа бесплатны.
     */
    suspend fun members(
        key: String,
        identifier: String,
        page: Int = 1,
        pageSize: Int = 200
    ): MemberPage {
        val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val url = "$BASE/groups/${Net.urlEncode(identifier.trimStart('@'))}/members" +
            "?page=$page&page_size=$size"
        return memberPageOf(request(url, key), page, size)
    }

    /** Разбор ответа `/groups/{id}/members` — вынесен отдельно, чтобы покрыть тестом. */
    internal fun memberPageOf(root: JSONObject, page: Int, size: Int): MemberPage {
        val data = payload(root) ?: root
        val list = data.optJSONArray("members")
            ?: data.optJSONArray("items")
            ?: data.optJSONArray("users")
            ?: root.optJSONArray("members")
            ?: root.optJSONArray("data")
            ?: root.optJSONArray("results")
            ?: JSONArray()
        val pagination = data.optJSONObject("pagination") ?: root.optJSONObject("pagination")
        val members = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let(::parseMember)
        }
        return MemberPage(
            members = members,
            page = pagination?.optInt("page", page) ?: page,
            pageSize = pagination?.optInt("page_size", size) ?: size,
            hasNext = pagination?.optBoolean("has_next_page")
                ?: pagination?.optBoolean("has_next")
                ?: (members.size >= size),
            total = pagination?.optInt("total", 0) ?: data.optInt("total", 0)
        )
    }

    /** Поиск пользователей и групп по запросу (без полных карточек). */
    suspend fun search(key: String, query: String, limit: Int = 20): List<RawFact> {
        val url = "$BASE/search/?query=${Net.urlEncode(query)}&limit=${limit.coerceIn(1, 100)}"
        val root = runCatching { request(url, key) }.getOrElse { error ->
            if (error is ApiException && error.code == 400) return emptyList() else throw error
        }
        val facts = ArrayList<RawFact>()
        val users = root.optJSONArray("users") ?: JSONObject(root.toString()).optJSONArray("users")
        if (users != null) for (i in 0 until users.length()) {
            users.optJSONObject(i)?.let { facts += userFacts(it, query) }
        }
        val groups = root.optJSONArray("groups")
        if (groups != null) for (i in 0 until groups.length()) {
            groups.optJSONObject(i)?.let { facts += groupFacts(it) }
        }
        if (facts.isEmpty()) facts += items(root).flatMap { item ->
            if (item.has("members") || item.has("title")) groupFacts(item) else userFacts(item, query)
        }
        return facts.distinctBy { it.kind to it.value }
    }

    // ------------------------------------------------------------ разбор ответов

    private fun userFacts(data: JSONObject, query: String): List<RawFact> {
        val id = data.optString("id").ifBlank { data.optString("user_id") }
        val username = data.optString("username").ifBlank {
            data.optString("usernames").trim().trim('[', ']', '"')
        }.substringBefore(',').trim()
        val first = data.optString("first_name").ifBlank { data.optString("firstName") }
        val last = data.optString("last_name").ifBlank { data.optString("lastName") }
        val name = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { data.optString("name") }
        val phone = data.optString("phone").ifBlank { data.optString("phone_number") }
        val value = when {
            username.isNotBlank() -> "@$username"
            id.isNotBlank() -> "ID $id"
            phone.isNotBlank() -> phone
            else -> return emptyList()
        }
        val subject = name.ifBlank { value }
        val link = data.optString("link").ifBlank {
            if (username.isNotBlank()) "https://t.me/$username" else ""
        }
        val attrs = buildMap {
            if (id.isNotBlank()) put("ИД", id)
            if (username.isNotBlank()) put("Ник", "@$username")
            if (phone.isNotBlank()) put("Телефон", phone)
            if (link.isNotBlank()) put("Ссылка", link)
            data.optString("last_online").takeIf { it.isNotBlank() }?.let { put("Последняя активность", it) }
            data.optString("status").takeIf { it.isNotBlank() }?.let { put("Статус", it) }
            if (data.optBoolean("premium")) put("Premium", "да")
            if (data.optBoolean("bot")) put("Бот", "да")
            if (data.optBoolean("scam")) put("Признак скама", "да")
            if (data.optBoolean("fake")) put("Признак фейка", "да")
            strings(data.opt("languages")).takeIf { it.isNotEmpty() }
                ?.let { put("Языки", it.joinToString(", ")) }
            strings(data.opt("names_history") ?: data.opt("name_history")).takeIf { it.isNotEmpty() }
                ?.let { put("История имён", it.joinToString(" → ").take(200)) }
        }
        val facts = ArrayList<RawFact>()
        facts += RawFact(
            category = FactCategory.PERSON,
            kind = "Telegram-профиль",
            value = value,
            subject = subject,
            attributes = attrs,
            sourceId = "telesint",
            weight = 2,
            sourceUrl = link
        )
        if (name.isNotBlank()) {
            facts += RawFact(FactCategory.PERSON, "Полное имя", name, subject, emptyMap(), "telesint", 2, link)
        }
        if (phone.isNotBlank() && phone != query) {
            facts += RawFact(FactCategory.CONTACT, "Телефон", phone, subject, emptyMap(), "telesint", 2, link)
        }
        val memberships = items(data.opt("groups") ?: data.opt("chats"))
        for (group in memberships) {
            val title = group.optString("title").ifBlank { group.optString("username") }
            if (title.isBlank()) continue
            facts += RawFact(
                FactCategory.OTHER,
                "Членство в канале",
                title,
                subject,
                buildMap {
                    group.optString("username").takeIf { it.isNotBlank() }
                        ?.let { put("Ссылка", "https://t.me/${it.trimStart('@')}") }
                    group.optString("role").takeIf { it.isNotBlank() }?.let { put("Роль", it) }
                },
                "telesint",
                1,
                ""
            )
        }
        return facts
    }

    private fun groupFacts(data: JSONObject): List<RawFact> {
        val title = data.optString("title").ifBlank { data.optString("name") }
        val username = data.optString("username").trimStart('@')
        val id = data.optString("id").ifBlank { data.optString("group_id") }
        val value = title.ifBlank { if (username.isNotBlank()) "@$username" else "ID $id" }
        if (value.isBlank()) return emptyList()
        val link = data.optString("link").ifBlank {
            if (username.isNotBlank()) "https://t.me/$username" else ""
        }
        val members = data.optInt("members_count", data.optInt("participants_count", 0))
        val kind = when {
            data.optBoolean("is_channel") || data.optBoolean("channel") -> "Telegram-канал"
            else -> "Telegram-чат"
        }
        val attrs = buildMap {
            if (members > 0) put("Подписчиков", members.toString())
            if (link.isNotBlank()) put("Ссылка", link)
            if (username.isNotBlank()) put("Ник", "@$username")
            if (id.isNotBlank()) put("ИД", id)
            data.optString("description").takeIf { it.isNotBlank() }?.let { put("Описание", it.take(300)) }
            data.optString("created_at").takeIf { it.isNotBlank() }?.let { put("Создан", it.take(10)) }
            if (data.optBoolean("scam")) put("Признак скама", "да")
            if (data.optBoolean("fake")) put("Признак фейка", "да")
            strings(data.opt("languages")).takeIf { it.isNotEmpty() }
                ?.let { put("Языки", it.joinToString(", ")) }
        }
        return listOf(
            RawFact(
                category = FactCategory.OTHER,
                kind = kind,
                value = value,
                subject = value,
                attributes = attrs,
                sourceId = "telesint",
                weight = 2,
                sourceUrl = link
            )
        )
    }

    private fun parseMember(json: JSONObject): Member {
        val first = json.optString("first_name").ifBlank { json.optString("firstName") }
        val last = json.optString("last_name").ifBlank { json.optString("lastName") }
        return Member(
            id = json.optString("id").ifBlank { json.optString("user_id") },
            username = json.optString("username").trimStart('@'),
            firstName = first,
            lastName = last,
            phone = json.optString("phone").ifBlank { json.optString("phone_number") },
            premium = json.optBoolean("premium"),
            bot = json.optBoolean("bot"),
            lastOnline = json.optString("last_online").ifBlank { json.optString("status") }
        )
    }

    // ------------------------------------------------------------ транспорт

    private suspend fun request(url: String, key: String): JSONObject {
        if (key.isBlank()) throw ApiException(401, "Ключ TeleSINT не задан")
        val response = Net.fetch(
            url,
            headers = mapOf("Api-Key" to key.trim(), "Accept" to "application/json"),
            attempts = 2
        )
        val parsed = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.code !in 200..299) {
            throw ApiException(response.code, messageFor(response.code, parsed))
        }
        return parsed ?: throw ApiException(response.code, "Некорректный ответ сервиса")
    }

    private fun messageFor(code: Int, body: JSONObject?): String {
        val detail = body?.optString("detail").orEmpty()
            .ifBlank { body?.optString("message").orEmpty() }
            .ifBlank { body?.optString("error").orEmpty() }
        val base = when (code) {
            400 -> "Недостаточно средств на балансе TeleSINT или некорректный параметр"
            401 -> "Неверный или отозванный ключ TeleSINT"
            403 -> "Доступ к методу закрыт тарифом"
            404 -> "Объект не найден в базе TeleSINT"
            429 -> "Превышен лимит запросов, повторите позже"
            503 -> "Сервис ограничения частоты недоступен, повторите позже"
            else -> "Ошибка TeleSINT: HTTP $code"
        }
        return if (detail.isNotBlank()) "$base ($detail)" else base
    }

    /** Разворачивает конверт `{"data": ...}` / `{"result": ...}`. */
    private fun payload(root: JSONObject): JSONObject? {
        for (key in listOf("data", "result", "user", "group")) {
            val nested = root.optJSONObject(key)
            if (nested != null) return nested
        }
        return if (root.has("id") || root.has("username") || root.has("title")) root else null
    }

    /** Список объектов из `data.items`, `data`, `results`, `users`, `groups`. */
    private fun items(value: Any?): List<JSONObject> {
        val array = when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("items")
                ?: value.optJSONArray("data")
                ?: value.optJSONArray("results")
                ?: value.optJSONArray("users")
                ?: value.optJSONArray("groups")
            else -> null
        } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun strings(value: Any?): List<String> {
        val array = value as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
