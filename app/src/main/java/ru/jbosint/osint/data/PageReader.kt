package ru.jbosint.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact

/**
 * Чтение страниц, найденных поиском.
 *
 * Модули отдают ссылки и сниппеты, но не открывают сами страницы. Здесь
 * страница скачивается (HTTPS, лимит размера), из HTML вынимаются заголовок,
 * описание, текст, контакты и ссылки на профили — это и делает поиск
 * развёрнутым: из «просто ссылки» получается набор проверяемых сведений.
 */
data class PageContent(
    val url: String,
    val title: String = "",
    val description: String = "",
    val siteName: String = "",
    val text: String = "",
    /** Абсолютные ссылки со страницы: нужны для поиска профилей и следующего шага. */
    val links: List<String> = emptyList(),
    val ok: Boolean = false,
    val error: String = "",
    /** "local" — страницу прочитал телефон, "server" — серверный ускоритель. */
    val via: String = "local"
) {
    val host: String get() = runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
}

object PageReader {

    private const val MAX_BYTES = 400_000
    private const val MAX_TEXT = 6_000

    /**
     * Серверное чтение страницы. Настраивается один раз из AppViewModel:
     * null — сервер выключен или не задан, тогда всё работает только локально.
     */
    @Volatile
    var serverFallback: (suspend (String) -> PageContent?)? = null

    /** Какие хосты не читаем: соцсети отдают HTML без контента, файлы бесполезны. */
    private val skipHosts = listOf(
        "vk.com", "ok.ru", "instagram.com", "facebook.com", "tiktok.com",
        "youtube.com", "youtu.be", "x.com", "twitter.com", "t.me", "telegram.me",
        "avito.ru", "drom.ru", "auto.ru", "ozon.ru", "wildberries.ru"
    )
    private val skipExtensions = listOf(
        ".pdf", ".zip", ".rar", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".mp4", ".mp3", ".doc", ".docx", ".xls", ".xlsx"
    )

    fun readable(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val clean = url.lowercase()
        if (skipExtensions.any { clean.substringBefore('?').endsWith(it) }) return false
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
        return skipHosts.none { host == it || host.endsWith(".$it") }
    }

    suspend fun read(url: String): PageContent = withContext(Dispatchers.IO) {
        if (!readable(url)) return@withContext PageContent(url, error = "страница не читается автоматически")
        val local = runCatching { Net.fetch(url, attempts = 1) }.fold(
            onSuccess = { response ->
                if (response.code !in 200..299) {
                    PageContent(url, error = "HTTP ${response.code}")
                } else {
                    val body = if (response.body.length > MAX_BYTES) response.body.take(MAX_BYTES) else response.body
                    parse(body, url)
                }
            },
            onFailure = { PageContent(url, error = it.message ?: "сеть недоступна") }
        )
        if (local.ok) return@withContext local
        // Локально не вышло (403/429, блокировка по IP, таймаут) — пробуем сервер.
        val fallback = serverFallback ?: return@withContext local
        val remote = runCatching { fallback(url) }.getOrNull() ?: return@withContext local
        if (remote.ok) remote else local.copy(error = local.error.ifBlank { remote.error })
    }

    /** Разбор HTML. Вынесен отдельно от сети — покрывается тестом на строке. */
    fun parse(html: String, url: String): PageContent {
        if (html.isBlank()) return PageContent(url, error = "пустой ответ")
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let(::cleanText).orEmpty()
        fun meta(name: String): String {
            // Порядок атрибутов в <meta> произвольный: content может стоять и до name.
            Regex("<meta[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val tag = match.value
                val key = Regex("(?:name|property)=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1).orEmpty()
                if (!key.equals(name, ignoreCase = true)) return@forEach
                val value = Regex("content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1).orEmpty()
                if (value.isNotBlank()) return cleanText(value)
            }
            return ""
        }
        val description = meta("description").ifBlank { meta("og:description") }
        val siteName = meta("og:site_name")
        val withoutScripts = html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        val text = cleanText(withoutScripts.replace(Regex("<[^>]+>"), " ")).take(MAX_TEXT)
        // Ссылки берём из атрибутов href: после вырезания тегов они теряются.
        val links = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].trim() }
            .mapNotNull { href ->
                when {
                    href.startsWith("https://") -> href
                    href.startsWith("//") -> "https:$href"
                    else -> null
                }
            }
            .distinct()
            .take(60)
            .toList()
        return PageContent(
            url = url,
            title = title,
            description = description,
            siteName = siteName,
            text = text,
            links = links,
            ok = text.length > 40 || title.isNotBlank()
        )
    }

    /** Сведения со страницы: упоминание, контакты, профили. */
    fun facts(page: PageContent): List<RawFact> {
        if (!page.ok) return emptyList()
        val subject = page.title.ifBlank { page.host }
        val out = ArrayList<RawFact>()
        out += RawFact(
            category = FactCategory.OTHER,
            kind = "Упоминание на странице",
            value = page.url,
            subject = subject,
            attributes = buildMap {
                if (page.title.isNotBlank()) put("Заголовок", page.title.take(160))
                if (page.description.isNotBlank()) put("Описание", page.description.take(240))
                if (page.host.isNotBlank()) put("Сайт", page.host)
                if (page.via == Remote.SERVER_VIA) put("Получено", "серверный режим")
            },
            sourceId = "page",
            weight = 1,
            sourceUrl = page.url
        )
        emails(page.text + " " + page.description).forEach { email ->
            out += RawFact(FactCategory.CONTACT, "Почта", email, subject, mapOf("Источник" to page.host), "page", 2, page.url)
        }
        phones(page.text + " " + page.description).forEach { phone ->
            out += RawFact(FactCategory.CONTACT, "Телефон", phone, subject, mapOf("Источник" to page.host), "page", 2, page.url)
        }
        PhotoAnalyzer.handlesFrom(page.links).forEach { handle ->
            out += RawFact(FactCategory.PERSON, "Никнейм", "@$handle", subject, mapOf("Источник" to page.host), "page", 1, page.url)
        }
        if (page.siteName.isNotBlank()) {
            out += RawFact(FactCategory.ORGANIZATION, "Организация", page.siteName, subject, emptyMap(), "page", 1, page.url)
        }
        return out.distinctBy { it.kind to it.value }
    }

    /** Ссылки для следующего шага: сначала «человеческие» страницы, потом остальное. */
    fun pickUrls(urls: List<String>, limit: Int = 3): List<String> = urls
        .asSequence()
        .filter { readable(it) }
        .distinct()
        .sortedByDescending { url ->
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            when {
                host.endsWith("github.com") || host.endsWith("gitlab.com") -> 3
                host.contains("ru") -> 2
                else -> 1
            }
        }
        .take(limit)
        .toList()

    fun emails(text: String): List<String> = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
        .findAll(text)
        .mapNotNull { EntityResolver.email(it.value) }
        .filterNot { it.endsWith(".png") || it.endsWith(".jpg") || it.contains("example.") }
        .distinct()
        .take(5)
        .toList()

    fun phones(text: String): List<String> = Regex(
        """(?:\+7|8)[\s\-(]*\d{3}[\s\-)]*\d{3}[\s\-]*\d{2}[\s\-]*\d{2}|\+\d{10,14}"""
    )
        .findAll(text)
        .mapNotNull { EntityResolver.phone(it.value) }
        .distinct()
        .take(5)
        .toList()

    private fun cleanText(raw: String): String {
        val decoded = raw
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
        return decoded.replace(Regex("\\s+"), " ").trim()
    }
}
