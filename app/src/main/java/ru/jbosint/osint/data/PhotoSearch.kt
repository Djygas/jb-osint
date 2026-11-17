package ru.jbosint.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact

/**
 * Поиск по фотографии: агрегатор обратного поиска изображения.
 *
 * Работает по двум контурам:
 *  1. Автоматически — SauceNAO (бесплатный ключ) и любой сервис поиска по лицу,
 *     адрес которого задан в ключах; результаты разбираются в совпадения.
 *  2. Вручную — готовые ссылки на Яндекс.Картинки, Bing, TinEye, Google Lens,
 *     IQDB и поиск по аватару в Telegram: пользователь открывает их сам, и это
 *     честно помечено в отчёте как ручная проверка, а не найденное совпадение.
 *
 * Совпадения дедуплицируются по нормализованному адресу, получают платформу,
 * статус достоверности и никнейм (если он есть в адресе). Никнеймы затем можно
 * передать в модули поиска по людям — это делает агент.
 */

/** Одно найденное совпадение по фото. */
data class PhotoMatch(
    val url: String,
    val platform: String,
    val title: String,
    val similarity: Int,
    val status: Confidence,
    val provider: String,
    val handle: String = "",
    val date: String = "",
    val likes: Int = 0,
    val manual: Boolean = false
) {
    val id: String get() = url.lowercase()
}

data class PhotoSearchResult(
    val photo: PhotoAnalysis,
    val matches: List<PhotoMatch>,
    val platforms: List<String>,
    val handles: List<String>,
    val manualLinks: List<Pair<String, String>>,
    val providersUsed: List<String>,
    val freeOnly: Boolean,
    val durationMs: Long,
    val note: String = ""
) {
    val found: List<PhotoMatch> get() = matches.filterNot { it.manual }
    val hasResults: Boolean get() = found.isNotEmpty()
}

object PhotoSearch {

    /** Минимальная схожесть, при которой совпадение вообще показываем. */
    const val MIN_SIMILARITY = 50

    private val platformHosts = linkedMapOf(
        "vk.com" to "VK", "vk.ru" to "VK", "ok.ru" to "Одноклассники",
        "instagram.com" to "Instagram", "facebook.com" to "Facebook",
        "twitter.com" to "X", "x.com" to "X", "tiktok.com" to "TikTok",
        "youtube.com" to "YouTube", "youtu.be" to "YouTube", "t.me" to "Telegram",
        "telegram.me" to "Telegram", "discord.com" to "Discord", "discord.gg" to "Discord",
        "reddit.com" to "Reddit", "2ch.hk" to "2ch", "2ch.ru" to "2ch",
        "avito.ru" to "Авито", "mamba.ru" to "Mamba", "badoo.com" to "Badoo",
        "tinder.com" to "Tinder", "pinterest.com" to "Pinterest", "viber.com" to "Viber",
        "github.com" to "GitHub", "drom.ru" to "Дром", "auto.ru" to "Авто.ру"
    )

    fun platformOf(url: String): String {
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val clean = host.removePrefix("www.").removePrefix("m.")
        return platformHosts.entries.firstOrNull { clean.endsWith(it.key) }?.value ?: "Сайт"
    }

    fun statusFor(similarity: Int, confirmed: Boolean): Confidence = when {
        similarity >= 90 && confirmed -> Confidence.CONFIRMED
        similarity >= 60 -> Confidence.PROBABLE
        else -> Confidence.UNVERIFIED
    }

    /** Нормализация адреса: без UTM, якорей, завершающего слэша и мобильного домена. */
    fun normalize(url: String): String {
        var clean = url.trim()
        clean = clean.substringBefore('#')
        if (clean.contains("?")) {
            val base = clean.substringBefore('?')
            val query = clean.substringAfter('?').split('&')
                .filterNot { it.startsWith("utm_") || it.startsWith("fbclid") || it.startsWith("gclid") }
                .joinToString("&")
            clean = if (query.isBlank()) base else "$base?$query"
        }
        clean = clean.removeSuffix("/")
        clean = clean.replace("://m.", "://").replace("://www.", "://")
        return clean.lowercase()
    }

    /** Удаление дублей: одинаковый адрес из разных сервисов остаётся один раз. */
    fun dedupe(matches: List<PhotoMatch>): List<PhotoMatch> {
        val byUrl = LinkedHashMap<String, PhotoMatch>()
        matches.forEach { match ->
            val key = normalize(match.url)
            val previous = byUrl[key]
            byUrl[key] = if (previous == null) {
                match.copy(url = key.takeIf { it.startsWith("http") } ?: match.url)
            } else {
                previous.copy(
                    similarity = maxOf(previous.similarity, match.similarity),
                    status = if (previous.status.level >= match.status.level) previous.status else match.status,
                    provider = (previous.provider.split(", ") + match.provider.split(", ")).distinct().joinToString(", ")
                )
            }
        }
        return byUrl.values.sortedByDescending { it.similarity }
    }

    /** Ссылки для ручной проверки — сервисы без публичного API. */
    fun manualLinks(handle: String = ""): List<Pair<String, String>> = buildList {
        add("Яндекс.Картинки — поиск по фото" to "https://yandex.ru/images/")
        add("Google Lens" to "https://lens.google.com/")
        add("Bing Visual Search" to "https://www.bing.com/visualsearch")
        add("TinEye" to "https://tineye.com/")
        add("IQDB (аниме/иллюстрации)" to "https://iqdb.org/")
        if (handle.isNotBlank()) {
            add("Telegram — аватар @$handle" to "https://t.me/$handle")
            add("ВКонтакте — люди" to "https://vk.com/people?q=$handle")
            add("Одноклассники — поиск" to "https://ok.ru/search?st.query=$handle")
        }
    }

    /**
     * Полный проход по фото: автоматические сервисы + разбор найденного.
     * Ключи берутся из общего хранилища: `saucenao` и `faceapi`.
     */
    suspend fun run(
        photo: PhotoAnalysis,
        keys: KeyRepository,
        onStage: (String, Int) -> Unit = { _, _ -> }
    ): PhotoSearchResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val collected = ArrayList<PhotoMatch>()
        val providers = ArrayList<String>()

        onStage("Обратный поиск изображения", 10)
        val sauceKey = keys.key("saucenao")
        if (sauceKey.isNotBlank()) {
            providers += "SauceNAO"
            val hits = runCatching { ReverseImage.sauceNao(sauceKey, photo, limit = 12) }
                .getOrDefault(emptyList())
            hits.forEach { hit ->
                collected += PhotoMatch(
                    url = hit.url,
                    platform = platformOf(hit.url),
                    title = hit.title.take(120),
                    similarity = hit.similarity,
                    status = statusFor(hit.similarity, confirmed = false),
                    provider = "SauceNAO",
                    handle = PhotoAnalyzer.handlesFrom(listOf(hit.url)).firstOrNull().orEmpty()
                )
            }
        }

        val faceEndpoint = keys.secondField("faceapi")
        if (faceEndpoint.isNotBlank()) {
            onStage("Поиск по лицу", 45)
            providers += "Поиск по лицу"
            val hits = runCatching {
                FaceSearch.search(faceEndpoint, keys.key("faceapi"), photo, limit = 10)
            }.getOrDefault(emptyList())
            hits.forEach { hit ->
                collected += PhotoMatch(
                    url = hit.url,
                    platform = platformOf(hit.url),
                    title = hit.title,
                    similarity = hit.similarity,
                    status = statusFor(hit.similarity, confirmed = hit.similarity >= 90),
                    provider = "Поиск по лицу",
                    handle = PhotoAnalyzer.handlesFrom(listOf(hit.url)).firstOrNull().orEmpty()
                )
            }
        }

        onStage("Дедупликация совпадений", 80)
        val filtered = collected.filter { it.similarity >= MIN_SIMILARITY }
        val matches = dedupe(filtered)
        val handles = matches.mapNotNull { it.handle.takeIf { handle -> handle.isNotBlank() } }
            .distinct()
            .ifEmpty { PhotoAnalyzer.handlesFrom(matches.map { it.url }) }

        PhotoSearchResult(
            photo = photo,
            matches = matches,
            platforms = matches.map { it.platform }.distinct(),
            handles = handles.take(8),
            manualLinks = manualLinks(handles.firstOrNull().orEmpty()),
            providersUsed = providers,
            freeOnly = providers.isEmpty(),
            durationMs = System.currentTimeMillis() - started,
            note = when {
                providers.isEmpty() ->
                    "Автоматический обратный поиск не выполнялся: не заданы ключи SauceNAO и сервиса поиска по лицу. " +
                        "Доступны только технические данные снимка и ссылки для ручной проверки."
                matches.isEmpty() ->
                    "Сервисы не вернули совпадений выше $MIN_SIMILARITY%. Это не значит, что фотографии нет в сети — " +
                        "проверьте вручную по ссылкам ниже."
                else -> "Найдено совпадений: ${matches.size}. Совпадение изображения не равно тождеству личности."
            }
        )
    }

    /** Превращение совпадений в факты: попадают в досье и общий граф. */
    fun toFacts(result: PhotoSearchResult): List<RawFact> {
        val out = ArrayList<RawFact>()
        val subject = result.photo.fileName
        result.matches.forEach { match ->
            out += RawFact(
                category = FactCategory.DOCUMENT,
                kind = "Совпадение по фото",
                value = match.url,
                subject = subject,
                attributes = buildMap {
                    put("Платформа", match.platform)
                    put("Схожесть", "${match.similarity}%")
                    put("Достоверность", match.status.title)
                    put("Сервис", match.provider)
                    match.handle.takeIf { it.isNotBlank() }?.let { put("Никнейм", it) }
                },
                sourceId = "photo",
                weight = if (match.status == Confidence.CONFIRMED) 3 else 2,
                sourceUrl = match.url
            )
        }
        result.handles.forEach { handle ->
            out += RawFact(
                category = FactCategory.PERSON,
                kind = "Никнейм из совпадения",
                value = "@$handle",
                subject = subject,
                attributes = mapOf("Найден" to "по фотографии"),
                sourceId = "photo",
                weight = 2
            )
        }
        return out
    }

    /** Сводка по снимку для карточки результата. */
    fun summary(result: PhotoSearchResult): String {
        val parts = mutableListOf<String>()
        parts += "${result.photo.width}×${result.photo.height}"
        if (result.photo.faces > 0) parts += "лиц: ${result.photo.faces}"
        parts += "совпадений: ${result.matches.size}"
        if (result.platforms.isNotEmpty()) parts += result.platforms.joinToString(", ")
        return parts.joinToString(" · ")
    }
}
