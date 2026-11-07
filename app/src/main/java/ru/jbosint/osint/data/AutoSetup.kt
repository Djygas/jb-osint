package ru.jbosint.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Ссылка на сервис, указанная пользователем: сайт или Telegram-бот/канал.
 * Из неё извлекается только домен — этого достаточно для построения гипотез.
 */
data class SourceLink(
    val kind: Kind,
    val host: String,
    val path: String = "",
    val telegram: String = ""
) {
    enum class Kind { SITE, TELEGRAM }

    val display: String get() = if (kind == Kind.TELEGRAM) "t.me/$telegram" else host

    companion object {

        fun parse(raw: String): SourceLink? {
            var text = raw.trim()
            if (text.isBlank()) return null
            if (text.startsWith("@")) {
                val user = userName(text.drop(1)) ?: return null
                return SourceLink(Kind.TELEGRAM, "t.me", telegram = user)
            }
            if (!text.contains("://")) text = "https://$text"
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            if (host.contains(' ') || !host.contains('.')) return null
            val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
            if (host == "t.me" || host == "telegram.me" || host == "telegram.dog") {
                val user = segments.firstOrNull { it != "s" }?.let { userName(it) } ?: return null
                return SourceLink(Kind.TELEGRAM, "t.me", telegram = user)
            }
            return SourceLink(Kind.SITE, host, "/" + segments.joinToString("/"))
        }

        fun userName(value: String): String? {
            val clean = value.trim().trimStart('@').trim('/').substringBefore('?')
            return clean.takeIf { it.matches(Regex("[A-Za-z0-9_]{3,64}")) }
        }
    }
}

/** Подсказки, извлечённые из документации: нестандартные пути и приоритет способов передачи ключа. */
data class DocHints(
    val templates: List<String> = emptyList(),
    val prefer: List<CustomAuth> = emptyList(),
    val note: String = ""
) {
    val isEmpty: Boolean get() = templates.isEmpty() && prefer.isEmpty()
}

/**
 * Автонастройка модуля по одному ключу и ссылке на сервис:
 * 1) читает страницу сервиса (или описание Telegram-бота) и ищет подсказки;
 * 2) генерирует стандартные эндпоинты поиска и способы передачи ключа;
 * 3) перебирает GET, затем POST, пока сервис не ответит 2xx (401/403 — адрес жив).
 */
object AutoSetup {

    const val TEST_QUERY = "osint"
    const val MAX_ATTEMPTS = 40

    /** Хосты, которые почти всегда отдают картинки и виджеты, а не API: ключ туда не отправляем. */
    private val noiseHosts = listOf(
        "telegram.org", "telegram.me", "t.me", "tgme.org", "cdn-telegram.org",
        "google.com", "googleapis.com", "gstatic.com", "youtube.com", "youtu.be",
        "facebook.com", "fbcdn.net", "instagram.com", "twitter.com", "x.com",
        "tiktok.com", "vk.com", "ok.ru", "discord.com", "discord.gg",
        "whatsapp.com", "bit.ly", "t.co", "github.com", "githubusercontent.com"
    )

    data class Variant(val method: String, val auth: CustomAuth, val headerName: String, val paramName: String)

    data class Outcome(
        val fixed: Boolean,
        val strict: Boolean,
        val setup: CustomConnectionProbe.Setup?,
        val urlTemplate: String?,
        val attempts: Int,
        val code: Int,
        val detail: String,
        val exhausted: Boolean = false
    )

    val standardPaths = listOf(
        "/search?q={query}",
        "/search?query={query}",
        "/api/search?q={query}",
        "/api/search?query={query}",
        "/api/v1/search?q={query}",
        "/api/v1/search?query={query}",
        "/search?text={query}"
    )

    fun getVariants(prefer: List<CustomAuth> = emptyList()): List<Variant> {
        val base = listOf(
            Variant("GET", CustomAuth.PARAM, "", "key"),
            Variant("GET", CustomAuth.PARAM, "", "token"),
            Variant("GET", CustomAuth.BEARER, "", ""),
            Variant("GET", CustomAuth.HEADER, "X-Api-Key", "")
        )
        return order(base, prefer)
    }

    fun postVariants(prefer: List<CustomAuth> = emptyList()): List<Variant> {
        val base = listOf(
            Variant("POST", CustomAuth.JSON_BODY, "", "key"),
            Variant("POST", CustomAuth.JSON_BODY, "", "token"),
            Variant("POST", CustomAuth.BEARER, "", ""),
            Variant("POST", CustomAuth.HEADER, "X-Api-Key", "")
        )
        return order(base, prefer)
    }

    private fun order(base: List<Variant>, prefer: List<CustomAuth>): List<Variant> {
        if (prefer.isEmpty()) return base
        return base.sortedBy { variant ->
            val index = prefer.indexOf(variant.auth).takeIf { it >= 0 }
                ?: if (variant.auth == CustomAuth.JSON_BODY) prefer.indexOf(CustomAuth.PARAM).takeIf { it >= 0 } else null
            index ?: prefer.size + base.indexOf(variant)
        }
    }

    fun templates(host: String, hints: DocHints = DocHints()): List<String> {
        val cleanHost = host.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        val standard = standardPaths.map { "https://$cleanHost$it" }
        return (hints.templates + standard).distinct().take(8)
    }

    /** Чтение страницы сервиса или публичной страницы Telegram-бота. Сеть может быть недоступна. */
    suspend fun fetchHints(link: SourceLink): DocHints = withContext(Dispatchers.IO) {
        if (link.kind == SourceLink.Kind.TELEGRAM) {
            val page = runCatching { Net.fetch("https://t.me/s/${link.telegram}", attempts = 1) }.getOrNull()
                ?: return@withContext DocHints()
            val html = page.body.takeIf { page.code in 200..299 }.orEmpty()
            if (html.isBlank()) return@withContext DocHints()
            return@withContext parseDocument(html, hintsFromTelegram(html))
        }
        val pages = buildList {
            add("https://${link.host}${link.path.ifBlank { "/" }}")
            add("https://${link.host}/api")
            add("https://${link.host}/docs")
        }.distinct().take(3)
        var best = DocHints()
        pages.forEach { page ->
            if (best.templates.size >= 2) return@forEach
            val response = runCatching { Net.fetch(page, attempts = 1) }.getOrNull() ?: return@forEach
            // Код ответа надёжнее поиска строки «404» в теле: цифры встречаются на живых страницах.
            if (response.code in 200..299 && response.body.isNotBlank()) {
                val parsed = parseDocument(response.body, DocHints())
                if (parsed.templates.size > best.templates.size) best = parsed
            }
        }
        best
    }

    private fun hintsFromTelegram(html: String): DocHints {
        val lower = html.lowercase()
        val prefer = ArrayList<CustomAuth>()
        if (lower.contains("x-api-key")) prefer += CustomAuth.HEADER
        if (lower.contains("bearer")) prefer += CustomAuth.BEARER
        val description = Regex("""<meta property="og:description" content="([^"]{0,300})"""")
            .find(html)?.groupValues?.getOrNull(1).orEmpty()
        val links = Regex("""https?://[^\s"'<>]+""").findAll(html)
            .map { it.value }
            .filterNot { it.contains("t.me") || it.contains("telegram.org") || it.contains("telegram.me") }
            .mapNotNull { runCatching { URI(it).host }.getOrNull() }
            .filter { it.contains('.') }
            .map { it.lowercase().removePrefix("www.") }
            .filterNot { host -> noiseHosts.any { host == it || host.endsWith(".$it") } }
            .distinct()
            // Ключ уходит на найденный хост, поэтому берём только один адрес и отбрасываем
            // CDN/соцсети: обычно это картинки и кнопки, а не API сервиса.
            .take(1)
            .toList()
        val hosts = links
        val extra = hosts.flatMap { host ->
            listOf("https://$host/search?q={query}", "https://$host/api/search?query={query}")
        }
        return DocHints(extra, prefer, description.takeIf { it.isNotBlank() }?.let { "из описания бота: ${it.take(120)}" }.orEmpty())
    }

    private fun parseDocument(html: String, base: DocHints): DocHints {
        if (html.isBlank()) return base
        val lower = html.lowercase()
        val prefer = base.prefer.toMutableList()
        if (lower.contains("x-api-key")) prefer += CustomAuth.HEADER
        if (lower.contains("bearer")) prefer += CustomAuth.BEARER
        if (lower.contains("token=") || lower.contains("api_key=") || lower.contains("apikey=")) prefer += CustomAuth.PARAM

        val found = Regex("""(?:https?://[^\s"'<>)]+)?/[a-z0-9_\-/.]{0,40}(?:search|lookup|find|query)[a-z0-9_\-/.]{0,40}""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.trim('"', '\'', '<', '>', ')', ',', '.') }
            .filter { it.startsWith("/") || it.startsWith("http") }
            .map { normaliseTemplate(it) }
            .filter { it != null }
            .mapNotNull { it }
            .distinct()
            .take(3)

        return DocHints(
            templates = (base.templates + found).distinct().take(3),
            prefer = prefer.distinct(),
            note = base.note
        )
    }

    /** Превращает найденный в документации адрес в шаблон с {query}. */
    private fun normaliseTemplate(raw: String): String? {
        var path = raw.trim()
        if (path.startsWith("http")) {
            val uri = runCatching { URI(path) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank()) return null
            path = uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "")
            if (path.isBlank() || path == "/") return null
        }
        if (!path.startsWith("/")) return null
        if (path.contains("{")) return null
        val hasQueryParam = Regex("""[?&](q|query|text|search)=""", RegexOption.IGNORE_CASE).containsMatchIn(path)
        return when {
            hasQueryParam -> Regex("""([?&](?:q|query|text|search)=)[^&]*""", RegexOption.IGNORE_CASE)
                .replace(path) { m -> m.groupValues[1] + "{query}" }
            path.contains('?') -> "$path&q={query}"
            else -> "$path?q={query}"
        }
    }

    /**
     * План перебора: пять адресов × восемь комбинаций (GET и POST) = ровно [MAX_ATTEMPTS].
     * Порядок «адрес → метод/ключ» важен: при прежнем «сначала все GET» POST-варианты
     * не попадали в лимит и POST-API не находились вообще.
     */
    fun plan(templates: List<String>, prefer: List<CustomAuth> = emptyList()): List<Pair<String, Variant>> {
        val combos = ArrayList<Pair<String, Variant>>()
        templates.distinct().take(5).forEach { template ->
            getVariants(prefer).forEach { combos += template to it }
            postVariants(prefer).forEach { combos += template to it }
        }
        return combos
    }

    suspend fun detect(
        link: SourceLink,
        key: String,
        hints: DocHints = DocHints(),
        skip: Int = 0
    ): Outcome = withContext(Dispatchers.IO) {
        val templates = if (link.kind == SourceLink.Kind.SITE) templates(link.host, hints) else hints.templates
        val combos = plan(templates, hints.prefer)
        val limited = combos.take(MAX_ATTEMPTS)
        if (limited.isEmpty() || skip >= limited.size) {
            return@withContext Outcome(
                fixed = false, strict = false, setup = null, urlTemplate = null,
                attempts = minOf(skip, limited.size), code = 0,
                detail = "Проверены все варианты подключения", exhausted = true
            )
        }
        var attemptNo = skip
        var rejected: Triple<String, Variant, Int>? = null
        var lastCode = 0
        var htmlPage = false
        var redirectHost: String? = null
        var rateLimited = false

        for ((template, variant) in limited.drop(skip)) {
            attemptNo++
            val module = CustomModule(
                id = "probe", title = "probe", urlTemplate = template,
                auth = variant.auth, method = variant.method,
                headerName = variant.headerName, paramName = variant.paramName, enabled = false
            )
            // 429 — «подождите», а не «неверный ключ»: пробуем тот же вариант ещё раз.
            var response = fetchVariant(module, key)
            if (response?.code == 429) {
                delay(700)
                response = fetchVariant(module, key)
                if (response?.code == 429) rateLimited = true
            }
            // Один мёртвый адрес (DNS, таймаут) не должен обрывать перебор остальных.
            if (response == null) continue
            // Ключ в query ушёл бы на чужой хост вместе с редиректом — дальше не идём.
            val redirect = response.crossHostRedirect
            if (redirect != null) {
                redirectHost = redirect
                break
            }
            lastCode = response.code
            if (response.code in 200..299) {
                // 200 с веб-страницей — типичный ответ SPA на любой путь, а не API поиска.
                if (looksLikeHtml(response.body)) {
                    htmlPage = true
                    continue
                }
                return@withContext Outcome(
                    fixed = true, strict = true,
                    setup = CustomConnectionProbe.Setup(
                        variant.method, variant.auth, variant.headerName, variant.paramName, response.code
                    ),
                    urlTemplate = template, attempts = attemptNo, code = response.code,
                    detail = "Модуль настроен: ${variant.method}, ${authTitle(variant)}"
                )
            }
            if ((response.code == 401 || response.code == 403) && rejected == null) {
                rejected = Triple(template, variant, response.code)
            }
        }

        rejected?.let { (template, variant, code) ->
            return@withContext Outcome(
                fixed = true, strict = false,
                setup = CustomConnectionProbe.Setup(variant.method, variant.auth, variant.headerName, variant.paramName, code),
                urlTemplate = template, attempts = attemptNo, code = code,
                detail = "Адрес отвечает (${CustomConnectionProbe().describe(code)}), ключ отклонён"
            )
        }
        val detail = when {
            redirectHost != null -> "Сервис перенаправляет на $redirectHost — укажите конечный адрес API вручную"
            rateLimited -> "Сервис ограничивает частоту запросов (429) — повторите через минуту"
            htmlPage && lastCode in 200..299 -> "Адрес отвечает веб-страницей, а не данными API"
            lastCode == 0 -> "Нет соединения"
            else -> "Сервис не принял ни один вариант (последний код $lastCode)"
        }
        Outcome(
            fixed = false, strict = false, setup = null, urlTemplate = null,
            attempts = attemptNo, code = lastCode, detail = detail
        )
    }

    /** Один пробный запрос варианта; null — сеть недоступна. */
    private suspend fun fetchVariant(module: CustomModule, key: String): Net.Response? {
        val url = CustomModuleSpec.buildUrl(module, key, TEST_QUERY)
        return runCatching {
            // Ключ в JSON-теле обязателен: без него POST-проверка всегда даёт 401/400.
            Net.fetch(
                url, CustomModuleSpec.headers(module, key), attempts = 1,
                method = module.method, body = CustomModuleSpec.body(module, key)
            )
        }.getOrNull()
    }

    /** Признак HTML-страницы: такие ответы не считаем успешной настройкой поиска. */
    private fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(200).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<?xml")
    }

    fun authTitle(variant: Variant): String = when (variant.auth) {
        CustomAuth.BEARER -> "Bearer-заголовок"
        CustomAuth.HEADER -> "заголовок ${variant.headerName}"
        CustomAuth.PARAM -> "параметр ?${variant.paramName}="
        CustomAuth.JSON_BODY -> "JSON-тело {\"${variant.paramName}\": …}"
        else -> variant.auth.title
    }
}
