package ru.jbosint.osint.data

/**
 * Правила и классификация входов Telegram-парсера.
 *
 * Поддерживаемые сценарии (TeleSINT / Telelog / Funstat, где доступно API):
 * - @username или числовой ID аккаунта
 * - контакт/недавние аккаунты (текстовый идентификатор/список)
 * - стикер (файл/ссылка/ID стикера)
 * - @username/ID группы или канала
 * - ссылка на сообщение в группе
 * - пересланное сообщение из группы
 * - ссылка/ID группы - статистика чата
 * - пересланный пост с канала - кто пересылал в чаты
 */
object TelegramRules {

    private val usernameRegex = Regex("^[A-Za-z][A-Za-z0-9_]{4,31}$")
    private val tgLink = Regex("""(?:https?://)?(?:t\.me|telegram\.me)/(.+)""", RegexOption.IGNORE_CASE)

    enum class Kind {
        USERNAME,
        USER_ID,
        GROUP,
        GROUP_ID,
        MESSAGE_LINK,
        FORWARDED_GROUP_MESSAGE,
        GROUP_STATS,
        CHANNEL_FORWARD_POST,
        CONTACT_OR_RECENT,
        STICKER,
        INVALID
    }

    data class Result(
        val valid: Boolean,
        val kind: Kind,
        val normalized: String,
        val username: String = "",
        val numericId: String = "",
        val messageId: Int? = null,
        val error: String = ""
    )

    fun username(raw: String): String? {
        val clean = raw.trim().removePrefix("@")
        return clean.takeIf { usernameRegex.matches(it) }
    }

    fun isId(raw: String): Boolean {
        val clean = raw.trim().removePrefix("-100").removePrefix("-")
        return clean.length in 5..15 && clean.all { it.isDigit() }
    }

    fun parse(raw: String, modeHint: Kind? = null): Result {
        val text = raw.trim()
        if (text.isBlank()) return Result(false, Kind.INVALID, "", error = "Пустой запрос")
        modeHint?.let { hint ->
            if (hint == Kind.CONTACT_OR_RECENT) return Result(true, hint, text)
            if (hint == Kind.STICKER) return Result(true, hint, text)
            if (hint == Kind.FORWARDED_GROUP_MESSAGE) return parseForwarded(text, group = true)
            if (hint == Kind.CHANNEL_FORWARD_POST) return parseForwarded(text, group = false)
            if (hint == Kind.GROUP_STATS) return parseGroup(text, stats = true) ?: Result(false, Kind.INVALID, text, error = "Укажите группу/канал или ссылку")
        }

        parseMessageLink(text)?.let { return it }
        parseGroup(text, stats = false)?.let { return it }

        if (isId(text)) {
            val kind = if (text.startsWith("-100") || text.startsWith("-")) Kind.GROUP_ID else Kind.USER_ID
            return Result(true, kind, text, numericId = text.filter { it.isDigit() || it == '-' })
        }
        val fromLink = extractLinkTarget(text)
        if (fromLink != null) {
            username(fromLink)?.let { u ->
                return Result(true, Kind.USERNAME, u, username = u)
            }
            if (isId(fromLink)) return Result(true, Kind.USER_ID, fromLink, numericId = fromLink)
        }
        val clean = text.removePrefix("@")
        username(clean)?.let { u -> return Result(true, Kind.USERNAME, u, username = u) }

        // Контакты/недавние/стикеры часто приходят как JSON/список/ссылка на файл.
        if (text.contains("contact", true) || text.contains("recent", true) || text.contains("phone", true) || text.contains("\n")) {
            return Result(true, Kind.CONTACT_OR_RECENT, text)
        }
        if (text.contains("sticker", true) || text.contains(".tgs") || text.contains(".webp") || text.contains("tgsticker")) {
            return Result(true, Kind.STICKER, text)
        }

        val reason = when {
            clean.length < 5 -> "Username короче 5 символов"
            clean.length > 32 -> "Username длиннее 32 символов"
            clean.firstOrNull()?.isLetter() != true -> "Username должен начинаться с буквы"
            clean.any { !it.isLetterOrDigit() && it != '_' } -> "Недопустимые символы: разрешены латиница, цифры и подчёркивание"
            !clean.all { it.code < 128 } -> "Username может содержать только латиницу"
            else -> "Неверный формат ввода"
        }
        return Result(false, Kind.INVALID, clean, error = reason)
    }

    private fun parseGroup(text: String, stats: Boolean): Result? {
        val target = extractLinkTarget(text) ?: text.trim().removePrefix("@")
        if (target.isBlank()) return null
        if (target.startsWith("c/")) {
            val parts = target.removePrefix("c/").split('/')
            val id = parts.firstOrNull().orEmpty()
            if (isId(id)) return Result(true, if (stats) Kind.GROUP_STATS else Kind.GROUP_ID, text, numericId = id)
        }
        if (isId(target)) return Result(true, if (stats) Kind.GROUP_STATS else Kind.GROUP_ID, target, numericId = target.filter { it.isDigit() || it == '-' })
        username(target)?.let { u ->
            return Result(true, if (stats) Kind.GROUP_STATS else Kind.GROUP, u, username = u)
        }
        return null
    }

    private fun parseMessageLink(text: String): Result? {
        val m = tgLink.find(text) ?: return null
        val tail = m.groupValues[1].trim('/').substringBefore('?')
        val parts = tail.split('/').filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val msgId = parts.last().toIntOrNull()
            val target = parts[parts.size - 2]
            if (msgId != null) {
                val user = username(target)
                val id = if (isId(target)) target else ""
                if (user != null || id.isNotBlank() || target.startsWith("c")) {
                    return Result(true, Kind.MESSAGE_LINK, text, username = user.orEmpty(), numericId = id.removePrefix("c/"), messageId = msgId)
                }
            }
        }
        return null
    }

    private fun parseForwarded(text: String, group: Boolean): Result {
        // Пересланное сообщение часто приходит как JSON/текст; сохраняем как есть.
        val link = parseMessageLink(text)
        if (link != null) return link.copy(kind = if (group) Kind.FORWARDED_GROUP_MESSAGE else Kind.CHANNEL_FORWARD_POST)
        return Result(true, if (group) Kind.FORWARDED_GROUP_MESSAGE else Kind.CHANNEL_FORWARD_POST, text)
    }

    private fun extractLinkTarget(text: String): String? {
        val m = tgLink.find(text) ?: return null
        val tail = m.groupValues[1].trim('/').substringBefore('?')
        val first = tail.split('/').firstOrNull().orEmpty()
        return first.takeIf { it.isNotBlank() }
    }

    fun error(raw: String): String? {
        val result = parse(raw)
        return if (result.valid) null else result.error
    }
}
