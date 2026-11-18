package ru.jbosint.osint.core.report

import ru.jbosint.osint.core.pdf.PdfDocument
import ru.jbosint.osint.core.pdf.PdfFontHandle
import ru.jbosint.osint.core.pdf.PdfPage
import ru.jbosint.osint.core.pdf.Rgb
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Отчёт по состоянию модулей: что проверено, чем ответил сервис, что делать.
 *
 * Отдельный документ, а не часть досье: он нужен при настройке, его читают
 * поддержка и сам аналитик. Секретов в нём нет по построению — сюда попадают
 * только маскированные адреса и тексты рекомендаций.
 */
data class ModuleReportRow(
    val number: Int,
    val title: String,
    val kind: String,
    /** Короткий статус: «работает», «ключ отклонён», «нет связи» … */
    val status: String,
    val code: Int,
    val facts: Int,
    val issues: String,
    val advice: String,
    val source: String,
    val steps: List<String> = emptyList()
)

data class ModuleReportData(
    val query: String,
    val appVersion: String,
    val android: String,
    val generatedAt: Long = System.currentTimeMillis(),
    val rows: List<ModuleReportRow>,
    /** Пары «показатель → значение» для блока сводки. */
    val stats: List<Pair<String, String>> = emptyList(),
    val notes: List<String> = emptyList()
)

class ModuleReport(
    private val data: ModuleReportData,
    private val fonts: ReportFonts? = null
) {

    private val stamp: String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(data.generatedAt))

    // ------------------------------------------------------------------- JSON

    fun buildJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"jb-osint/modules/1.0\",\n")
        sb.append("  \"query\": ").append(jsonString(data.query)).append(",\n")
        sb.append("  \"generated_at\": ").append(jsonString(stamp)).append(",\n")
        sb.append("  \"app_version\": ").append(jsonString(data.appVersion)).append(",\n")
        sb.append("  \"android\": ").append(jsonString(data.android)).append(",\n")
        sb.append("  \"statistics\": {")
        data.stats.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(",")
            sb.append("\n    ").append(jsonString(key)).append(": ").append(jsonString(value))
        }
        sb.append("\n  },\n")
        sb.append("  \"modules\": [")
        data.rows.forEachIndexed { index, row ->
            if (index > 0) sb.append(",")
            sb.append("\n    {")
            sb.append("\n      \"number\": ").append(row.number).append(",")
            sb.append("\n      \"title\": ").append(jsonString(row.title)).append(",")
            sb.append("\n      \"kind\": ").append(jsonString(row.kind)).append(",")
            sb.append("\n      \"status\": ").append(jsonString(row.status)).append(",")
            sb.append("\n      \"http_code\": ").append(row.code).append(",")
            sb.append("\n      \"facts\": ").append(row.facts).append(",")
            sb.append("\n      \"issues\": ").append(jsonString(row.issues)).append(",")
            sb.append("\n      \"advice\": ").append(jsonString(row.advice)).append(",")
            sb.append("\n      \"recommendation_source\": ").append(jsonString(row.source)).append(",")
            sb.append("\n      \"steps\": [")
            row.steps.forEachIndexed { stepIndex, step ->
                if (stepIndex > 0) sb.append(", ")
                sb.append(jsonString(step))
            }
            sb.append("]\n    }")
        }
        sb.append("\n  ],\n")
        sb.append("  \"notes\": [")
        data.notes.forEachIndexed { index, note ->
            if (index > 0) sb.append(", ")
            sb.append(jsonString(note))
        }
        sb.append("]\n}\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------- HTML

    fun buildHtml(anonymise: Boolean = false): String {
        val rows = data.rows.joinToString("\n") { row ->
            val title = if (anonymise) "Модуль ${row.number}" else row.title
            """
            <tr>
              <td class="num">${row.number}</td>
              <td><b>${html(title)}</b><div class="kind">${html(row.kind)}</div></td>
              <td><span class="status">${html(row.status)}</span><div class="kind">${html(codeText(row))}</div></td>
              <td>${html(row.advice)}${stepsHtml(row.steps)}</td>
              <td class="kind">${html(row.source)}</td>
            </tr>
            """.trimIndent()
        }
        val stats = data.stats.joinToString("") { (key, value) ->
            "<div class=\"stat\"><span>${html(key)}</span><b>${html(value)}</b></div>"
        }
        return """
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>JB OSINT — состояние модулей</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; padding: 28px; background: #0B0E14; color: #EDEFF5;
         font: 14px/1.55 Inter, -apple-system, Segoe UI, Roboto, sans-serif; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  .meta { color: #8A93A6; font-size: 12px; margin-bottom: 18px; }
  .stats { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 20px; }
  .stat { background: #151A24; border: 1px solid #232A38; border-radius: 10px;
          padding: 8px 12px; font-size: 12px; color: #8A93A6; }
  .stat b { display: block; color: #EDEFF5; font-size: 16px; margin-top: 2px; }
  table { width: 100%; border-collapse: collapse; }
  th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: .06em;
       color: #8A93A6; padding: 8px 10px; border-bottom: 1px solid #232A38; }
  td { vertical-align: top; padding: 10px; border-bottom: 1px solid #1B2130; }
  tr:nth-child(even) td { background: #10141D; }
  .num { color: #8A93A6; width: 28px; }
  .kind { color: #6B7280; font-size: 11px; }
  .status { color: #A5B4FC; }
  ul { margin: 6px 0 0 16px; padding: 0; }
  li { margin: 2px 0; color: #C9D0DC; }
  .notes { color: #8A93A6; font-size: 12px; margin-top: 18px; }
</style>
</head>
<body>
  <h1>JB OSINT — состояние модулей</h1>
  <div class="meta">${html(stamp)} · версия ${html(data.appVersion)} · ${html(data.android)}
    · тестовый запрос «${html(data.query)}» · ключи и токены заменены на ***</div>
  <div class="stats">$stats</div>
  <table>
    <thead><tr><th>№</th><th>Модуль</th><th>Состояние</th><th>Рекомендация</th><th>Источник</th></tr></thead>
    <tbody>
$rows
    </tbody>
  </table>
  <div class="notes">${data.notes.joinToString("<br>") { html(it) }}</div>
</body>
</html>
""".trimIndent()
    }

    // ------------------------------------------------------------------- XLSX

    fun buildXlsx(): ByteArray {
        val header = listOf("№", "Модуль", "Тип", "Состояние", "Код", "Фактов", "Замечания", "Рекомендация", "Шаги", "Источник")
        val widths = listOf(5f, 30f, 20f, 20f, 8f, 9f, 30f, 58f, 46f, 14f)
        val rows = ArrayList<List<String>>()
        rows += header
        data.rows.forEach { row ->
            rows += listOf(
                row.number.toString(),
                row.title,
                row.kind,
                row.status,
                if (row.code == 0) "—" else row.code.toString(),
                row.facts.toString(),
                row.issues,
                row.advice,
                row.steps.joinToString("\n") { "• $it" },
                row.source
            )
        }
        val statsRows = ArrayList<List<String>>()
        statsRows += listOf("Показатель", "Значение")
        data.stats.forEach { (key, value) -> statsRows += listOf(key, value) }
        statsRows += listOf("", "")
        statsRows += listOf("Отчёт", "JB OSINT — состояние модулей")
        statsRows += listOf("Сформирован", stamp)
        statsRows += listOf("Версия", data.appVersion)
        statsRows += listOf("Устройство", data.android)
        statsRows += listOf("Тестовый запрос", data.query)
        data.notes.forEach { statsRows += listOf("Примечание", it) }

        val sheets = listOf(
            Sheet("Модули", header.size, widths, rows),
            Sheet("Сводка", 2, listOf(30f, 70f), statsRows)
        )
        return buildBook(sheets)
    }

    private data class Sheet(
        val name: String,
        val columns: Int,
        val widths: List<Float>,
        val rows: List<List<String>>
    )

    private fun buildBook(sheets: List<Sheet>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes(sheets.size))
            entry("_rels/.rels", rootRels())
            entry("docProps/core.xml", coreProps())
            entry("docProps/app.xml", appProps())
            entry("xl/workbook.xml", workbook(sheets.map { it.name }))
            entry("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            entry("xl/styles.xml", styles())
            sheets.forEachIndexed { index, sheet ->
                entry("xl/worksheets/sheet${index + 1}.xml", sheetXml(sheet))
            }
        }
        return out.toByteArray()
    }

    private fun sheetXml(sheet: Sheet): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        val lastRow = sheet.rows.size.coerceAtLeast(1)
        sb.append("<dimension ref=\"A1:${columnName(sheet.columns)}$lastRow\"/>")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
        sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        sb.append("</sheetView></sheetViews>")
        sb.append("<cols>")
        sheet.widths.forEachIndexed { index, width ->
            sb.append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")
        sheet.rows.forEachIndexed { rowIndex, cells ->
            val r = rowIndex + 1
            sb.append("<row r=\"$r\">")
            cells.forEachIndexed { colIndex, value ->
                val style = if (rowIndex == 0) 1 else 2
                sb.append("<c r=\"${columnName(colIndex + 1)}$r\" t=\"inlineStr\" s=\"$style\">")
                sb.append("<is><t xml:space=\"preserve\">").append(escape(value)).append("</t></is></c>")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        sb.append("<autoFilter ref=\"A1:${columnName(sheet.columns)}1\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun contentTypes(sheetCount: Int): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            (1..sheetCount).joinToString("") {
                "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            } +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "</Types>"

    private fun rootRels(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>"

    private fun workbook(sheetNames: List<String>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
        sb.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<workbookPr/><bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>")
        sheetNames.forEachIndexed { index, name ->
            sb.append("<sheet name=\"${escape(name.take(31))}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
        }
        sb.append("</sheets></workbook>")
        return sb.toString()
    }

    private fun workbookRels(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..sheetCount) {
            sb.append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        sb.append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun styles(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"3\">" +
            "<font><sz val=\"10\"/><color rgb=\"FF262B33\"/><name val=\"Inter\"/></font>" +
            "<font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"Inter\"/></font>" +
            "<font><sz val=\"10\"/><color rgb=\"FF262B33\"/><name val=\"Inter\"/></font>" +
            "</fonts>" +
            "<fills count=\"3\">" +
            "<fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF0B0E14\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "</fills>" +
            "<borders count=\"2\">" +
            "<border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border><left/><right/><top/><bottom style=\"thin\"><color rgb=\"FFE4E7EC\"/></bottom><diagonal/></border>" +
            "</borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"3\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment horizontal=\"left\" vertical=\"center\" wrapText=\"1\"/></xf>" +
            "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"

    private fun coreProps(): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>JB OSINT — состояние модулей</dc:title>" +
            "<dc:creator>JB OSINT</dc:creator>" +
            "<cp:lastModifiedBy>JB OSINT</cp:lastModifiedBy>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified>" +
            "</cp:coreProperties>"
    }

    private fun appProps(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" " +
            "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" +
            "<Application>JB OSINT</Application>" +
            "<AppVersion>${escape(data.appVersion)}</AppVersion>" +
            "</Properties>"

    // -------------------------------------------------------------------- PDF

    fun buildPdf(): ByteArray {
        val set = fonts ?: ReportFonts.fromClasspath()
        val doc = PdfDocument()
        val regular = doc.registerFont(set.regular, "F1")
        val semibold = doc.registerFont(set.semibold, "F2")
        val bold = doc.registerFont(set.bold, "F3")

        val pageW = doc.pageWidth
        val pageH = doc.pageHeight
        val marginL = 56f
        val contentW = pageW - marginL * 2
        val top = 82f
        val bottom = pageH - 64f

        var page: PdfPage = doc.newPage()
        var y = top
        var pageNo = 1

        fun newPage() {
            page = doc.newPage()
            pageNo++
            y = top
        }

        fun ensure(space: Float) {
            if (y + space > bottom) newPage()
        }

        fun heading(text: String) {
            ensure(40f)
            page.text(marginL, y + 14f, 15f, semibold, text, ModulePalette.ink)
            y += 24f
            page.line(marginL, y - 6f, pageW - marginL, y - 6f, ModulePalette.line, 0.8f)
        }

        fun paragraph(text: String, size: Float = 10f, color: Rgb = ModulePalette.body, lead: Float = 4f) {
            if (text.isBlank()) return
            wrap(text, regular, size, contentW).forEach { line ->
                ensure(size + lead)
                page.text(marginL, y + size, size, regular, line, color)
                y += size + lead
            }
        }

        // Титул
        page.rect(0f, 0f, pageW, pageH, ModulePalette.dark)
        page.text(marginL, 210f, 11f, semibold, "JB OSINT", ModulePalette.accent2, tracking = 3f)
        page.text(marginL, 268f, 30f, bold, "Состояние модулей", ModulePalette.onDark)
        page.text(marginL, 306f, 13f, regular, "Проверка, локальный анализ и рекомендации", ModulePalette.onDarkMuted)
        page.line(marginL, 336f, marginL + 120f, 336f, ModulePalette.accent, 2f)
        page.text(marginL, 380f, 11f, regular, "Тестовый запрос: ${data.query}", ModulePalette.onDark)
        page.text(marginL, 402f, 11f, regular, "Сформирован: $stamp", ModulePalette.onDarkMuted)
        page.text(marginL, 424f, 11f, regular, "Версия приложения: ${data.appVersion}", ModulePalette.onDarkMuted)
        page.text(marginL, 446f, 11f, regular, data.android, ModulePalette.onDarkMuted)
        page.text(
            marginL, 520f, 10f, regular,
            "Ключи и токены в документ не попадают: адреса сервисов показаны без параметров авторизации.",
            ModulePalette.onDarkMuted
        )
        page.text(marginL, pageH - 70f, 9f, regular, "Конфиденциально · JB OSINT", ModulePalette.onDarkMuted)

        newPage()
        heading("Сводка")
        data.stats.forEach { (key, value) ->
            ensure(16f)
            page.text(marginL, y + 10f, 9.5f, regular, key, ModulePalette.muted)
            val width = regular.width(key, 9.5f)
            page.text(marginL + maxOf(190f, width + 12f), y + 10f, 10f, semibold, value, ModulePalette.ink)
            y += 16f
        }

        y += 10f
        heading("Модули")
        data.rows.forEach { row ->
            ensure(64f)
            page.text(marginL, y + 12f, 11.5f, semibold, "${row.number}. ${row.title}", ModulePalette.ink)
            val statusColor = when {
                row.status.startsWith("работает", ignoreCase = true) -> ModulePalette.green
                row.status.contains("ключ", ignoreCase = true) -> ModulePalette.amber
                row.status.contains("нет связи", ignoreCase = true) -> ModulePalette.slate
                else -> ModulePalette.amber
            }
            page.text(marginL, y + 27f, 9f, regular, "${row.status} · ${row.kind} · ${codeText(row)}", statusColor)
            y += 34f
            paragraph(row.advice, 9.5f)
            row.steps.forEach { step ->
                paragraph("• $step", 9f, ModulePalette.muted, 3f)
            }
            ensure(10f)
            page.line(marginL, y + 4f, pageW - marginL, y + 4f, ModulePalette.line, 0.6f)
            y += 14f
        }

        if (data.notes.isNotEmpty()) {
            y += 6f
            heading("Примечания")
            data.notes.forEach { paragraph(it, 9f, ModulePalette.muted) }
        }

        // Колонтитулы: номер страницы внизу каждой страницы.
        val total = doc.pages.size
        doc.pages.forEachIndexed { index, p ->
            p.text(marginL, pageH - 34f, 8.5f, regular, "JB OSINT · состояние модулей · $stamp", ModulePalette.faint)
            p.text(pageW - marginL - 34f, pageH - 34f, 8.5f, regular, "${index + 1} / $total", ModulePalette.faint)
        }

        val info = linkedMapOf(
            "Title" to "JB OSINT — состояние модулей",
            "Author" to "JB OSINT",
            "Subject" to data.query,
            "Creator" to "JB OSINT ${data.appVersion}",
            "Producer" to "JB OSINT PDF Engine"
        )
        return doc.write(info)
    }

    private fun wrap(text: String, handle: PdfFontHandle, size: Float, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        text.split('\n').forEach { chunk ->
            var current = StringBuilder()
            chunk.split(' ').forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (handle.width(candidate, size) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) lines += current.toString()
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
        }
        return lines
    }

    private fun codeText(row: ModuleReportRow): String = buildString {
        append(if (row.code == 0) "нет ответа" else "код ${row.code}")
        append(" · фактов ").append(row.facts)
        if (row.issues.isNotBlank()) append(" · ").append(row.issues)
    }

    private fun stepsHtml(steps: List<String>): String {
        if (steps.isEmpty()) return ""
        return "<ul>" + steps.joinToString("") { "<li>${html(it)}</li>" } + "</ul>"
    }

    private companion object {
        fun jsonString(value: String): String {
            val sb = StringBuilder("\"")
            value.forEach { ch ->
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
                }
            }
            return sb.append('"').toString()
        }

        fun escape(value: String): String {
            val sb = StringBuilder()
            value.forEach { ch ->
                when {
                    ch == '&' -> sb.append("&amp;")
                    ch == '<' -> sb.append("&lt;")
                    ch == '>' -> sb.append("&gt;")
                    ch == '"' -> sb.append("&quot;")
                    ch.isISOControl() && ch != '\n' && ch != '\t' -> sb.append(' ')
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun html(value: String): String = escape(value).replace("\n", "<br>")
    }
}

private object ModulePalette {
    val ink = Rgb.hex(0x14161C)
    val body = Rgb.hex(0x2B2F38)
    val muted = Rgb.hex(0x6B7280)
    val faint = Rgb.hex(0x9AA1AD)
    val line = Rgb.hex(0xE4E7EC)
    val accent = Rgb.hex(0x4F5BFF)
    val accent2 = Rgb.hex(0x8B5CF6)
    val dark = Rgb.hex(0x0B0E14)
    val onDark = Rgb.hex(0xEDEFF5)
    val onDarkMuted = Rgb.hex(0x8A93A6)
    val green = Rgb.hex(0x12885A)
    val amber = Rgb.hex(0xB45309)
    val slate = Rgb.hex(0x64748B)
}
