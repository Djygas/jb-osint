package ru.jbosint.osint.core.aggregate

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.Link
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.SearchStats

/**
 * Агрегатор результатов: объединяет ответы всех источников, убирает дубликаты,
 * сливает наблюдения и рассчитывает достоверность. Не сохраняет и не раскрывает
 * названия источников — только их количество.
 */
object Aggregator {

    private const val MAX_FACTS = 4000
    private const val MAX_LINKS = 240

    fun aggregate(
        query: String,
        raw: List<RawFact>,
        stats: SearchStats
    ): AggregatedData {
        val merged = LinkedHashMap<String, MutableBundle>()

        for (item in raw) {
            val value = clean(item.value)
            if (value.isBlank()) continue
            val subject = clean(item.subject)
            val key = buildKey(item.category, item.kind, value, subject)
            val existing = merged[key]
            if (existing == null) {
                merged[key] = MutableBundle(
                    category = item.category,
                    kind = clean(item.kind).ifBlank { "Сведение" },
                    value = value,
                    subject = subject,
                    attributes = item.attributes.filterValues { it.isNotBlank() }.toMutableMap(),
                    sourceIds = mutableSetOf(item.sourceId),
                    observations = item.weight.coerceAtLeast(1)
                )
            } else {
                existing.sourceIds += item.sourceId
                existing.observations += item.weight.coerceAtLeast(1)
                for ((k, v) in item.attributes) {
                    if (v.isBlank()) continue
                    val prev = existing.attributes[k]
                    existing.attributes[k] = when {
                        prev.isNullOrBlank() -> v
                        prev.equals(v, ignoreCase = true) -> prev
                        else -> mergeAttribute(prev, v)
                    }
                }
            }
        }

        val facts: List<Fact> = merged.values
            .map { it.toFact() }
            .sortedWith(
                compareBy<Fact> { it.category.order }
                    .thenByDescending { it.sourceCount }
                    .thenBy { it.kind.lowercase() }
                    .thenBy { it.value.lowercase() }
            )
            .take(MAX_FACTS)

        val duplicates = (raw.size - merged.size).coerceAtLeast(0)

        return AggregatedData(
            query = query,
            facts = facts,
            links = buildLinks(facts),
            stats = stats.copy(
                rawFacts = raw.size,
                duplicatesRemoved = duplicates
            )
        )
    }

    /**
     * Профилирование связей:
     *  1) субъект → атрибут (тип связи = вид сведения);
     *  2) перекрёстные связи: объекты, у которых совпал атрибут (cross-referencing).
     */
    fun buildLinks(facts: List<Fact>): List<Link> {
        val result = ArrayList<Link>()
        val subjectKey = { f: Fact -> f.subject.ifBlank { "Запрос" } }

        // 1. Прямые связи субъект → значение.
        val direct = facts
            .filter { it.subject.isNotBlank() && !it.value.equals(it.subject, ignoreCase = true) }
            .groupBy { Triple(subjectKey(it), it.value, it.kind) }
            .map { (triple, group) ->
                Link(
                    from = triple.first,
                    to = triple.second,
                    relation = triple.third,
                    weight = group.sumOf { it.observations },
                    sourceCount = group.maxOf { it.sourceCount }
                )
            }
        result += direct

        // 2. Пересечения: одно и то же значение у разных субъектов.
        facts
            .filter { it.subject.isNotBlank() }
            .groupBy { it.kind.lowercase() + "|" + normalize(it.value) }
            .forEach { (_, group) ->
                val subjects = group.map { it.subject }.distinct()
                if (subjects.size < 2) return@forEach
                val sources = group.flatMap { it.sourceIds }.toSet().size
                for (i in subjects.indices) {
                    for (j in i + 1 until subjects.size) {
                        result += Link(
                            from = subjects[i],
                            to = subjects[j],
                            relation = "пересечение: ${group.first().kind.lowercase()}",
                            weight = group.size,
                            sourceCount = sources
                        )
                    }
                }
            }

        return result
            .groupBy { normalize(it.from) + "→" + normalize(it.to) + "|" + it.relation }
            .map { (_, group) ->
                val base = group.first()
                base.copy(
                    weight = group.sumOf { it.weight },
                    sourceCount = group.maxOf { it.sourceCount }
                )
            }
            .sortedWith(compareByDescending<Link> { it.sourceCount }.thenByDescending { it.weight })
            .take(MAX_LINKS)
    }

    private fun mergeAttribute(a: String, b: String): String {
        val parts = (a.split("; ") + b.split("; ")).map { it.trim() }.filter { it.isNotBlank() }
        return parts.distinctBy { it.lowercase() }.take(4).joinToString("; ")
    }

    private fun buildKey(category: FactCategory, kind: String, value: String, subject: String): String =
        listOf(category.name, normalize(kind), normalize(value), normalize(subject)).joinToString("|")

    /** Нормализация значения для поиска дубликатов. */
    fun normalize(value: String): String {
        var v = value.trim().lowercase().replace('\u00A0', ' ')
        v = v.replace(Regex("\\s+"), " ")
        // Телефоны: если строка состоит только из цифр и телефонных разделителей,
        // сравниваем одни цифры и приводим 8XXXXXXXXXX к 7XXXXXXXXXX.
        val digits = v.filter { it.isDigit() }
        if (digits.length in 10..15) {
            val leftovers = v.filterNot { it.isDigit() || it in " +-()" }
            if (leftovers.isEmpty()) {
                val canonical = if (digits.length == 11 && digits[0] == '8') "7" + digits.substring(1) else digits
                return canonical
            }
        }
        if (v.startsWith("http://") || v.startsWith("https://")) v = v.substringAfter("://")
        v = v.removeSuffix("/")
        if (v.startsWith("www.")) v = v.substring(4)
        return v
    }

    private fun clean(value: String): String = value
        .replace('\u00A0', ' ')
        .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(400)

    private class MutableBundle(
        val category: FactCategory,
        val kind: String,
        val value: String,
        val subject: String,
        val attributes: MutableMap<String, String>,
        val sourceIds: MutableSet<String>,
        var observations: Int
    ) {
        fun toFact() = Fact(
            category = category,
            kind = kind,
            value = value,
            subject = subject,
            attributes = attributes.toMap(),
            sourceIds = sourceIds.toSet(),
            observations = observations
        )
    }
}
