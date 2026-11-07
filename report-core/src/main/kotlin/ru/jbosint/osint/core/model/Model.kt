package ru.jbosint.osint.core.model

/**
 * Категории фактов. Порядок задаёт последовательность разделов в отчёте.
 */
enum class FactCategory(val title: String, val order: Int, val short: String) {
    PERSON("Люди и профили", 1, "Люди"),
    CONTACT("Контактные данные", 2, "Контакты"),
    ORGANIZATION("Организации", 3, "Организации"),
    LOCATION("Геолокация", 4, "Локации"),
    TECHNICAL("Технические данные", 5, "Технические"),
    DOCUMENT("Документы и медиа", 6, "Документы"),
    OTHER("Прочие сведения", 9, "Прочее");

    companion object {
        fun fromOrder(value: Int): FactCategory = entries.minByOrNull { kotlin.math.abs(it.order - value) } ?: OTHER
    }
}

/** Уровень достоверности факта, вычисляется по числу независимых источников. */
enum class Confidence(val title: String, val note: String, val level: Int) {
    CONFIRMED("Подтверждено", "три и более независимых источника", 3),
    PROBABLE("Вероятно", "два независимых источника", 2),
    UNVERIFIED("Требует проверки", "единственный источник", 1)
}

/**
 * Сырой факт, полученный от одного источника.
 * sourceId — внутренний технический идентификатор слота, пользователю не показывается.
 */
data class RawFact(
    val category: FactCategory,
    val kind: String,
    val value: String,
    val subject: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val sourceId: String = "internal",
    val weight: Int = 1
)

/** Агрегированный факт после дедупликации и слияния наблюдений. */
data class Fact(
    val category: FactCategory,
    val kind: String,
    val value: String,
    val subject: String,
    val attributes: Map<String, String>,
    val sourceIds: Set<String>,
    val observations: Int,
    val firstSeen: Long = 0L
) {
    val sourceCount: Int get() = sourceIds.size
    val confidence: Confidence
        get() = when {
            sourceCount >= 3 -> Confidence.CONFIRMED
            sourceCount == 2 -> Confidence.PROBABLE
            else -> Confidence.UNVERIFIED
        }
}

/** Связь между объектами (для блока профилирования связей). */
data class Link(
    val from: String,
    val to: String,
    val relation: String,
    val weight: Int,
    val sourceCount: Int
)

/** Статистика выполнения поиска — только агрегированные числа, без имён источников. */
data class SearchStats(
    val sourcesAttempted: Int = 0,
    val sourcesResponded: Int = 0,
    val sourcesFailed: Int = 0,
    val rawFacts: Int = 0,
    val duplicatesRemoved: Int = 0,
    val durationMs: Long = 0L
) {
    val successRate: Int get() = if (sourcesAttempted == 0) 0 else sourcesResponded * 100 / sourcesAttempted
}

/** Полный результат поиска, готовый к формированию отчёта. */
data class AggregatedData(
    val query: String,
    val facts: List<Fact> = emptyList(),
    val links: List<Link> = emptyList(),
    val stats: SearchStats = SearchStats()
) {
    val categoryCounts: Map<FactCategory, Int>
        get() = facts.groupingBy { it.category }.eachCount()

    val confidenceCounts: Map<Confidence, Int>
        get() = facts.groupingBy { it.confidence }.eachCount()

    val subjects: List<String>
        get() = facts.map { it.subject }.filter { it.isNotBlank() }.distinct()

    fun factsOf(category: FactCategory): List<Fact> = facts.filter { it.category == category }

    val isEmpty: Boolean get() = facts.isEmpty()
}

/** Метаданные отчёта. */
data class ReportMeta(
    val analyst: String,
    val reportId: String,
    val createdAt: Long,
    val appVersion: String = "1.0.0",
    val classification: String = "КОНФИДЕНЦИАЛЬНО · ДЛЯ ВНУТРЕННЕГО ИСПОЛЬЗОВАНИЯ",
    /** Аналитический вывод интеллектуального ассистента (может быть пустым). */
    val aiAnalysis: String = "",
    /** Оформленное ИИ-резюме исследования для отчёта (может быть пустым). */
    val aiNarrative: String = ""
)
