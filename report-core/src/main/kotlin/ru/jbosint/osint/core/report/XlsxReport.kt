package ru.jbosint.osint.core.report

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.ReportMeta
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Генератор Excel-книги (XLSX) без внешних зависимостей.
 * Листы: сводка, реестр, отдельные листы по категориям, связи, методология.
 * Готов к фильтрации и построению сводных таблиц.
 */
class XlsxReport(
    private val meta: ReportMeta,
    private val data: AggregatedData
) {
    private data class Cell(val value: String, val style: Int = 0, val numeric: Boolean = false)

    private data class Sheet(
        val name: String,
        val columns: List<Column>,
        val rows: List<List<Cell>>,
        val freeze: Boolean = true,
        val autoFilter: Boolean = true
    )

    private data class Column(val title: String, val width: Float)

    fun build(): ByteArray = buildSheets(listOf(
        summarySheet(),
        evidenceSheet(),
        registrySheet(),
        categorySheet(FactCategory.PERSON, "Люди"),
        categorySheet(FactCategory.CONTACT, "Контакты"),
        categorySheet(FactCategory.TECHNICAL, "Технические"),
        genericSheet("Документы", ReportSections.documentFacts(data)),
        genericSheet("Медиа", ReportSections.mediaFacts(data)),
        sourcesSheet(),
        linksSheet(),
        aiSheet(),
        methodologySheet()
    ))

    // ------------------------------------------------------------------ листы

    private fun summarySheet(): Sheet {
        val st = data.stats
        val rows = ArrayList<List<Cell>>()
        rows += listOf(Cell("ОТЧЁТ JB OSINT", 3))
        rows += listOf(Cell("Аналитическое исследование открытых источников", 4))
        rows += emptyRow(2)
        rows += listOf(Cell("Идентификатор отчёта", 4), Cell(meta.reportId))
        rows += listOf(Cell("Аналитик", 4), Cell(meta.analyst.ifBlank { "Не указан" }))
        rows += listOf(Cell("Дата формирования", 4), Cell(dateText(meta.createdAt)))
        rows += listOf(Cell("Объект запроса", 4), Cell(data.query))
        if (meta.queryType.isNotBlank()) rows += listOf(Cell("Тип запроса", 4), Cell(meta.queryType))
        if (meta.photoNote.isNotBlank()) rows += listOf(Cell("Поиск по фото", 4), Cell(meta.photoNote.take(1000)))
        rows += listOf(Cell("Версия системы", 4), Cell("JB OSINT ${meta.appVersion}"))
        rows += emptyRow(2)
        rows += listOf(Cell("ПОКАЗАТЕЛИ СЕССИИ", 2))
        rows += listOf(Cell("Модулей сбора задействовано", 4), Cell(st.sourcesAttempted.toString(), numeric = true))
        rows += listOf(Cell("Ответили корректно", 4), Cell(st.sourcesResponded.toString(), numeric = true))
        rows += listOf(Cell("Недоступны / отказ", 4), Cell(st.sourcesFailed.toString(), numeric = true))
        rows += listOf(Cell("Наблюдений получено", 4), Cell(st.rawFacts.toString(), numeric = true))
        rows += listOf(Cell("Уникальных сведений", 4), Cell(data.facts.size.toString(), numeric = true))
        rows += listOf(Cell("Устранено дубликатов", 4), Cell(st.duplicatesRemoved.toString(), numeric = true))
        rows += listOf(Cell("Связей построено", 4), Cell(data.links.size.toString(), numeric = true))
        rows += listOf(Cell("Длительность, с", 4), Cell(String.format(Locale.US, "%.1f", st.durationMs / 1000f), numeric = true))
        rows += emptyRow(2)
        rows += listOf(Cell("РАСПРЕДЕЛЕНИЕ ПО КАТЕГОРИЯМ", 2))
        rows += listOf(Cell("Категория", 1), Cell("Сведений", 1), Cell("Подтверждено", 1), Cell("Требует проверки", 1))
        for (category in FactCategory.entries) {
            val facts = data.factsOf(category)
            if (facts.isEmpty()) continue
            rows += listOf(
                Cell(category.title),
                Cell(facts.size.toString(), numeric = true),
                Cell(facts.count { it.confidence == Confidence.CONFIRMED }.toString(), numeric = true),
                Cell(facts.count { it.confidence == Confidence.UNVERIFIED }.toString(), numeric = true)
            )
        }
        rows += emptyRow(2)
        rows += listOf(Cell("ОЦЕНКА ДОСТОВЕРНОСТИ", 2))
        rows += listOf(Cell("Статус", 1), Cell("Критерий", 1), Cell("Фактов", 1))
        for (level in Confidence.entries.reversed()) {
            rows += listOf(
                Cell(level.title),
                Cell(level.note),
                Cell((data.confidenceCounts[level] ?: 0).toString(), numeric = true)
            )
        }
        return Sheet(
            name = "Сводка",
            columns = listOf(Column("Показатель", 34f), Column("Значение", 42f), Column("Доп. 1", 18f), Column("Доп. 2", 18f)),
            rows = rows,
            freeze = false,
            autoFilter = false
        )
    }

    private fun registrySheet(): Sheet {
        val rows = data.facts.mapIndexed { index, fact ->
            listOf(
                Cell((index + 1).toString(), numeric = true),
                Cell(fact.category.title),
                Cell(fact.kind),
                Cell(fact.value),
                Cell(fact.subject.ifBlank { "—" }),
                Cell(fact.attributes.entries.joinToString("; ") { "${it.key}: ${it.value}" }),
                Cell(ReportSections.sourceNames(fact).ifBlank { "Поиск JB OSINT" }),
                Cell(ReportSections.evidenceUrl(fact)),
                Cell(fact.sourceCount.toString(), numeric = true),
                Cell(fact.observations.toString(), numeric = true),
                Cell(fact.confidence.title, styleForConfidence(fact.confidence))
            )
        }
        return Sheet(
            name = "Реестр",
            columns = listOf(
                Column("№", 6f),
                Column("Категория", 20f),
                Column("Сведение", 22f),
                Column("Значение", 38f),
                Column("Субъект", 26f),
                Column("Атрибуты", 40f),
                Column("Источник", 22f),
                Column("Ссылка", 46f),
                Column("Источников", 11f),
                Column("Наблюдений", 11f),
                Column("Достоверность", 18f)
            ),
            rows = rows
        )
    }

    private fun categorySheet(category: FactCategory, sheetName: String): Sheet {
        val facts = data.factsOf(category)
        val rows = facts.mapIndexed { index, fact ->
            listOf(
                Cell((index + 1).toString(), numeric = true),
                Cell(fact.kind),
                Cell(fact.value),
                Cell(fact.subject.ifBlank { "—" }),
                Cell(fact.sourceCount.toString(), numeric = true),
                Cell(fact.confidence.title, styleForConfidence(fact.confidence))
            )
        }
        return Sheet(
            name = sheetName,
            columns = listOf(
                Column("№", 6f),
                Column("Сведение", 24f),
                Column("Значение", 42f),
                Column("Субъект", 26f),
                Column("Источников", 11f),
                Column("Достоверность", 18f)
            ),
            rows = rows
        )
    }

    /** Лист «Доказательства»: строка на каждое сведение — источник, ссылка, дата, статус, примечание. */
    private fun evidenceSheet(): Sheet {
        val rows = data.facts.mapIndexed { index, fact ->
            listOf(
                Cell((index + 1).toString(), numeric = true),
                Cell(fact.category.title),
                Cell(fact.kind),
                Cell(fact.value),
                Cell(ReportSections.sourceNames(fact).ifBlank { "Поиск JB OSINT" }),
                Cell(ReportSections.evidenceUrl(fact)),
                Cell(ReportSections.dateText(fact.firstSeen)),
                Cell(fact.confidence.title, styleForConfidence(fact.confidence)),
                Cell(ReportSections.statusLine(fact, data)),
                Cell(ReportSections.noteFor(fact, meta))
            )
        }
        return Sheet(
            name = "Доказательства",
            columns = listOf(
                Column("№", 6f),
                Column("Категория", 18f),
                Column("Сведение", 20f),
                Column("Значение", 34f),
                Column("Источник", 20f),
                Column("Ссылка", 46f),
                Column("Дата", 12f),
                Column("Статус", 14f),
                Column("Комментарий статуса", 34f),
                Column("Примечание", 44f)
            ),
            rows = rows
        )
    }

    /** Универсальный лист для документов и медиа. */
    private fun genericSheet(sheetName: String, facts: List<ru.jbosint.osint.core.model.Fact>): Sheet {
        val rows = facts.mapIndexed { index, fact ->
            listOf(
                Cell((index + 1).toString(), numeric = true),
                Cell(fact.kind),
                Cell(fact.value),
                Cell(ReportSections.sourceNames(fact).ifBlank { "Поиск JB OSINT" }),
                Cell(ReportSections.evidenceUrl(fact)),
                Cell(ReportSections.dateText(fact.firstSeen)),
                Cell(fact.confidence.title, styleForConfidence(fact.confidence))
            )
        }
        return Sheet(
            name = sheetName,
            columns = listOf(
                Column("№", 6f),
                Column("Тип", 20f),
                Column("Значение / адрес", 52f),
                Column("Источник", 22f),
                Column("Ссылка", 46f),
                Column("Дата", 12f),
                Column("Статус", 16f)
            ),
            rows = rows
        )
    }

    /** Лист «Источники»: модули сбора и собранные ссылки-доказательства. */
    private fun sourcesSheet(): Sheet {
        val rows = ArrayList<List<Cell>>()
        val refs = data.sources.ifEmpty { SourceCatalog.forFacts(data.facts) }
        rows += listOf(Cell("МОДУЛИ СБОРА", 2), Cell(""), Cell(""))
        rows += listOf(Cell("№", 1), Cell("Модуль", 1), Cell("Сведений", 1), Cell("Ресурс", 1))
        refs.forEachIndexed { i, src ->
            rows += listOf(
                Cell((i + 1).toString(), numeric = true),
                Cell(src.name),
                Cell(data.facts.count { src.id in it.sourceIds }.toString(), numeric = true),
                Cell(src.url)
            )
        }
        val links = data.facts.flatMap { f -> f.urls.map { url -> f.kind to url } }.distinct()
        if (links.isNotEmpty()) {
            rows += emptyRow(2)
            rows += listOf(Cell("ССЫЛКИ-ДОКАЗАТЕЛЬСТВА", 2), Cell(""), Cell(""))
            rows += listOf(Cell("№", 1), Cell("Сведение", 1), Cell("Ссылка", 1))
            links.take(1000).forEachIndexed { i, (kind, url) ->
                rows += listOf(Cell((i + 1).toString(), numeric = true), Cell(kind), Cell(url))
            }
        }
        return Sheet(
            name = "Источники",
            columns = listOf(Column("№", 6f), Column("Источник", 30f), Column("Сведений", 12f), Column("Адрес", 70f)),
            rows = rows,
            freeze = true,
            autoFilter = false
        )
    }

    private fun linksSheet(): Sheet {
        val rows = data.links.mapIndexed { index, link ->
            listOf(
                Cell((index + 1).toString(), numeric = true),
                Cell(link.from),
                Cell(link.to),
                Cell(link.relation.replaceFirstChar { it.uppercase() }),
                Cell(link.weight.toString(), numeric = true),
                Cell(link.sourceCount.toString(), numeric = true)
            )
        }
        return Sheet(
            name = "Связи",
            columns = listOf(
                Column("№", 6f),
                Column("Объект A", 30f),
                Column("Объект B", 30f),
                Column("Тип связи", 34f),
                Column("Наблюдений", 12f),
                Column("Подтверждений", 14f)
            ),
            rows = rows
        )
    }

    /** Лист с выводами интеллектуального ассистента: раздел + текст по абзацам. */
    private fun aiSheet(): Sheet {
        val analysis = meta.aiAnalysis.trim()
        val narrative = meta.aiNarrative.trim()
        val rows = ArrayList<List<Cell>>()
        if (analysis.isNotBlank()) {
            rows += listOf(Cell("Выводы по цифровым следам", 2), Cell(""))
            analysis.split('\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                rows += listOf(Cell(""), Cell(line.take(3000)))
            }
        }
        if (narrative.isNotBlank()) {
            if (rows.isNotEmpty()) rows += emptyRow(2)
            rows += listOf(Cell("Оформленное резюме исследования", 2), Cell(""))
            narrative.split('\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                rows += listOf(Cell(""), Cell(line.take(3000)))
            }
        }
        return Sheet(
            name = "ИИ-заключение",
            columns = listOf(Column("Раздел", 30f), Column("Содержание", 110f)),
            rows = rows,
            freeze = true,
            autoFilter = false
        )
    }

    private fun methodologySheet(): Sheet {
        val paragraphs = listOf(
            "МЕТОДОЛОГИЯ ИССЛЕДОВАНИЯ",
            "0.1.1. Сбор и нормализация. Запрос распределён между активными модулями сбора; значения приведены к единому виду, дубликаты отброшены.",
            "0.1.2. Триангуляция данных. Совпадение значения в трёх и более независимых источниках — «Подтверждено»; в двух — «Вероятно»; в одном — «Требует проверки».",
            "0.1.3. Перекрёстная проверка (cross-referencing). Выявлены пересечения идентификаторов между объектами, образующие связи (лист «Связи»).",
            "0.1.4. Анализ метаданных. Для технических артефактов использованы регистрационные данные доменов, DNS, сетевая принадлежность IP и метаданные изображений.",
            "5.3.1. Ограничения: сведения отражают состояние открытых источников на момент сбора; документ не является юридическим заключением.",
            "Сформировано в JB OSINT · ${meta.reportId} · ${dateText(meta.createdAt)}"
        )
        val rows = paragraphs.map { listOf(Cell(it, if (it == paragraphs.first()) 3 else 2)) }
        return Sheet(
            name = "Методология",
            columns = listOf(Column("Описание", 120f)),
            rows = rows,
            freeze = false,
            autoFilter = false
        )
    }

    private fun styleForConfidence(confidence: Confidence): Int = when (confidence) {
        Confidence.CONFIRMED -> 5
        Confidence.PROBABLE -> 2
        Confidence.UNVERIFIED -> 2
    }

    private fun emptyRow(columns: Int): List<Cell> = List(columns) { Cell("") }

    private fun dateText(ts: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(if (ts == 0L) System.currentTimeMillis() else ts))

    // ------------------------------------------------------------- упаковка xlsx

    private fun buildSheets(sheets: List<Sheet>): ByteArray {
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
        val lastCol = columnName(sheet.columns.size)
        sb.append("<dimension ref=\"A1:$lastCol$lastRow\"/>")
        sb.append("<sheetViews><sheetView tabSelected=\"0\" workbookViewId=\"0\">")
        if (sheet.freeze) {
            sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        }
        sb.append("</sheetView></sheetViews>")
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        sb.append("<cols>")
        sheet.columns.forEachIndexed { index, column ->
            sb.append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"${column.width}\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")

        var rowIndex = 1
        if (sheet.freeze) {
            sb.append("<row r=\"$rowIndex\" ht=\"26\" customHeight=\"1\">")
            sheet.columns.forEachIndexed { colIndex, column ->
                sb.append(cellXml("${columnName(colIndex + 1)}$rowIndex", Cell(column.title, 1)))
            }
            sb.append("</row>")
            rowIndex++
        }

        for (row in sheet.rows) {
            sb.append("<row r=\"$rowIndex\">")
            row.forEachIndexed { colIndex, cell ->
                if (cell.value.isNotEmpty() || cell.style != 0) {
                    sb.append(cellXml("${columnName(colIndex + 1)}$rowIndex", cell))
                }
            }
            sb.append("</row>")
            rowIndex++
        }
        sb.append("</sheetData>")
        if (sheet.autoFilter && lastRow > 1) {
            sb.append("<autoFilter ref=\"A1:$lastCol$lastRow\"/>")
        }
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun cellXml(ref: String, cell: Cell): String {
        val style = if (cell.style == 0) "" else " s=\"${cell.style}\""
        return if (cell.numeric && cell.value.isNotEmpty()) {
            "<c r=\"$ref\"$style><v>${cell.value}</v></c>"
        } else {
            "<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(cell.value)}</t></is></c>"
        }
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

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
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

    private fun contentTypes(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in 1..sheetCount) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        sb.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        sb.append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        sb.append("</Types>")
        return sb.toString()
    }

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
        sb.append("<workbookPr/>")
        sb.append("<bookViews><workbookView activeTab=\"0\"/></bookViews>")
        sb.append("<sheets>")
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
            "<fonts count=\"5\">" +
            "<font><sz val=\"10\"/><color rgb=\"FF262B33\"/><name val=\"Inter\"/></font>" +
            "<font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"Inter\"/></font>" +
            "<font><b/><sz val=\"10\"/><color rgb=\"FF14161C\"/><name val=\"Inter\"/></font>" +
            "<font><b/><sz val=\"15\"/><color rgb=\"FF14161C\"/><name val=\"Inter\"/></font>" +
            "<font><sz val=\"9\"/><color rgb=\"FF6B7280\"/><name val=\"Inter\"/></font>" +
            "</fonts>" +
            "<fills count=\"4\">" +
            "<fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF0B0E14\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEEF0FF\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "</fills>" +
            "<borders count=\"2\">" +
            "<border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border><left/><right/><top/><bottom style=\"thin\"><color rgb=\"FFE4E7EC\"/></bottom><diagonal/></border>" +
            "</borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"6\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment horizontal=\"left\" vertical=\"center\" wrapText=\"1\"/></xf>" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
            "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"

    private fun coreProps(): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>JB OSINT — отчёт об исследовании</dc:title>" +
            "<dc:creator>${escape(meta.analyst.ifBlank { "JB OSINT" })}</dc:creator>" +
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
            "<AppVersion>${meta.appVersion}</AppVersion>" +
            "</Properties>"
}
