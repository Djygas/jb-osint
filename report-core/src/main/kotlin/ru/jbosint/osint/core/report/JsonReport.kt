package ru.jbosint.osint.core.report

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.ReportMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Машиночитаемое представление отчёта (JSON) для дальнейшей программной обработки.
 * Не содержит названий источников — только число подтверждений.
 */
class JsonReport(
    private val meta: ReportMeta,
    private val data: AggregatedData
) {
    fun build(): String {
        val sb = StringBuilder(16384)
        sb.append("{\n")
        sb.append("  \"schema\": \"jb-osint/report/1.0\",\n")
        sb.append("  \"generator\": \"JB OSINT ${meta.appVersion}\",\n")
        sb.append("  \"report_id\": ${str(meta.reportId)},\n")
        sb.append("  \"generated_at\": ${str(isoDate(meta.createdAt))},\n")
        sb.append("  \"classification\": ${str(meta.classification)},\n")
        sb.append("  \"analyst\": ${str(meta.analyst.ifBlank { "Не указан" })},\n")
        sb.append("  \"query\": ${str(data.query)},\n")

        sb.append("  \"methodology\": {\n")
        sb.append("    \"techniques\": [\"сбор и нормализация\", \"триангуляция данных\", \"перекрёстная проверка\", \"анализ метаданных\"],\n")
        sb.append("    \"confidence_rule\": \"подтверждено: >=3 источников; вероятно: 2; требует проверки: 1\",\n")
        sb.append("    \"deduplication\": \"по нормализованному значению и субъекту\"\n")
        sb.append("  },\n")

        sb.append("  \"statistics\": {\n")
        sb.append("    \"sources_attempted\": ${data.stats.sourcesAttempted},\n")
        sb.append("    \"sources_responded\": ${data.stats.sourcesResponded},\n")
        sb.append("    \"sources_failed\": ${data.stats.sourcesFailed},\n")
        sb.append("    \"raw_observations\": ${data.stats.rawFacts},\n")
        sb.append("    \"unique_facts\": ${data.facts.size},\n")
        sb.append("    \"duplicates_removed\": ${data.stats.duplicatesRemoved},\n")
        sb.append("    \"links\": ${data.links.size},\n")
        sb.append("    \"duration_ms\": ${data.stats.durationMs}\n")
        sb.append("  },\n")

        sb.append("  \"confidence_summary\": {")
        val levels = Confidence.entries.reversed()
        levels.forEachIndexed { index, level ->
            if (index > 0) sb.append(", ")
            sb.append(str(level.title)).append(": ").append(data.confidenceCounts[level] ?: 0)
        }
        sb.append("},\n")

        sb.append("  \"facts\": [")
        data.facts.forEachIndexed { index, fact ->
            if (index > 0) sb.append(',')
            sb.append("\n    {")
            sb.append("\"id\": ").append(index + 1).append(", ")
            sb.append("\"category\": ").append(str(fact.category.title)).append(", ")
            sb.append("\"kind\": ").append(str(fact.kind)).append(", ")
            sb.append("\"value\": ").append(str(fact.value)).append(", ")
            sb.append("\"subject\": ").append(str(fact.subject)).append(", ")
            sb.append("\"source_count\": ").append(fact.sourceCount).append(", ")
            sb.append("\"observations\": ").append(fact.observations).append(", ")
            sb.append("\"confidence\": ").append(str(fact.confidence.title)).append(", ")
            sb.append("\"attributes\": {")
            fact.attributes.entries.forEachIndexed { attrIndex, entry ->
                if (attrIndex > 0) sb.append(", ")
                sb.append(str(entry.key)).append(": ").append(str(entry.value))
            }
            sb.append("}}")
        }
        sb.append("\n  ],\n")

        sb.append("  \"links\": [")
        data.links.forEachIndexed { index, link ->
            if (index > 0) sb.append(',')
            sb.append("\n    {")
            sb.append("\"from\": ").append(str(link.from)).append(", ")
            sb.append("\"to\": ").append(str(link.to)).append(", ")
            sb.append("\"relation\": ").append(str(link.relation)).append(", ")
            sb.append("\"weight\": ").append(link.weight).append(", ")
            sb.append("\"source_count\": ").append(link.sourceCount)
            sb.append("}")
        }
        sb.append("\n  ],\n")

        sb.append("  \"categories\": {")
        FactCategory.entries.forEachIndexed { index, category ->
            val count = data.categoryCounts[category] ?: 0
            if (count == 0) return@forEachIndexed
            if (sb[sb.length - 1] != '{') sb.append(", ")
            sb.append(str(category.title)).append(": ").append(count)
        }
        sb.append("}\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun isoDate(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(if (ts == 0L) System.currentTimeMillis() else ts))

    private fun str(value: String): String {
        val sb = StringBuilder(value.length + 8)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format(Locale.US, "\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/** Имена файлов отчётов в папке JB_OSINT_Reports. */
object ReportNaming {
    private val translit = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    fun slug(query: String, maxLength: Int = 40): String {
        val sb = StringBuilder()
        for (ch in query.lowercase()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                translit.containsKey(ch) -> sb.append(translit[ch])
                ch == ' ' || ch == '-' || ch == '_' || ch == '.' -> sb.append('_')
            }
            if (sb.length >= maxLength) break
        }
        val cleaned = sb.toString().trim('_').replace(Regex("_+"), "_")
        return cleaned.ifBlank { "report" }
    }

    fun fileName(meta: ReportMeta, query: String, extension: String): String {
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(if (meta.createdAt == 0L) System.currentTimeMillis() else meta.createdAt))
        return "JB_OSINT_${slug(query)}_$date.$extension"
    }

    fun reportId(query: String, createdAt: Long): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("$query|$createdAt".toByteArray())
        val hash = digest.digest().take(4).joinToString("") { String.format(Locale.US, "%02X", it) }
        val date = SimpleDateFormat("yyMMdd", Locale.US).format(Date(if (createdAt == 0L) System.currentTimeMillis() else createdAt))
        return "JB-$date-$hash"
    }
}
