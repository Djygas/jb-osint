package ru.jbosint.osint.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Готовые шаблоны отчёта агента: «Быстрый чек», «Глубокое досье»,
 * «Анализ авто», «Анализ бизнеса».
 *
 * Шаблон меняет состав и порядок разделов, но не источник данных: всё берётся
 * из [AgentReport], модули остаются анонимными («Модуль N»), а реальные
 * названия и ссылки выводятся только в приложении B.
 */
enum class AgentTemplate(val title: String, val description: String) {
    QUICK("Быстрый чек", "Одна страница: кто это, ключевые контакты, риски"),
    DEEP("Глубокое досье", "Полный отчёт: все факты, связи, предположения, приложение B"),
    AUTO("Анализ авто", "VIN, история, залоги, пробег, рекомендации перед сделкой"),
    BUSINESS("Анализ бизнеса", "Организации, домены, почты, связи и репутация")
}

object AgentTemplates {

    private val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))

    fun build(report: AgentReport, template: AgentTemplate): ByteArray {
        val title = when (template) {
            AgentTemplate.QUICK -> "Быстрый чек"
            AgentTemplate.DEEP -> "Досье"
            AgentTemplate.AUTO -> "Отчёт по автомобилю"
            AgentTemplate.BUSINESS -> "Анализ бизнеса"
        }
        val body = StringBuilder()
        when (template) {
            AgentTemplate.QUICK -> {
                body.append("<h2>Объект</h2>")
                body.append("<p class=\"big\">${esc(report.query)}</p>")
                body.append("<p class=\"muted\">Тип запроса: ${report.target.title} · ${report.note}</p>")
                body.append("<h2>Ключевое</h2>")
                appendFindings(body, report.findings, limit = 10)
                body.append("<h2>Риски и что проверить</h2>")
                if (report.assumptions.isEmpty()) body.append("<p class=\"muted\">Явных рисков не выявлено.</p>")
                else appendAssumptions(body, report.assumptions)
            }
            AgentTemplate.DEEP -> {
                body.append("<h2>Карточка</h2>")
                body.append("<table><tbody>")
                body.append(row("Объект", report.query))
                body.append(row("Тип", report.target.title))
                body.append(row("Собрано", stamp.format(Date(System.currentTimeMillis()))))
                body.append(row("Фактов", report.findings.size.toString()))
                body.append(row("Модулей", report.modules.size.toString()))
                body.append(row("Платных запросов", report.paidRequests.toString()))
                body.append("</tbody></table>")
                body.append("<h2>Установленные факты</h2>")
                appendFindings(body, report.findings, limit = 200)
                body.append("<h2>Аналитические предположения</h2>")
                appendAssumptions(body, report.assumptions)
                body.append("<h2>План сбора</h2><table><tbody>")
                report.plan.forEach { step ->
                    body.append(row(step.title, "${step.tier.title} · ${step.state.title}" + if (step.facts > 0) " · фактов ${step.facts}" else ""))
                }
                body.append("</tbody></table>")
                appendAppendix(body, report)
            }
            AgentTemplate.AUTO -> {
                body.append("<h2>Автомобиль</h2>")
                body.append("<p class=\"big\">${esc(report.query)}</p>")
                body.append("<table><tbody>")
                report.findings.filter { isAutoFact(it.kind) }.take(40).forEach { finding ->
                    body.append(row(finding.kind, finding.value))
                }
                body.append("</tbody></table>")
                body.append("<h2>Что проверить перед сделкой</h2><ul>")
                body.append("<li>Сверить VIN на кузове и в документах, проверить контрольную сумму.</li>")
                body.append("<li>Проверить залоги в реестре залогов и ограничения в ГИБДД.</li>")
                body.append("<li>Сверить пробег с историей обслуживания и годом выпуска.</li>")
                body.append("<li>Запросить отчёт о ДТП и ремонтах у продавца.</li>")
                body.append("</ul>")
                if (report.assumptions.isNotEmpty()) {
                    body.append("<h2>Предположения</h2>")
                    appendAssumptions(body, report.assumptions)
                }
                appendAppendix(body, report)
            }
            AgentTemplate.BUSINESS -> {
                val business = report.findings.filter {
                    it.kind.contains("организац", true) || it.value.contains(".") ||
                        it.kind.contains("домен", true) || it.kind.contains("почт", true) ||
                        it.kind.contains("email", true)
                }
                body.append("<h2>Объект</h2>")
                body.append("<p class=\"big\">${esc(report.query)}</p>")
                body.append("<h2>Организации, домены и связи</h2>")
                if (business.isEmpty()) body.append("<p class=\"muted\">Юридические лица и домены не найдены.</p>")
                else appendFindings(body, business, limit = 60)
                body.append("<h2>Репутация и риски</h2>")
                appendAssumptions(body, report.assumptions)
                appendAppendix(body, report)
            }
        }
        val html = buildString {
            append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>$title — ${esc(report.query)}</title>")
            append(
                "<style>body{background:#0B0E14;color:#EDEFF5;font-family:Inter,Arial,sans-serif;margin:0;padding:24px}" +
                    "h1{font-size:22px;margin:0 0 4px}h2{font-size:14px;color:#8B5CF6;text-transform:uppercase;" +
                    "letter-spacing:.06em;margin:24px 0 8px}p{margin:4px 0;color:#C7CEE0}" +
                    "table{width:100%;border-collapse:collapse;margin:6px 0}th,td{text-align:left;padding:6px 8px;" +
                    "border-bottom:1px solid #232B3B;font-size:13px}th{color:#8A93A6;font-weight:600}" +
                    ".muted{color:#5B6478}.big{font-size:18px;color:#fff}.badge{display:inline-block;padding:1px 7px;" +
                    "border-radius:6px;background:#171E2C;color:#8A93A6;font-size:11px;margin-left:6px}" +
                    ".ok{color:#22C55E}.warn{color:#F59E0B}.doubt{color:#7C8AA5}" +
                    ".foot{margin-top:28px;color:#5B6478;font-size:11px}</style>"
            )
            append("</head><body>")
            append("<h1>$title</h1>")
            append("<p class=\"muted\">JB OSINT · ${stamp.format(Date(System.currentTimeMillis()))} · шаблон «${template.title}»</p>")
            append(body)
            append("<div class=\"foot\">Данные собраны из открытых источников. Модули анонимизированы, реальные источники — в приложении B. " +
                "Совпадение не равно тождеству личности.</div>")
            append("</body></html>")
        }
        return html.toByteArray(Charsets.UTF_8)
    }

    private fun appendFindings(body: StringBuilder, findings: List<AgentFinding>, limit: Int) {
        if (findings.isEmpty()) {
            body.append("<p class=\"muted\">Данные не найдены.</p>")
            return
        }
        body.append("<table><thead><tr><th>Утверждение</th><th>Статус</th><th>Модуль</th></tr></thead><tbody>")
        findings.take(limit).forEach { finding ->
            val css = when (finding.status.name) {
                "CONFIRMED" -> "ok"
                "PROBABLE" -> "warn"
                else -> "doubt"
            }
            body.append("<tr><td>${esc(finding.text)}</td>")
            body.append("<td class=\"$css\">${esc(finding.status.title)}</td>")
            body.append("<td>${esc(finding.moduleLabel)}</td></tr>")
        }
        body.append("</tbody></table>")
    }

    private fun appendAssumptions(body: StringBuilder, assumptions: List<AgentAssumption>) {
        if (assumptions.isEmpty()) {
            body.append("<p class=\"muted\">Предположений нет.</p>")
            return
        }
        body.append("<ul>")
        assumptions.forEach {
            body.append("<li>${esc(it.text)}<div class=\"muted\">основание: ${esc(it.basis)}</div></li>")
        }
        body.append("</ul>")
    }

    private fun appendAppendix(body: StringBuilder, report: AgentReport) {
        body.append("<h2>Приложение B — реальные источники</h2>")
        if (report.modules.isEmpty()) {
            body.append("<p class=\"muted\">Источники не зафиксированы.</p>")
            return
        }
        body.append("<table><thead><tr><th>Обозначение</th><th>Источник</th><th>Адрес</th></tr></thead><tbody>")
        report.modules.forEach {
            body.append("<tr><td>${esc(it.label)}</td><td>${esc(it.realName)}</td><td>${esc(it.url)}</td></tr>")
        }
        body.append("</tbody></table>")
    }

    private fun isAutoFact(kind: String): Boolean = listOf(
        "VIN", "Госномер", "Марка", "Год", "Пробег", "Владел", "ДТП", "Залог",
        "Ремонт", "Цена", "Парков", "Фотограф", "Период владения"
    ).any { kind.contains(it, true) }

    private fun row(key: String, value: String): String =
        "<tr><th>${esc(key)}</th><td>${esc(value)}</td></tr>"

    private fun esc(value: String): String {
        val sb = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.isISOControl() && ch != '\n' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun fileName(report: AgentReport, template: AgentTemplate): String {
        val base = report.query.replace(Regex("[^A-Za-zА-Яа-я0-9_.-]"), "_").take(40).ifBlank { "agent" }
        val suffix = when (template) {
            AgentTemplate.QUICK -> "quick"
            AgentTemplate.DEEP -> "dossier"
            AgentTemplate.AUTO -> "auto"
            AgentTemplate.BUSINESS -> "business"
        }
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "agent_${base}_${suffix}_$date.html"
    }
}
