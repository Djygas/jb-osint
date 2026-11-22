package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact

/**
 * Telelog/Funstat API client (OpenAPI: https://telelog.info/swagger/v1/swagger.json).
 * Auth: Authorization: Bearer <JWT>. Costs come from tech.request_cost/current_ballance.
 */
object TelelogLike {

    class ApiException(val code: Int, message: String) : Exception(message)

    data class Account(val ok: Boolean, val detail: String)
    data class RawReport(val endpoint: String, val body: String)
    data class QueryResult(val facts: List<RawFact>, val warnings: List<String> = emptyList(), val rawReports: List<RawReport> = emptyList())

    private fun base(id: String) = when (id) {
        "funstat" -> "https://funstat.info/api/v1"
        else -> "https://telelog.info/api/v1"
    }

    private fun headers(key: String) = mapOf(
        "Authorization" to "Bearer ${key.trim()}",
        "Accept" to "application/json"
    )

    suspend fun account(id: String, key: String): Account {
        val selfId = resolveSelfId(key)
        if (selfId != null) {
            val (code, body) = get(id, key, "/users/basic_info_by_id", mapOf("id" to selfId.toString()))
            if (code in 200..299) return Account(true, "Ключ принят (Telelog/Funstat API)")
            if (code == 401 || code == 403) throw ApiException(code, bodyMessage(code, body))
        }
        val (c2, b2) = get(id, key, "/users/resolve_username", mapOf("name" to "telegram"))
        if (c2 in 200..299) return Account(true, "Ключ принят (Telelog/Funstat API)")
        throw ApiException(c2, bodyMessage(c2, b2))
    }

    private fun resolveSelfId(jwt: String): Long? = runCatching {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        val b64 = parts[1].replace('-', '+').replace('_', '/')
        val pad = (4 - b64.length % 4) % 4
        val payload = String(java.util.Base64.getDecoder().decode(b64 + "=".repeat(pad)))
        JSONObject(payload).optString("uid").toLongOrNull()
    }.getOrNull()

    suspend fun query(id: String, key: String, input: TelegramRules.Result): List<RawFact> = queryDetailed(id, key, input).facts

    suspend fun queryDetailed(id: String, key: String, input: TelegramRules.Result): QueryResult {
        val warnings = ArrayList<String>()
        val facts = ArrayList<RawFact>()
        val raw = ArrayList<RawReport>()

        when (input.kind) {
            TelegramRules.Kind.USERNAME, TelegramRules.Kind.USER_ID -> {
                val user = resolveUser(id, key, input, raw)
                if (user == null) {
                    warnings += "${label(id)}: пользователь не найден"
                } else {
                    facts += userFacts(id, key, user, input.normalized, warnings, raw)
                }
            }
            TelegramRules.Kind.GROUP, TelegramRules.Kind.GROUP_ID -> {
                val gid = resolveGroupId(id, key, input, warnings, raw)
                if (gid != null) facts += groupFacts(id, key, gid, input.normalized, warnings, raw)
                else warnings += "${label(id)}: группа/канал не найдены или ID не подтверждён"
            }
            TelegramRules.Kind.GROUP_STATS -> {
                val gid = resolveGroupId(id, key, input, warnings, raw)
                if (gid != null) facts += groupStatsFacts(id, key, gid, input.normalized, warnings, raw)
                else warnings += "${label(id)}: для статистики нужен ID/username группы, доступный API"
            }
            TelegramRules.Kind.MESSAGE_LINK -> facts += messageLinkFacts(id, key, input, warnings)
            TelegramRules.Kind.FORWARDED_GROUP_MESSAGE, TelegramRules.Kind.CHANNEL_FORWARD_POST -> {
                warnings += "${label(id)}: прямой endpoint для пересылок поста/сообщения в публичной спецификации не подтверждён; показаны доступные косвенные данные"
                facts += forwardedFacts(id, key, input, warnings)
            }
            TelegramRules.Kind.CONTACT_OR_RECENT -> warnings += "${label(id)}: импорт контактов/недавних не поддерживается публичным API; используйте username/ID"
            TelegramRules.Kind.STICKER -> facts += stickerFacts(id, key, input, warnings, raw)
            TelegramRules.Kind.INVALID -> {}
        }
        if (facts.isEmpty() && warnings.isEmpty()) warnings += "${label(id)}: нет данных по запросу"
        return QueryResult(facts.distinctBy { it.kind to it.value to it.subject }, warnings.distinct(), raw)
    }

    private fun label(id: String) = if (id == "funstat") "Funstat" else "Telelog"

    private data class ResolvedUser(val id: Long, val username: String, val firstName: String, val lastName: String, val active: Boolean, val bot: Boolean, val premium: Boolean)

    private suspend fun resolveUser(id: String, key: String, input: TelegramRules.Result, raw: MutableList<RawReport>): ResolvedUser? {
        val reqPath: String; val reqParams: Map<String,String>
        if (input.username.isNotBlank()) { reqPath = "/users/resolve_username"; reqParams = mapOf("name" to input.username) }
        else { val uid = input.numericId.filter { it.isDigit() }.toLongOrNull() ?: return null; reqPath = "/users/basic_info_by_id"; reqParams = mapOf("id" to uid.toString()) }
        val (code, body) = get(id, key, reqPath, reqParams)
        if (code in 200..299) raw += RawReport(reqPath, body)
        if (code == 401 || code == 403) throw ApiException(code, bodyMessage(code, body))
        if (code !in 200..299) return null
        val arr = dataArray(body)
        val o = arr.optJSONObject(0) ?: return null
        val uid = o.optLong("id")
        if (uid <= 0L) return null
        return ResolvedUser(
            id = uid,
            username = o.optString("username").trim(),
            firstName = o.optString("first_name").trim(),
            lastName = o.optString("last_name").trim(),
            active = o.optBoolean("is_active", false),
            bot = o.optBoolean("is_bot", false),
            premium = o.optBoolean("has_premium", false)
        )
    }

    private suspend fun resolveGroupId(id: String, key: String, input: TelegramRules.Result, warnings: MutableList<String>, raw: MutableList<RawReport>): Long? {
        val rawId = input.numericId.filter { it.isDigit() || it == '-' }
        rawId.toLongOrNull()?.let { if (it != 0L) return kotlin.math.abs(it) }
        if (input.username.isNotBlank()) {
            // No direct group resolve endpoint in spec; try username usage for actual groups/channels.
            val (code, body) = get(id, key, "/users/username_usage", mapOf("input" to input.username)).also { if (it.first in 200..299) raw += RawReport("/users/username_usage", it.second) }
            if (code in 200..299) {
                val data = dataObj(body)
                val groups = data?.optJSONArray("actualGroupsOrChannels") ?: JSONArray()
                for (i in 0 until groups.length()) {
                    val g = groups.optJSONObject(i) ?: continue
                    val uname = g.optString("username").trim().removePrefix("@")
                    if (uname.equals(input.username, true)) return g.optLong("id").takeIf { it > 0 }
                }
                if (groups.length() == 1) return groups.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
            } else if (code == 401 || code == 403) throw ApiException(code, bodyMessage(code, body))
            warnings += "${label(id)}: для группы по username нужен числовой ID; username_usage не дал однозначного ID"
        }
        return null
    }

    private suspend fun userFacts(id: String, key: String, user: ResolvedUser, subject: String, warnings: MutableList<String>, raw: MutableList<RawReport>): List<RawFact> {
        val facts = ArrayList<RawFact>()
        val baseAttrs = linkedMapOf<String, String>()
        baseAttrs["ID"] = user.id.toString()
        if (user.username.isNotBlank()) baseAttrs["Ник"] = "@${user.username}"
        if (user.firstName.isNotBlank() || user.lastName.isNotBlank()) baseAttrs["Имя"] = listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ")
        if (user.premium) baseAttrs["Premium"] = "да"
        if (user.bot) baseAttrs["Бот"] = "да"
        baseAttrs["Активен"] = if (user.active) "да" else "нет"
        facts += raw(id, FactCategory.PERSON, "Telegram-профиль", displayUser(user), subject, baseAttrs,
            link = if (user.username.isNotBlank()) "https://t.me/${user.username}" else "", confirmed = true, note = "Telelog API resolve/basic_info")

        fetchObj(id, key, "/users/${user.id}/stats_min", emptyMap(), raw)?.let { o ->
            val a = linkedMapOf("ID" to user.id.toString())
            o.optString("first_msg_date").takeIf { it.isNotBlank() }?.let { a["Первое сообщение"] = it }
            o.optString("last_msg_date").takeIf { it.isNotBlank() }?.let { a["Последнее сообщение"] = it }
            o.optInt("total_msg_count", 0).takeIf { it > 0 }?.let { a["Сообщений"] = it.toString() }
            o.optInt("total_groups", 0).takeIf { it > 0 }?.let { a["Групп"] = it.toString() }
            o.optInt("adm_in_groups", 0).takeIf { it > 0 }?.let { a["Админ в группах"] = it.toString() }
            facts += raw(id, FactCategory.OTHER, "Telegram-активность", displayUser(user), subject, a, confirmed = true, note = "stats_min FREE")
        }
        fetchObj(id, key, "/users/${user.id}/stats", emptyMap(), raw)?.let { o ->
            val a = linkedMapOf("ID" to user.id.toString())
            o.optString("about").takeIf { it.isNotBlank() }?.let { a["О себе"] = it.take(300) }
            o.optString("lang_code").takeIf { it.isNotBlank() }?.let { a["Язык"] = it }
            o.optInt("stars_level", 0).takeIf { it > 0 }?.let { a["Stars level"] = it.toString() }
            o.optInt("gift_count", 0).takeIf { it > 0 }?.let { a["Подарков"] = it.toString() }
            val bd = listOf(o.optInt("birth_day",0), o.optInt("birth_month",0), o.optInt("birth_year",0)).filter { it > 0 }
            if (bd.isNotEmpty()) a["День рождения"] = bd.joinToString(".")
            facts += raw(id, FactCategory.PERSON, "Telegram-профиль (расширенный)", displayUser(user), subject, a, confirmed = true, note = "stats COST 1")
        }
        fetchArray(id, key, "/users/${user.id}/usernames", emptyMap(), raw)?.let { arr ->
            for (i in 0 until minOf(arr.length(), 20)) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim().removePrefix("@")
                if (name.isBlank()) continue
                facts += raw(id, FactCategory.PERSON, "История username", "@$name", subject,
                    linkedMapOf("ID" to user.id.toString(), "Дата" to o.optString("date_time")), confirmed = true, note = "usernames COST 3")
            }
        }
        fetchArray(id, key, "/users/${user.id}/names", emptyMap(), raw)?.let { arr ->
            for (i in 0 until minOf(arr.length(), 20)) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isBlank()) continue
                facts += raw(id, FactCategory.PERSON, "История имени", name, subject,
                    linkedMapOf("ID" to user.id.toString(), "Дата" to o.optString("date_time")), confirmed = true, note = "names COST 3")
            }
        }
        fetchArray(id, key, "/users/${user.id}/groups", emptyMap(), raw)?.let { arr ->
            for (i in 0 until minOf(arr.length(), 30)) {
                val o = arr.optJSONObject(i) ?: continue
                val chat = o.optJSONObject("chat") ?: continue
                val title = chat.optString("title").ifBlank { chat.optString("username") }
                if (title.isBlank()) continue
                val attrs = linkedMapOf<String, String>()
                chat.optLong("id").takeIf { it > 0 }?.let { attrs["Chat ID"] = it.toString() }
                chat.optString("username").takeIf { it.isNotBlank() }?.let { attrs["Ник"] = "@${it}" }
                o.optInt("messagesCount", 0).takeIf { it > 0 }?.let { attrs["Сообщений"] = it.toString() }
                if (o.optBoolean("isAdmin", false)) attrs["Админ"] = "да"
                facts += raw(id, FactCategory.OTHER, "Группа пользователя", title, subject, attrs,
                    link = chat.optString("username").takeIf { it.isNotBlank() }?.let { "https://t.me/$it" }.orEmpty(),
                    confirmed = true, note = "groups COST 5")
            }
        } ?: warnings.add("${label(id)}: группы пользователя недоступны/пусты")
        return facts
    }

    private suspend fun groupFacts(id: String, key: String, gid: Long, subject: String, warnings: MutableList<String>, raw: MutableList<RawReport>): List<RawFact> {
        val facts = ArrayList<RawFact>()
        fetchObj(id, key, "/groups/$gid", emptyMap(), raw)?.let { o ->
            // spec says 200 description only; try to parse common fields defensively
            val attrs = linkedMapOf("ID" to gid.toString())
            o.optString("title").takeIf { it.isNotBlank() }?.let { attrs["Название"] = it }
            o.optString("username").takeIf { it.isNotBlank() }?.let { attrs["Ник"] = "@${it}" }
            o.optInt("members_count", o.optInt("participants_count", 0)).takeIf { it > 0 }?.let { attrs["Участников"] = it.toString() }
            val title = attrs["Название"] ?: attrs["Ник"] ?: "Группа $gid"
            facts += raw(id, FactCategory.OTHER, "Telegram-чат", title, subject, attrs,
                link = attrs["Ник"]?.removePrefix("@")?.let { "https://t.me/$it" }.orEmpty(), confirmed = true, note = "groups/{id} COST 0.01")
        } ?: warnings.add("${label(id)}: детали группы недоступны")
        return facts
    }

    private suspend fun groupStatsFacts(id: String, key: String, gid: Long, subject: String, warnings: MutableList<String>, raw: MutableList<RawReport>): List<RawFact> {
        val facts = groupFacts(id, key, gid, subject, warnings, raw).toMutableList()
        // members endpoint is costly; include count-level hint only from group details. No separate stats endpoint.
        warnings += "${label(id)}: отдельный endpoint статистики чата не указан; использованы доступные данные groups/{id}"
        return facts
    }

    private suspend fun messageLinkFacts(id: String, key: String, input: TelegramRules.Result, warnings: MutableList<String>): List<RawFact> {
        val facts = ArrayList<RawFact>()
        val text = input.normalized
        facts += raw(id, FactCategory.OTHER, "Ссылка на сообщение", text, text, linkedMapOf("Ссылка" to text), confirmed = true, note = "Входной идентификатор")
        warnings += "${label(id)}: прямой endpoint получения сообщения по ссылке в публичной спецификации не найден; можно использовать text/search при наличии текста"
        return facts
    }

    private suspend fun forwardedFacts(id: String, key: String, input: TelegramRules.Result, warnings: MutableList<String>): List<RawFact> {
        val facts = ArrayList<RawFact>()
        facts += raw(id, FactCategory.OTHER, "Пересланный контент", input.normalized, input.normalized, linkedMapOf("Ввод" to input.normalized.take(200)),
            confirmed = false, note = "Требуется подтверждённый endpoint пересылок в API")
        return facts
    }

    private suspend fun stickerFacts(id: String, key: String, input: TelegramRules.Result, warnings: MutableList<String>, raw: MutableList<RawReport>): List<RawFact> {
        val facts = ArrayList<RawFact>()
        val uid = input.normalized.filter { it.isDigit() }.toLongOrNull()
        if (uid != null && input.normalized.trim().matches(Regex("\\d{5,15}"))) {
            fetchArray(id, key, "/users/$uid/stickers", emptyMap(), raw)?.let { arr ->
                for (i in 0 until minOf(arr.length(), 30)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title").ifBlank { o.optString("short_name") }
                    if (title.isBlank()) continue
                    facts += raw(id, FactCategory.OTHER, "Стикерпак пользователя", title, input.normalized, linkedMapOf(
                        "ID набора" to o.optString("sticker_set_id"),
                        "Короткое имя" to o.optString("short_name"),
                        "Стикеров" to o.optInt("stickers_count", 0).toString()
                    ), confirmed = true, note = "stickers COST 1 if found")
                }
            }
            if (facts.isEmpty()) warnings += "${label(id)}: стикерпаки не найдены для user ID"
        } else {
            warnings += "${label(id)}: поиск по файлу/ID стикера не поддерживается публичным API; укажите ID пользователя для stickers"
            facts += raw(id, FactCategory.OTHER, "Стикер (ввод)", input.normalized.take(120), input.normalized, emptyMap(), confirmed = false, note = "Нужен endpoint по sticker/file id")
        }
        return facts
    }

    private fun raw(id: String, cat: FactCategory, kind: String, value: String, subject: String, attrs: Map<String, String>, link: String = "", confirmed: Boolean, note: String): RawFact =
        RawFact(cat, kind, value, subject, attrs.filterValues { it.isNotBlank() }, id, 2, link, System.currentTimeMillis(), confirmed, note)

    private fun displayUser(u: ResolvedUser): String {
        val name = listOf(u.firstName, u.lastName).filter { it.isNotBlank() }.joinToString(" ")
        return when {
            name.isNotBlank() && u.username.isNotBlank() -> "$name (@${u.username})"
            u.username.isNotBlank() -> "@${u.username}"
            name.isNotBlank() -> name
            else -> "ID ${u.id}"
        }
    }

    private suspend fun fetchObj(id: String, key: String, path: String, params: Map<String, String> = emptyMap(), raw: MutableList<RawReport>? = null): JSONObject? {
        val (code, body) = get(id, key, path, params)
        if (code == 401 || code == 403) throw ApiException(code, bodyMessage(code, body))
        if (code !in 200..299) return null
        raw?.add(RawReport(path, body))
        return dataObj(body) ?: runCatching { JSONObject(body) }.getOrNull()
    }

    private suspend fun fetchArray(id: String, key: String, path: String, params: Map<String, String> = emptyMap(), raw: MutableList<RawReport>? = null): JSONArray? {
        val (code, body) = get(id, key, path, params)
        if (code == 401 || code == 403) throw ApiException(code, bodyMessage(code, body))
        if (code !in 200..299) return null
        raw?.add(RawReport(path, body))
        return dataArray(body)
    }

    private fun dataObj(body: String): JSONObject? = runCatching {
        val root = JSONObject(body)
        root.optJSONObject("data") ?: if (root.has("id") || root.has("title")) root else null
    }.getOrNull()

    private fun dataArray(body: String): JSONArray = runCatching {
        val root = JSONObject(body)
        root.optJSONArray("data") ?: JSONArray()
    }.getOrDefault(JSONArray())

    private suspend fun get(id: String, key: String, path: String, params: Map<String, String> = emptyMap()): Pair<Int, String> {
        val q = params.filterValues { it.isNotBlank() }.entries.flatMap { (k, v) ->
            if (v.contains(',')) v.split(',').map { it.trim() }.filter { it.isNotBlank() }.map { "${Net.urlEncode(k)}=${Net.urlEncode(it)}" }
            else listOf("${Net.urlEncode(k)}=${Net.urlEncode(v)}")
        }.joinToString("&")
        val url = base(id) + path + if (q.isNotBlank()) "?$q" else ""
        val resp = Net.fetch(url, headers(key), attempts = 1)
        return resp.code to resp.body
    }

    private fun bodyMessage(code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val detail = json?.optString("detail").orEmpty()
            .ifBlank { json?.optString("title").orEmpty() }
            .ifBlank { json?.optString("message").orEmpty() }
            .ifBlank { json?.optString("error").orEmpty() }
        val baseMsg = when (code) {
            401, 403 -> "Ключ отклонён/нет доступа"
            429 -> "Превышен лимит запросов"
            404 -> "Объект/endpoint не найден"
            else -> "Ошибка API: HTTP $code"
        }
        return if (detail.isNotBlank()) "$baseMsg ($detail)" else baseMsg
    }
}
