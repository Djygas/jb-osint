package ru.jbosint.osint.core.report

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.ReportMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Автономный HTML-отчёт: один файл, встроенные стили, без внешних ресурсов.
 * Используется для выгрузки досье и для отчётов Telegram-парсера, поэтому
 * принимает необязательное вступление и заголовок раздела фактов.
 */
class HtmlReport(
    private val meta: ReportMeta,
    private val data: AggregatedData,
    private val intro: String = "",
    private val factsTitle: String = "Установленные сведения"
) {

    fun build(): String = buildString {
        append("<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>JB OSINT — ${esc(data.query)}</title>")
        append("<style>").append(CSS).append("</style></head><body>")

        append("<header><div class=\"brand\">JB <span>OSINT</span></div>")
        append("<div class=\"cls\">").append(esc(meta.classification)).append("</div></header>")

        append("<main>")
        append("<h1>Отчёт об исследовании открытых источников</h1>")
        append("<p class=\"lead\">Объект: <b>").append(esc(data.query)).append("</b>")
        if (meta.queryType.isNotBlank()) append(" · ").append(esc(meta.queryType))
        append("</p>")
        append("<div class=\"meta\">")
        append("<span>Отчёт ").append(esc(meta.reportId)).append("</span>")
        append("<span>").append(esc(date(meta.createdAt))).append("</span>")
        append("<span>JB OSINT ").append(esc(meta.appVersion)).append("</span>")
        if (meta.analyst.isNotBlank()) append("<span>Аналитик: ").append(esc(meta.analyst)).append("</span>")
        append("</div>")

        if (meta.photoNote.isNotBlank()) {
            append("<div class=\"note\">").append(esc(meta.photoNote)).append("</div>")
        }
        if (intro.isNotBlank()) {
            append("<div class=\"note\">").append(esc(intro)).append("</div>")
        }

        append("<section><h2>Сводка</h2><div class=\"cards\">")
        append(card("Сведений", data.facts.size.toString()))
        append(card("Субъектов", data.subjects.size.toString()))
        append(card("Модулей", data.stats.sourcesAttempted.toString()))
        append(card("Ответили", data.stats.sourcesResponded.toString()))
        append("</div>")
        val conf = data.confidenceCounts
        append("<p class=\"muted\">Подтверждено: ").append(conf[Confidence.CONFIRMED] ?: 0)
        append(" · Вероятно: ").append(conf[Confidence.PROBABLE] ?: 0)
        append(" · Требует проверки: ").append(conf[Confidence.UNVERIFIED] ?: 0).append("</p></section>")

        val face = ReportSections.faceFacts(data)
        if (face.isNotEmpty()) {
            append("<section><h2>Поиск по лицу</h2>")
            append("<table><thead><tr><th>№</th><th>Страница</th><th>Схожесть</th><th>Статус</th><th>Источник</th></tr></thead><tbody>")
            face.forEachIndexed { index, fact ->
                append("<tr><td>").append(index + 1).append("</td>")
                append("<td class=\"url\">").append(link(fact)).append("</td>")
                append("<td>").append(ReportSections.faceSimilarity(fact)?.let { "$it%" } ?: "—").append("</td>")
                append("<td>").append(esc(ReportSections.faceStatus(fact, data))).append("</td>")
                append("<td>").append(esc(ReportSections.faceNote(fact, data))).append("</td></tr>")
            }
            append("</tbody></table></section>")
        }

        append("<section><h2>").append(esc(factsTitle)).append("</h2>")
        if (data.facts.isEmpty()) {
            append("<p class=\"muted\">По запросу не получено сведений.</p>")
        } else {
            data.facts.filter { it !in face }.groupBy { it.category }
                .toSortedMap(compareBy { it.order })
                .forEach { (category, facts) -> categoryBlock(category, facts) }
        }
        append("</section>")

        if (meta.aiAnalysis.isNotBlank() || meta.aiNarrative.isNotBlank()) {
            append("<section><h2>Аналитический вывод</h2>")
            paragraphs(meta.aiAnalysis).forEach { append("<p>").append(esc(it)).append("</p>") }
            paragraphs(meta.aiNarrative).forEach { append("<p>").append(esc(it)).append("</p>") }
            append("</section>")
        }

        if (data.sources.isNotEmpty()) {
            append("<section><h2>Источники</h2><table><thead><tr><th>№</th><th>Модуль</th><th>Ресурс</th></tr></thead><tbody>")
            data.sources.forEachIndexed { index, source ->
                append("<tr><td>").append(index + 1).append("</td><td>").append(esc(source.name))
                append("</td><td class=\"url\">").append(esc(source.url)).append("</td></tr>")
            }
            append("</tbody></table></section>")
        }

        append("</main><footer>Сформировано JB OSINT ").append(esc(meta.appVersion))
        append(" · ").append(esc(date(meta.createdAt)))
        append("<br>Сведения собраны из открытых источников и не являются доказательством в юридическом смысле.</footer>")
        append("</body></html>")
    }

    private fun StringBuilder.categoryBlock(category: FactCategory, facts: List<Fact>) {
        append("<h3>").append(esc(category.title)).append(" <span class=\"count\">").append(facts.size).append("</span></h3>")
        append("<table><thead><tr><th>Сведение</th><th>Значение</th><th>Детали</th><th>Достоверность</th></tr></thead><tbody>")
        facts.forEach { fact ->
            append("<tr><td>").append(esc(fact.kind)).append("</td>")
            append("<td class=\"value\">").append(link(fact)).append("</td>")
            append("<td class=\"details\">").append(esc(details(fact))).append("</td>")
            append("<td><span class=\"badge ").append(badgeClass(fact.confidence)).append("\">")
            append(esc(fact.confidence.title)).append("</span></td></tr>")
        }
        append("</tbody></table>")
    }

    private fun link(fact: Fact): String {
        val url = fact.urls.firstOrNull { it.startsWith("http") }
            ?: fact.value.takeIf { it.startsWith("http") }
        return if (url != null) {
            "<a href=\"${esc(url)}\" rel=\"noopener noreferrer\">${esc(fact.value)}</a>"
        } else {
            esc(fact.value)
        }
    }

    private fun details(fact: Fact): String = fact.attributes.entries.joinToString("; ") { "${it.key}: ${it.value}" }

    private fun card(title: String, value: String) =
        "<div class=\"card\"><b>$value</b><span>${esc(title)}</span></div>"

    private fun badgeClass(confidence: Confidence) = when (confidence) {
        Confidence.CONFIRMED -> "ok"
        Confidence.PROBABLE -> "mid"
        Confidence.UNVERIFIED -> "low"
    }

    private fun paragraphs(text: String): List<String> = text.split('\n')
        .map { it.trim() }.filter { it.isNotBlank() }

    private fun date(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(if (millis > 0) millis else System.currentTimeMillis()))

    private fun esc(value: String): String = buildString(value.length + 16) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private companion object {
        val CSS = """
            :root{--ink:#14161c;--body:#2b2f38;--muted:#6b7280;--line:#e4e7ec;--bg:#f7f8fa;--accent:#4f5bff}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--body);font:14px/1.55 -apple-system,Segoe UI,Roboto,Arial,sans-serif}
            header{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:16px 24px;background:#0b0e14;color:#edeff5}
            .brand{font-weight:800;letter-spacing:.08em;font-size:18px}
            .brand span{color:#8b5cf6}
            .cls{font-size:11px;letter-spacing:.06em;color:#8a93a6;text-align:right}
            main{max-width:980px;margin:0 auto;padding:24px 20px 40px}
            h1{font-size:24px;color:var(--ink);margin:8px 0 6px}
            .lead{margin:0 0 10px}
            .meta{display:flex;flex-wrap:wrap;gap:14px;color:var(--muted);font-size:12px;margin-bottom:14px}
            .note{border-left:3px solid var(--accent);background:#eef0ff;padding:10px 14px;border-radius:6px;margin:12px 0;font-size:13px}
            section{background:#fff;border:1px solid var(--line);border-radius:10px;padding:16px 18px;margin:14px 0}
            h2{font-size:17px;color:var(--ink);margin:0 0 10px}
            h3{font-size:14px;color:var(--ink);margin:18px 0 8px;display:flex;gap:8px;align-items:center}
            .count{background:#eef0ff;color:var(--accent);border-radius:20px;padding:1px 8px;font-size:11px}
            .cards{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:8px}
            .card{border:1px solid var(--line);border-radius:8px;padding:8px 14px;min-width:96px;display:flex;flex-direction:column}
            .card b{font-size:18px;color:var(--ink)}
            .card span{font-size:11px;color:var(--muted)}
            .muted{color:var(--muted);font-size:12px}
            table{width:100%;border-collapse:collapse;font-size:12.5px}
            th,td{text-align:left;vertical-align:top;padding:7px 8px;border-bottom:1px solid var(--line)}
            th{color:var(--muted);font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.04em}
            tr:nth-child(even) td{background:#fafbfc}
            .value{color:var(--ink);font-weight:600;word-break:break-word}
            .url a{color:var(--accent);text-decoration:none;word-break:break-all}
            .details{color:var(--muted)}
            .badge{display:inline-block;border-radius:20px;padding:2px 9px;font-size:11px;font-weight:600;white-space:nowrap}
            .badge.ok{background:#e6f6ee;color:#12885a}
            .badge.mid{background:#fdf1e3;color:#b45309}
            .badge.low{background:#eef1f5;color:#64748b}
            footer{max-width:980px;margin:0 auto;padding:0 20px 30px;color:var(--muted);font-size:11px;line-height:1.6}
            @media print{body{background:#fff}section{border:0;padding:0;margin:10px 0}header{background:#fff;color:#000}.cls{color:#555}}
        """.trimIndent()
    }
}
