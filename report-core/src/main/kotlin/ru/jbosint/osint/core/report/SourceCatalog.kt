package ru.jbosint.osint.core.report

import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.SourceRef

/**
 * Каталог модулей сбора: человекочитаемое название и адрес ресурса.
 * Нужен для раздела «Источники» и для строки «Источник: … (ссылка)» у каждого факта.
 */
object SourceCatalog {

    private val known: Map<String, SourceRef> = listOf(
        SourceRef("github", "GitHub API", "https://api.github.com"),
        SourceRef("gitlab", "GitLab API", "https://gitlab.com/api/v4"),
        SourceRef("hibp", "Have I Been Pwned", "https://haveibeenpwned.com"),
        SourceRef("virustotal", "VirusTotal", "https://www.virustotal.com"),
        SourceRef("shodan", "Shodan", "https://www.shodan.io"),
        SourceRef("numverify", "Numverify", "https://numverify.com"),
        SourceRef("hunter", "Hunter.io", "https://hunter.io"),
        SourceRef("ipinfo", "IPinfo", "https://ipinfo.io"),
        SourceRef("tgstat", "Telegram-аналитика (TGStat)", "https://tgstat.ru"),
        SourceRef("vk", "VK API", "https://vk.com"),
        SourceRef("dyxless", "Dyxless", "https://t.me"),
        SourceRef("telesint", "TeleSINT", "https://t.me"),
        SourceRef("opencorporates", "OpenCorporates", "https://opencorporates.com"),
        SourceRef("google", "Google Custom Search", "https://www.google.com"),
        SourceRef("yandex", "Яндекс XML", "https://yandex.ru"),
        SourceRef("brave", "Brave Search", "https://search.brave.com"),
        SourceRef("serpapi", "SerpAPI", "https://serpapi.com"),
        SourceRef("ddg", "DuckDuckGo", "https://duckduckgo.com"),
        SourceRef("wikipedia", "Wikipedia", "https://ru.wikipedia.org"),
        SourceRef("urlscan", "urlscan.io", "https://urlscan.io"),
        SourceRef("securitytrails", "SecurityTrails", "https://securitytrails.com"),
        SourceRef("abuseipdb", "AbuseIPDB", "https://www.abuseipdb.com"),
        SourceRef("rdap", "RDAP-реестр", "https://rdap.org"),
        SourceRef("demo", "Демо-модуль", "https://jbosint.ru"),
        SourceRef("face", "Поиск по фото", "https://jbosint.ru"),
        SourceRef("photo", "Обратный поиск изображения (SauceNAO)", "https://saucenao.com"),
        SourceRef("faceapi", "Поиск по лицу (ProFaceFinder)", "https://profacefinder.com"),
        SourceRef("leakcheck", "LeakCheck", "https://leakcheck.io"),
        SourceRef("custom", "Свой модуль", "https://jbosint.ru")
    ).associateBy { it.id }

    fun ref(id: String): SourceRef = known[id.lowercase()]
        ?: SourceRef(id, if (id.isBlank()) "Внутренний модуль" else "Модуль $id", "")

    /** Полный список источников, реально давших наблюдения, в порядке первого появления. */
    fun forFacts(facts: List<Fact>): List<SourceRef> =
        facts.flatMap { it.sourceIds }.distinct().map { ref(it) }
}
