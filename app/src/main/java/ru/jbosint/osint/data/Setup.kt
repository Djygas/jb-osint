package ru.jbosint.osint.data

/**
 * Подключение модуля «по одному ключу»: пользователь вставляет строку из
 * кабинета сервиса — ключ, ключ вместе с адресом или вместе с названием, —
 * а приложение само решает, встроенный это модуль или нужен свой.
 *
 * Все функции чистые (без сети и Android): их покрывают unit-тесты, а
 * сетевую часть выполняет AppViewModel.
 */
data class KeyIntake(
    /** Найденный ключ (самый длинный токеноподобный фрагмент). */
    val key: String,
    /** Адрес сервиса, если он был в строке. */
    val url: String?,
    /** Всё, что осталось: название сервиса, подпись «ключ проекта» и т. п. */
    val hint: String
) {
    val host: String? get() = url?.let { SourceLink.parse(it)?.host }
    val hasKey: Boolean get() = key.length >= 8

    companion object {

    private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** Токены: буквы, цифры и типовые разделители `_ - . = :`. Не берём слова короче 8. */
    private val tokenRegex = Regex("""[A-Za-z0-9][A-Za-z0-9_\-\.=:/+]{7,}""")

    private val labels = setOf(
        "ключ", "токен", "key", "token", "api", "api_key", "apikey", "secret",
        "bearer", "access", "access_token", "проект", "проекта", "сервис", "для", "и"
    )

    /**
     * Разбирает вставленную строку. Возвращает null, если ключа не видно.
     *
     * Примеры, которые должны работать:
     *  - `ghp_1a2b…`
     *  - `VK API: vk1.a.AbC…`
     *  - `dyxless задача https://api.dyxless.im ключ abc123…`
     *  - `https://telelog.info  eyJhbGciOi…`
     */
    fun parse(raw: String): KeyIntake? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val url = urlRegex.find(text)?.value?.trimEnd('.', ',', ';', ')', ']')
        val withoutUrl = if (url != null) text.replace(url, " ") else text

        val candidates = tokenRegex.findAll(withoutUrl)
            .map { it.value.trim('.', ',', ';', ':', '=') }
            .filter { it.length >= 8 }
            .filter { it.lowercase() !in labels }
            .filter { !it.startsWith("http", ignoreCase = true) }
            .toList()

        // Ключ — самый длинный фрагмент: подписи и названия сервисов короче.
        val key = candidates.maxByOrNull { it.length } ?: return null
        val hint = withoutUrl.replace(key, " ").replace(Regex("""\s+"""), " ").trim()
        return KeyIntake(key = key, url = url, hint = hint)
        }
    }
}

/** Куда подключить вставленный ключ. */
sealed class SetupRoute {
    /** Встроенный модуль с этим id. */
    data class BuiltIn(val id: String, val how: String) : SetupRoute()

    /** Ассистент (модель ИИ), а не поисковый модуль. */
    data class Assistant(val providerId: String, val how: String) : SetupRoute()

    /** Свой модуль: адрес известен, API подберётся автоматически. */
    data class Custom(val url: String, val title: String, val how: String) : SetupRoute()

    /** Понять не удалось — нужен ручной выбор. */
    data class Unknown(val how: String) : SetupRoute()
}

/**
 * Определение сервиса по названию, адресу и префиксу ключа.
 * Совпадение по префиксу ключа считается надёжнее, чем по слову в тексте.
 */
object ProviderGuess {

    /** Названия и адреса сервисов → id встроенного модуля. */
    private val byHost: Map<String, String> = mapOf(
        "github.com" to "github", "api.github.com" to "github",
        "gitlab.com" to "gitlab",
        "haveibeenpwned.com" to "hibp", "api.pwnedpasswords.com" to "hibp",
        "virustotal.com" to "virustotal",
        "shodan.io" to "shodan",
        "numverify.com" to "numverify", "apilayer.com" to "numverify",
        "hunter.io" to "hunter",
        "ipinfo.io" to "ipinfo",
        "tgstat.ru" to "tgstat", "tgstat.com" to "tgstat",
        "vk.com" to "vk", "api.vk.com" to "vk", "dev.vk.com" to "vk",
        "dyxless.im" to "dyxless",
        "opencorporates.com" to "opencorporates",
        "yandex.ru" to "yandex", "yandex.com" to "yandex",
        "brave.com" to "brave", "api.search.brave.com" to "brave",
        "serpapi.com" to "serpapi",
        "securitytrails.com" to "securitytrails",
        "abuseipdb.com" to "abuseipdb",
        "saucenao.com" to "saucenao",
        "googleapis.com" to "google"
    )

    /** Слова в подписи/названии сервиса → id встроенного модуля. */
    private val byWord: Map<String, String> = mapOf(
        "github" to "github", "гитхаб" to "github", "гитхабе" to "github",
        "gitlab" to "gitlab", "гитлаб" to "gitlab",
        "hibp" to "hibp", "haveibeenpwned" to "hibp", "утечк" to "hibp",
        "virustotal" to "virustotal", "вирустотал" to "virustotal",
        "shodan" to "shodan", "шодан" to "shodan",
        "numverify" to "numverify", "apilayer" to "numverify",
        "hunter" to "hunter", "хантер" to "hunter",
        "ipinfo" to "ipinfo",
        "tgstat" to "tgstat",
        "vk" to "vk", "вконтакте" to "vk",
        "dyxless" to "dyxless", "дикслес" to "dyxless",
        "opencorporates" to "opencorporates",
        "yandex" to "yandex", "яндекс" to "yandex",
        "brave" to "brave",
        "serpapi" to "serpapi",
        "securitytrails" to "securitytrails",
        "abuseipdb" to "abuseipdb",
        "saucenao" to "saucenao",
        "google" to "google", "гугл" to "google"
    )

    private val byPrefix: List<Pair<(String) -> Boolean, String>> = listOf(
        { k: String -> k.startsWith("ghp_") || k.startsWith("gho_") || k.startsWith("ghs_") || k.startsWith("github_pat_") } to "github",
        { k: String -> k.startsWith("glpat-") } to "gitlab",
        { k: String -> k.startsWith("AIza") } to "google"
    )

    fun hostOf(url: String?): String? = url?.let { SourceLink.parse(it)?.host }

    /** Совпадение по адресу: поддомены тоже считаются (`api.github.com` → `github.com`). */
    fun byHost(url: String?): String? {
        val host = hostOf(url) ?: return null
        return byHost.entries.firstOrNull { (known, _) ->
            host == known || host.endsWith("." + known)
        }?.value
    }

    fun byText(text: String?): String? {
        val value = text.orEmpty().lowercase()
        if (value.isBlank()) return null
        return byWord.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (word, _) -> value.contains(word) }?.value
    }

    fun byKeyPrefix(key: String): String? =
        byPrefix.firstOrNull { (test, _) -> test(key) }?.second

    /** Поставщик ИИ по ключу — чтобы `hf_…` и `sk-or-v1-…` не попадали в поисковые модули. */
    fun assistantByKey(key: String): String? = when {
        key.startsWith("hf_") -> "hf"
        key.startsWith("sk-or-v1-") -> "openrouter"
        key.startsWith("gsk_") -> "groq"
        else -> null
    }

    /**
     * Итоговый маршрут. Порядок: префикс ключа → адрес → название сервиса.
     * Если ничего не совпало, но есть адрес — это свой модуль.
     */
    fun route(intake: KeyIntake): SetupRoute {
        assistantByKey(intake.key)?.let { id ->
            return SetupRoute.Assistant(
                id,
                "ключ ${AssistantCatalog.provider(id).title} — это ассистент, а не поисковый модуль"
            )
        }
        byKeyPrefix(intake.key)?.let { id ->
            return SetupRoute.BuiltIn(id, "формат ключа принадлежит «${ProviderCatalog.byId(id)?.title ?: id}»")
        }
        byHost(intake.url)?.let { id ->
            return SetupRoute.BuiltIn(id, "адрес ${hostOf(intake.url)} принадлежит «${ProviderCatalog.byId(id)?.title ?: id}»")
        }
        byText(intake.hint)?.let { id ->
            return SetupRoute.BuiltIn(id, "в строке назван сервис «${ProviderCatalog.byId(id)?.title ?: id}»")
        }
        val url = intake.url
        if (url != null) {
            val host = hostOf(url) ?: return SetupRoute.Unknown("не удалось разобрать адрес")
            return SetupRoute.Custom(url, titleFromHost(host), "сервис $host не встроен — создам свой модуль")
        }
        return SetupRoute.Unknown("не удалось понять, какому сервису принадлежит ключ")
    }

    /** Название модуля из адреса: `api.dyxless.im` → `Dyxless`. */
    fun titleFromHost(host: String): String {
        val parts = host.lowercase().removePrefix("www.").removePrefix("api.").split('.')
        val base = parts.firstOrNull { it.length >= 3 } ?: parts.firstOrNull().orEmpty()
        if (base.isBlank()) return "Свой модуль"
        return base.replaceFirstChar { it.uppercase() }
    }
}

/** Состояние подключения по ключу — то, что видит пользователь. */
data class ConnectionUi(
    val running: Boolean = false,
    val lines: List<String> = emptyList(),
    val done: Boolean = false,
    val ok: Boolean = false,
    val title: String = "",
    val detail: String = ""
) {
    fun step(text: String): ConnectionUi = copy(lines = lines + text)
}
