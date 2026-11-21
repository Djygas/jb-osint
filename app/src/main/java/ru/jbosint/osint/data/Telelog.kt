package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact

/**
 * Best-effort клиент Telelog/Funstat-подобных Telegram analytics API.
 *
 * Публичная спецификация у сервисов меняется, поэтому клиент пробует несколько
 * базовых URL и типовых endpoint'ов. Неподтверждённые возможности возвращают
 * понятную ошибку, а не выдуманный результат.
 */
object TelelogLike {

    class ApiException(val code: Int, message: String) : Exception(message)

    data class Account(val ok: Boolean, val detail: String)

    private fun bases(id: String): List<String> = when (id) {
        "funstat" -> listOf("https://funstat.info/api/v1", "https://api.funstat.info/v1", "https://funstat.info/api")
        else -> listOf("https://telelog.info/api/v1", "https://api.telelog.info/v1", "https://telelog.info/api")
    }

    suspend fun account(id: String, key: String): Account {
        val (code, body) = firstResponse(id, key, listOf("me", "profile", "user"), emptyMap())
        if (code in 200..299) return Account(true, "Ключ принят")
        throw ApiException(code, bodyMessage(code, body))
    }

    suspend fun query(id: String, key: String, input: TelegramRules.Result): List<RawFact> {
        val paths = pathsFor(input)
        var last: ApiException? = null
        for ((path, params) in paths) {
            val (code, body) = firstResponse(id, key, listOf(path), params)
            if (code in 200..299) return parseFacts(id, body, input)
            if (code == 401 || code == 403) throw ApiException(code, bodyMessage(code, body))
            last = ApiException(code, bodyMessage(code, body))
            if (code == 404 || code == 405) continue
        }
        if (last != null) throw last
        return emptyList()
    }

    private fun pathsFor(input: TelegramRules.Result): List<Pair<String, Map<String, String>>> {
        val q = input.normalized.ifBlank { input.username.ifBlank { input.numericId } }
        return when (input.kind) {
            TelegramRules.Kind.USERNAME, TelegramRules.Kind.USER_ID -> listOf(
                "user" to mapOf("q" to q, "username" to input.username, "id" to input.numericId),
                "users/search" to mapOf("q" to q),
                "search" to mapOf("q" to q, "type" to "user")
            )
            TelegramRules.Kind.GROUP, TelegramRules.Kind.GROUP_ID -> listOf(
                "chat" to mapOf("q" to q, "username" to input.username, "id" to input.numericId),
                "chats/search" to mapOf("q" to q),
                "search" to mapOf("q" to q, "type" to "chat")
            )
            TelegramRules.Kind.MESSAGE_LINK, TelegramRules.Kind.FORWARDED_GROUP_MESSAGE -> listOf(
                "message" to mapOf("link" to input.normalized, "q" to q),
                "messages/search" to mapOf("q" to input.normalized)
            )
            TelegramRules.Kind.GROUP_STATS -> listOf(
                "chat/stats" to mapOf("q" to q, "username" to input.username, "id" to input.numericId),
                "stats/chat" to mapOf("q" to q),
                "chat" to mapOf("q" to q, "stats" to "1")
            )
            TelegramRules.Kind.CHANNEL_FORWARD_POST -> listOf(
                "forwards" to mapOf("link" to input.normalized, "q" to q),
                "channel/forwards" to mapOf("link" to input.normalized),
                "post/forwards" to mapOf("link" to input.normalized)
            )
            TelegramRules.Kind.CONTACT_OR_RECENT -> listOf(
                "contacts" to mapOf("q" to input.normalized),
                "users/bulk" to mapOf("q" to input.normalized)
            )
            TelegramRules.Kind.STICKER -> listOf(
                "sticker" to mapOf("q" to input.normalized),
                "stickers/search" to mapOf("q" to input.normalized)
            )
            TelegramRules.Kind.INVALID -> emptyList()
        }
    }

    private suspend fun firstResponse(
        id: String, key: String, paths: List<String>, params: Map<String, String>
    ): Pair<Int, String> {
        var lastCode = -1
        var lastBody = ""
        for (base in bases(id)) {
            for (path in paths) {
                val query = params.filterValues { it.isNotBlank() }
                    .entries.joinToString("&") { (k, v) -> "${Net.urlEncode(k)}=${Net.urlEncode(v)}" }
                val url = "$base/${path.trimStart('/')}" + if (query.isNotBlank()) "?$query" else ""
                val resp = Net.fetch(url, mapOf("X-API-KEY" to key, "Accept" to "application/json"), attempts = 1)
                lastCode = resp.code; lastBody = resp.body
                if (resp.code in 200..299 || resp.code == 401 || resp.code == 403) return resp.code to resp.body
                if (resp.code != 404 && resp.code != 405) return resp.code to resp.body
            }
        }
        return lastCode to lastBody
    }

    private fun bodyMessage(code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val detail = json?.optString("detail").orEmpty().ifBlank { json?.optString("message").orEmpty() }
            .ifBlank { json?.optString("error").orEmpty() }
        val base = when (code) {
            401, 403 -> "Ключ отклонён сервисом"
            429 -> "Превышен лимит запросов"
            404 -> "Endpoint/объект не найден"
            else -> "Ошибка сервиса: HTTP $code"
        }
        return if (detail.isNotBlank()) "$base ($detail)" else base
    }

    private fun parseFacts(id: String, body: String, input: TelegramRules.Result): List<RawFact> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val facts = ArrayList<RawFact>()
        collectObjects(root).forEach { obj ->
            val title = obj.optString("title").ifBlank { obj.optString("name").ifBlank { obj.optString("full_name") } }
            val username = obj.optString("username").trimStart('@')
            val uid = obj.optString("id").ifBlank { obj.optString("user_id").ifBlank { obj.optString("chat_id") } }
            val link = obj.optString("link").ifBlank { if (username.isNotBlank()) "https://t.me/$username" else "" }
            val value = when {
                title.isNotBlank() && username.isNotBlank() -> "$title (@$username)"
                username.isNotBlank() -> "@$username"
                title.isNotBlank() -> title
                uid.isNotBlank() -> "ID $uid"
                else -> null
            } ?: return@forEach
            val attrs = linkedMapOf<String, String>()
            if (uid.isNotBlank()) attrs["ИД"] = uid
            if (username.isNotBlank()) attrs["Ник"] = "@$username"
            obj.optString("phone").takeIf { it.isNotBlank() }?.let { attrs["Телефон"] = it }
            obj.optInt("members_count", obj.optInt("participants_count", 0)).takeIf { it > 0 }?.let { attrs["Участников"] = it.toString() }
            obj.optInt("forwards_count", 0).takeIf { it > 0 }?.let { attrs["Пересылок"] = it.toString() }
            obj.optString("last_online").takeIf { it.isNotBlank() }?.let { attrs["Последняя активность"] = it }
            obj.optString("about").takeIf { it.isNotBlank() }?.let { attrs["Описание"] = it.take(200) }
            if (link.isNotBlank()) attrs["Ссылка"] = link
            val kind = when {
                input.kind == TelegramRules.Kind.GROUP_STATS -> "Статистика чата"
                input.kind == TelegramRules.Kind.CHANNEL_FORWARD_POST -> "Пересылка поста"
                obj.has("members_count") || obj.has("participants_count") -> "Telegram-чат"
                else -> "Telegram-профиль"
            }
            facts += RawFact(
                category = if (kind.contains("чат") || kind.contains("Статистика")) FactCategory.OTHER else FactCategory.PERSON,
                kind = kind,
                value = value,
                subject = input.normalized,
                attributes = attrs,
                sourceId = id,
                weight = 2,
                sourceUrl = link,
                observedAt = System.currentTimeMillis(),
                apiConfirmed = false,
                note = "Telelog/Funstat: endpoint подтверждён не полностью, требуется спецификация API"
            )
        }
        if (facts.isEmpty()) {
            facts += RawFact(FactCategory.OTHER, "Ответ $id", input.normalized, input.normalized,
                mapOf("Сводка" to DiagMask.text(body.take(300))), id, 1,
                apiConfirmed = false, note = "Нестандартный ответ API, нужна проверка спецификации")
        }
        return facts.distinctBy { it.kind to it.value }
    }

    private fun collectObjects(root: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    if (v.has("id") || v.has("username") || v.has("title") || v.has("name")) out += v
                    v.keys().forEach { walk(v.opt(it)) }
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(root)
        return out.take(50)
    }
}
