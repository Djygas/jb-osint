package ru.jbosint.osint.data

/**
 * Правила Telegram-идентификаторов.
 *
 * Username: латиница, цифры и подчёркивание, длина 5–32, начинается с буквы.
 * Числовой ID принимается отдельно — он не подчиняется этим правилам.
 * Проверка нужна до запроса: иначе модули тратят платные вызовы на заведомо
 * неверный ввод и возвращают пусто, что выглядит как «поиск не работает».
 */
object TelegramRules {

    /** Допустимый username: первая буква, дальше латиница/цифры/подчёркивание, 5–32 символа. */
    private val usernameRegex = Regex("^[A-Za-z][A-Za-z0-9_]{4,31}$")

    data class Result(
        val valid: Boolean,
        val kind: Kind,
        val normalized: String,
        val error: String = ""
    )

    enum class Kind { USERNAME, ID, CHANNEL_LINK, INVALID }

    fun username(raw: String): String? {
        val clean = raw.trim().removePrefix("@")
        return clean.takeIf { usernameRegex.matches(it) }
    }

    fun isId(raw: String): Boolean {
        val clean = raw.trim()
        return clean.length in 5..15 && clean.all { it.isDigit() }
    }

    /** Разбор ввода: @ник, ник, числовой ID или ссылка t.me. */
    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isBlank()) return Result(false, Kind.INVALID, "", "Пустой запрос")
        if (isId(text)) return Result(true, Kind.ID, text)

        val fromLink = when {
            text.contains("t.me/") -> text.substringAfter("t.me/").substringBefore('?').substringBefore('/').removePrefix("@")
            text.contains("telegram.me/") -> text.substringAfter("telegram.me/").substringBefore('?').substringBefore('/').removePrefix("@")
            text.startsWith("https://") || text.startsWith("http://") -> ""
            else -> text.removePrefix("@")
        }
        if (fromLink.isBlank() && (text.startsWith("http"))) {
            return Result(false, Kind.INVALID, "", "Ссылка не похожа на профиль Telegram")
        }
        username(fromLink)?.let {
            return Result(true, if (text.startsWith("http")) Kind.CHANNEL_LINK else Kind.USERNAME, it)
        }
        val reason = when {
            fromLink.length < 5 -> "Username короче 5 символов"
            fromLink.length > 32 -> "Username длиннее 32 символов"
            !fromLink.first().isLetter() -> "Username должен начинаться с буквы"
            fromLink.any { !it.isLetterOrDigit() && it != '_' } ->
                "Недопустимые символы: разрешены латиница, цифры и подчёркивание"
            !fromLink.all { it.code < 128 } -> "Username может содержать только латиницу"
            else -> "Неверный формат username"
        }
        return Result(false, Kind.INVALID, fromLink, reason)
    }

    /** Валидация для экранов: возвращает текст ошибки или null, если всё верно. */
    fun error(raw: String): String? {
        val result = parse(raw)
        return if (result.valid) null else result.error
    }
}
