package ru.jbosint.osint.core.report

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.ReportMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Общие правила разбиения сведений по разделам досье.
 * Используются и PDF, и XLSX, и JSON, чтобы отчёты не расходились по составу.
 */
object ReportSections {

    private val imageExt = Regex("\\.(jpg|jpeg|png|webp|gif|bmp)(\\?|$)", RegexOption.IGNORE_CASE)
    private val socialHosts = listOf(
        "vk.com", "vk.ru", "ok.ru", "instagram.com", "t.me", "telegram.me",
        "facebook.com", "twitter.com", "x.com", "tiktok.com", "youtube.com",
        "github.com", "gitlab.com", "linkedin.com", "habr.com"
    )

    fun telegramFacts(data: AggregatedData): List<Fact> = data.facts.filter { f ->
        val probe = (f.kind + " " + f.value + " " + f.attributes.values.joinToString(" ")).lowercase()
        f.value.contains("t.me", true) || probe.contains("telegram") || probe.contains("телеграм") ||
            f.kind.contains("Канал", true) || f.kind.contains("Чат", true)
    }

    fun socialFacts(data: AggregatedData): List<Fact> = data.facts.filter { f ->
        if (telegramFacts(data).any { it.value == f.value && it.category == f.category }) return@filter false
        val text = (f.value + " " + f.attributes.values.joinToString(" ")).lowercase()
        socialHosts.any { text.contains(it) }
    }

    fun emailFacts(data: AggregatedData): List<Fact> = data.facts.filter { f ->
        f.value.contains('@') && !f.value.startsWith("@") && f.value.substringAfter('@').contains('.')
    }

    fun leakFacts(data: AggregatedData): List<Fact> = data.facts.filter {
        it.kind.contains("утеч", true) || it.kind.contains("leak", true) || it.category == FactCategory.DOCUMENT &&
            it.attributes.keys.any { key -> key.contains("баз", true) || key.contains("breach", true) }
    }

    fun documentFacts(data: AggregatedData): List<Fact> = data.facts.filter { f ->
        f.category == FactCategory.DOCUMENT && !isMedia(f)
    }

    fun mediaFacts(data: AggregatedData): List<Fact> = data.facts.filter { isMedia(it) }

    /**
     * Совпадения обратного поиска по фотографии: страницы, где встретилось то же
     * лицо или то же изображение. Метаданные самого файла (хеш, EXIF, размер)
     * сюда не попадают — это не находки, а свойства снимка.
     */
    fun faceFacts(data: AggregatedData): List<Fact> = data.facts.filter { fact ->
        val kind = fact.kind.lowercase()
        val match = kind.contains("совпадени") &&
            (kind.contains("фото") || kind.contains("изображени") || kind.contains("лиц"))
        match || fact.sourceIds.any { it == "faceapi" || it == "face" }
    }

    /** Схожесть найденного совпадения в процентах (или null, если сервис её не сообщил). */
    fun faceSimilarity(fact: Fact): Int? {
        for (key in listOf("схожесть", "совпадение", "similarity", "score")) {
            val raw = fact.attributes.entries.firstOrNull { it.key.equals(key, true) }?.value ?: continue
            val number = Regex("\\d{1,3}([.,]\\d+)?").find(raw)?.value?.replace(',', '.') ?: continue
            val value = number.toDoubleOrNull() ?: continue
            return if (value <= 1.0 && raw.contains('.')) (value * 100).toInt() else value.toInt()
        }
        return null
    }

    /**
     * Статус совпадения: схожесть ≥ 90% и подтверждение тем же ресурсом от другого
     * независимого модуля — «Подтверждено»; 60–89% либо 90% без подтверждения —
     * «Требует проверки»; ниже — «Не подтверждено». Статус относится к совпадению
     * изображения, а не к тождеству личности.
     */
    fun faceStatus(fact: Fact, data: AggregatedData): String {
        val similarity = faceSimilarity(fact)
        val host = evidenceUrl(fact).substringAfter("://").substringBefore('/').removePrefix("www.")
        val confirmedElsewhere = data.facts.any { other ->
            if (other === fact) return@any false
            // подтверждением считается только независимый модуль, а не тот же источник
            if (other.sourceIds.any { it in fact.sourceIds }) return@any false
            val samePage = other.value.equals(fact.value, ignoreCase = true)
            val sameHost = host.isNotBlank() && other.value.contains(host, ignoreCase = true)
            (samePage || sameHost) && (faceSimilarity(other) ?: 0) >= 60
        }
        return when {
            similarity == null -> "Требует проверки"
            similarity >= 90 && confirmedElsewhere -> "Подтверждено"
            similarity >= 60 -> "Требует проверки"
            else -> "Не подтверждено"
        }
    }

    /** Примечание к совпадению: провайдер, порог и оговорка о пределах вывода. */
    fun faceNote(fact: Fact, data: AggregatedData): String {
        val provider = fact.attributes.entries.firstOrNull { it.key.equals("ресурс", true) }?.value
            ?: fact.attributes.entries.firstOrNull { it.key.equals("провайдер", true) }?.value
            ?: sourceNames(fact)
        val similarity = faceSimilarity(fact)?.let { "$it%" } ?: "не указана"
        val status = faceStatus(fact, data)
        return buildString {
            append("Сервис: ").append(provider.ifBlank { "—" })
            append("; схожесть: ").append(similarity)
            append("; вывод: ").append(status.lowercase())
            if (status != "Подтверждено") append(" — требуется ручная сверка профиля")
            append(". Совпадение изображения не подтверждает тождество личности.")
        }
    }

    private fun isMedia(fact: Fact): Boolean {
        if (fact.value.contains(imageExt)) return true
        if (fact.attributes.values.any { it.contains(imageExt) }) return true
        val kind = fact.kind.lowercase()
        return kind.contains("фото") || kind.contains("аватар") || kind.contains("изображен") ||
            kind.contains("снимок") || kind.contains("медиа")
    }

    fun sourceNames(fact: Fact): String =
        fact.sourceIds.map { SourceCatalog.ref(it).name }.distinct().joinToString(", ")

    /** Ссылка-доказательство: найденная страница либо адрес самого ресурса. */
    fun evidenceUrl(fact: Fact): String {
        fact.urls.firstOrNull { it.startsWith("http") }?.let { return it }
        if (fact.value.startsWith("http")) return fact.value
        val base = fact.sourceIds.map { SourceCatalog.ref(it).url }.firstOrNull { it.isNotBlank() }
        return base.orEmpty()
    }

    fun sourceLine(fact: Fact): String {
        val names = sourceNames(fact).ifBlank { "Поиск JB OSINT" }
        val url = evidenceUrl(fact)
        return if (url.isBlank()) names else "$names ($url)"
    }

    fun dateText(ts: Long): String =
        SimpleDateFormat("dd.MM.yyyy", Locale("ru")).format(Date(if (ts == 0L) System.currentTimeMillis() else ts))

    fun statusLine(fact: Fact, data: AggregatedData): String {
        val base = "${fact.confidence.title} (${fact.sourceCount} ист.)"
        val related = relatedValue(fact, data)
        return if (related.isBlank()) base else "$base; совпадение с «${related.take(70)}»"
    }

    /** Значение другого типа, встретившееся в том же наблюдении (совпадение по атрибуту). */
    private fun relatedValue(fact: Fact, data: AggregatedData): String {
        if (fact.attributes.isEmpty()) return ""
        val others = data.facts.filter { it !== fact && it.value != fact.value }
        for ((_, attrValue) in fact.attributes) {
            val hit = others.firstOrNull { other ->
                attrValue.contains(other.value, ignoreCase = true) && other.value.length >= 5
            }
            if (hit != null) return "${hit.kind}: ${hit.value}"
        }
        return ""
    }

    fun noteFor(fact: Fact, meta: ReportMeta): String {
        meta.aiNotes[fact.value]?.let { return it }
        if (fact.attributes.isEmpty()) return ""
        return fact.attributes.entries.take(3).joinToString("; ") { "${it.key}: ${it.value}" }.take(300)
    }
}
