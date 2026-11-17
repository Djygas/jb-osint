package ru.jbosint.osint.data

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.SourceRef
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.core.report.Csv

/**
 * Тип анализа «Telegram-парсер»: карточка канала и выгрузка его аудитории
 * через TeleSINT. Отдельный сценарий, не часть обычного поиска по запросу,
 * потому что каждый запрос платный и результат — список участников.
 */
data class TgDossier(
    val channel: String,
    val title: String,
    val username: String,
    val membersCount: Int,
    val facts: List<RawFact>,
    val members: List<TeleSint.Member>,
    val pagesLoaded: Int,
    val hasMore: Boolean,
    val collectedAt: Long = System.currentTimeMillis()
) {
    val truncated: Boolean get() = hasMore
    val link: String get() = if (username.isNotBlank()) "https://t.me/$username" else channel
}

object TgParser {

    /** Сколько страниц участников максимум тянуть за один запуск (1000 на страницу). */
    const val DEFAULT_MAX_PAGES = 3

    suspend fun collect(
        key: String,
        channel: String,
        maxPages: Int = DEFAULT_MAX_PAGES,
        pageSize: Int = 200,
        onProgress: (String) -> Unit = {}
    ): TgDossier {
        if (key.isBlank()) throw TeleSint.ApiException(401, "Добавьте ключ TeleSINT в разделе «Модули»")
        val target = channel.trim().removePrefix("https://t.me/").removePrefix("http://t.me/")
            .removePrefix("t.me/").trim('/').trimStart('@')
        if (target.isBlank()) throw TeleSint.ApiException(400, "Укажите канал: @name или ссылку t.me/name")

        onProgress("Карточка канала…")
        val facts = ArrayList<RawFact>()
        val groupFacts = runCatching { TeleSint.group(key, target) }.getOrElse { error ->
            if (error is TeleSint.ApiException && error.code == 400) emptyList() else throw error
        }
        facts += groupFacts
        val title = groupFacts.firstOrNull { it.kind.startsWith("Telegram-") }?.value ?: "@$target"
        val username = groupFacts.firstOrNull()?.attributes?.get("Ник")?.trimStart('@') ?: target
        val membersCount = groupFacts.firstOrNull()?.attributes?.get("Подписчиков")?.toIntOrNull() ?: 0

        val members = ArrayList<TeleSint.Member>()
        var page = 1
        var hasMore = false
        val limit = maxPages.coerceIn(1, 20)
        while (page <= limit) {
            onProgress("Участники: страница $page из $limit…")
            val chunk = TeleSint.members(key, target, page, pageSize)
            if (chunk.members.isEmpty()) break
            members += chunk.members
            hasMore = chunk.hasNext
            if (!hasMore) break
            page++
        }
        val unique = members.distinctBy { it.id.ifBlank { it.username.ifBlank { it.displayName } } }
        unique.forEach { members_ ->
            facts += RawFact(
                category = FactCategory.PERSON,
                kind = "Участник Telegram",
                value = members_.displayName,
                subject = title,
                attributes = buildMap {
                    if (members_.username.isNotBlank()) put("Ник", "@${members_.username}")
                    if (members_.id.isNotBlank()) put("ИД", members_.id)
                    if (members_.phone.isNotBlank()) put("Телефон", members_.phone)
                    if (members_.lastOnline.isNotBlank()) put("Последняя активность", members_.lastOnline)
                    if (members_.premium) put("Premium", "да")
                    if (members_.bot) put("Бот", "да")
                    if (members_.username.isNotBlank()) put("Ссылка", "https://t.me/${members_.username}")
                },
                sourceId = "telesint",
                weight = 1
            )
        }
        onProgress("Готово: ${unique.size} участников")
        return TgDossier(
            channel = target,
            title = title,
            username = username,
            membersCount = maxOf(membersCount, unique.size),
            facts = facts,
            members = unique,
            pagesLoaded = page,
            hasMore = hasMore
        )
    }
}

/** Преобразование результата парсера в общий формат отчётов и табличных выгрузок. */
object TgExport {

    fun data(dossier: TgDossier): AggregatedData {
        val merged = LinkedHashMap<Pair<String, String>, Fact>()
        dossier.facts.forEach { raw ->
            val key = raw.kind to raw.value
            val existing = merged[key]
            merged[key] = if (existing == null) {
                Fact(
                    category = raw.category,
                    kind = raw.kind,
                    value = raw.value,
                    subject = raw.subject.ifBlank { dossier.title },
                    attributes = raw.attributes,
                    sourceIds = setOf(raw.sourceId),
                    observations = 1,
                    firstSeen = dossier.collectedAt,
                    urls = setOfNotNull(raw.sourceUrl.takeIf { it.isNotBlank() })
                )
            } else {
                existing.copy(
                    attributes = existing.attributes + raw.attributes,
                    sourceIds = existing.sourceIds + raw.sourceId,
                    observations = existing.observations + 1,
                    urls = existing.urls + setOfNotNull(raw.sourceUrl.takeIf { it.isNotBlank() })
                )
            }
        }
        val groupFacts = merged.values.filter { it.kind.startsWith("Telegram-") }
        val audience = merged.values.filter { it.kind == "Участник Telegram" }
        return AggregatedData(
            query = dossier.link,
            facts = groupFacts + audience,
            stats = SearchStats(
                sourcesAttempted = 1,
                sourcesResponded = 1,
                sourcesFailed = 0,
                rawFacts = dossier.facts.size,
                duplicatesRemoved = dossier.facts.size - merged.size,
                durationMs = 0
            ),
            sources = listOf(SourceRef("telesint", "TeleSINT", TeleSint.SITE))
        )
    }

    /** CSV аудитории: заголовок + по строке на участника. */
    fun csv(dossier: TgDossier): String {
        val rows = ArrayList<List<String>>()
        rows += listOf(
            "ID", "Ник", "Имя", "Фамилия", "Телефон",
            "Premium", "Бот", "Последняя активность", "Ссылка"
        )
        dossier.members.forEach { m ->
            rows += listOf(
                m.id,
                if (m.username.isNotBlank()) "@${m.username}" else "",
                m.firstName,
                m.lastName,
                m.phone,
                if (m.premium) "да" else "",
                if (m.bot) "да" else "",
                m.lastOnline,
                if (m.username.isNotBlank()) "https://t.me/${m.username}" else ""
            )
        }
        return Csv.BOM + Csv.of(rows)
    }

    /** Имя файла: tg_<канал>_<дата>.<ext>. */
    fun fileName(dossier: TgDossier, extension: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date(dossier.collectedAt))
        val safe = dossier.username.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "channel" }
        return "tg_${safe}_$stamp.$extension"
    }
}
