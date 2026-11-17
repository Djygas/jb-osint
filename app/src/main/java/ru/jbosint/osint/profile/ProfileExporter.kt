package ru.jbosint.osint.profile

import ru.jbosint.osint.core.report.Csv
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Выгрузки профиля: сообщения, круг общения, идентификаторы, GraphML.
 *
 * Все файлы содержат дисклеймер «Данные собраны из открытых источников»;
 * при включённой анонимизации имена и ID заменяются на «Объект», «Контакт 1»…
 * XLSX собирается собственным минимальным писателем (пакет OOXML), чтобы
 * не тянуть в приложение сторонние библиотеки.
 */
object ProfileExporter {

    const val DISCLAIMER = "Данные собраны из открытых источников. Отчёт носит информационный характер."

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
    private val day = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))
    private val time = SimpleDateFormat("HH:mm:ss", Locale("ru"))

    // ------------------------------------------------------------ имена файлов

    fun baseName(profile: ProfileData): String {
        val handle = profile.subject.username.ifBlank {
            profile.subject.id.ifBlank { profile.query }
        }.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "profile" }
        return "profile_${handle}_${stamp.format(Date(profile.builtAt))}"
    }

    fun messagesName(profile: ProfileData, extension: String) = "${baseName(profile)}_messages.$extension"

    fun contactsName(profile: ProfileData, extension: String) = "${baseName(profile)}_audience.$extension"

    fun graphName(profile: ProfileData) = "${baseName(profile)}_graph.graphml"

    // ------------------------------------------------------------ CSV

    fun messagesCsv(profile: ProfileData, anonymize: Boolean = false): ByteArray {
        val rows = ArrayList<List<String>>()
        rows += listOf("Дата", "Время", "Отправитель", "Текст", "Тип", "Кому ответ", "Ссылка")
        profile.messages.forEachIndexed { index, message ->
            rows += listOf(
                day.format(Date(message.at)),
                time.format(Date(message.at)),
                if (anonymize) "Пользователь ${index % 7 + 1}" else message.author,
                message.text,
                message.type.title,
                anonymize(message.replyTo, anonymize),
                message.url
            )
        }
        rows.add(emptyList())
        rows.add(listOf(DISCLAIMER))
        return (Csv.BOM + Csv.of(rows)).toByteArray(Charsets.UTF_8)
    }

    fun contactsCsv(profile: ProfileData, anonymize: Boolean = false): ByteArray {
        val rows = ArrayList<List<String>>()
        rows += listOf("Контакт", "Ник", "ID", "Сообщений", "Реплаев", "Последняя активность", "Бот", "Ссылка")
        profile.contacts.forEachIndexed { index, contact ->
            rows += listOf(
                if (anonymize) "Контакт ${index + 1}" else contact.name,
                anonymize(contact.username, anonymize),
                anonymize(contact.id, anonymize),
                contact.messages.toString(),
                contact.replies.toString(),
                if (contact.lastAt > 0) day.format(Date(contact.lastAt)) else "",
                if (contact.bot) "да" else "нет",
                contact.link
            )
        }
        rows.add(emptyList())
        rows.add(listOf(DISCLAIMER))
        return (Csv.BOM + Csv.of(rows)).toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------ HTML-досье

    /** Текстовое досье по шаблону: «Досье на человека», «Анализ активности», «Круг общения». */
    fun dossierHtml(
        profile: ProfileData,
        template: DossierTemplate = DossierTemplate.PERSON,
        anonymize: Boolean = false
    ): ByteArray {
        val title = if (anonymize) "Объект" else profile.subject.title
        fun row(key: String, value: String) =
            "<tr><th>$key</th><td>${escapeXml(anonymize(value, anonymize))}</td></tr>"

        val body = StringBuilder()
        when (template) {
            DossierTemplate.PERSON -> {
                body.append("<h2>Карточка объекта</h2><table><tbody>")
                body.append(row("Имя", profile.subject.title))
                body.append(row("Никнейм", profile.subject.username.let { if (it.isBlank()) "—" else "@$it" }))
                body.append(row("Telegram ID", profile.subject.id.ifBlank { "—" }))
                body.append(row("Ссылка", profile.subject.link.ifBlank { "—" }))
                body.append(row("Статус", profile.subject.status.title))
                body.append(row("Полнота профиля", "${profile.completeness}%"))
                body.append("</tbody></table>")
                body.append("<h2>Идентификаторы</h2>")
                if (profile.identifiers.isEmpty()) body.append("<p class=\"muted\">Данные не найдены.</p>")
                else {
                    body.append("<table><thead><tr><th>Тип</th><th>Значение</th><th>Платформа</th><th>Достоверность</th></tr></thead><tbody>")
                    profile.identifiers.forEach {
                        body.append("<tr><td>${escapeXml(it.kind)}</td><td>${escapeXml(anonymize(it.value, anonymize))}</td>")
                        body.append("<td>${escapeXml(it.platform)}</td><td>${escapeXml(it.confidence.title)}</td></tr>")
                    }
                    body.append("</tbody></table>")
                }
                body.append("<h2>История имён</h2>")
                if (profile.names.isEmpty()) body.append("<p class=\"muted\">Данные не найдены.</p>")
                else profile.names.forEach {
                    body.append("<p>${escapeXml(it.date.ifBlank { "—" })} → <b>${escapeXml(anonymize(it.name, anonymize))}</b></p>")
                }
                body.append("<h2>Репутация</h2><table><tbody>")
                body.append(row("Индекс токсичности", "${profile.reputation.toxicity}%"))
                body.append(row("Индекс спама", "${profile.reputation.spam}%"))
                body.append(row("Доверие", "${profile.reputation.trust}%"))
                body.append("</tbody></table>")
                if (profile.reputation.notes.isNotEmpty()) {
                    body.append("<ul>").append(profile.reputation.notes.joinToString("") { "<li>${escapeXml(it)}</li>" }).append("</ul>")
                }
            }
            DossierTemplate.ACTIVITY -> {
                body.append("<h2>Сводка активности</h2><table><tbody>")
                body.append(row("Сообщений", profile.summary.messages.toString()))
                body.append(row("Медиа", profile.summary.media.toString()))
                body.append(row("Реплаев", profile.summary.replies.toString()))
                body.append(row("Период", if (profile.summary.hasRange)
                    "${ProfileBuilder.dateText(profile.summary.from)} — ${ProfileBuilder.dateText(profile.summary.to)}"
                else "нет данных"))
                body.append(row("География", profile.summary.geo.ifBlank { "нет данных" }))
                body.append("</tbody></table>")
                body.append("<h2>Типы контента</h2><table><tbody>")
                body.append(row("Текст", "${profile.mix.text}%"))
                body.append(row("Медиа", "${profile.mix.media}%"))
                body.append(row("Реплаи", "${profile.mix.replies}%"))
                body.append(row("Кружки", "${profile.mix.rounds}%"))
                body.append(row("Голосовые", "${profile.mix.voice}%"))
                body.append(row("Ссылки", "${profile.mix.links}%"))
                body.append("</tbody></table>")
                body.append("<h2>Поведение</h2><table><tbody>")
                body.append(row("Пик активности", if (profile.behavior.heatmap.peakHour >= 0)
                    "${profile.behavior.heatmap.peakHour}:00" else "нет данных"))
                body.append(row("Доля ночных сообщений", "${profile.behavior.heatmap.nightShare}%"))
                body.append(row("Средняя пауза", "${profile.behavior.heatmap.avgGapMinutes} мин"))
                body.append(row("Максимальная пауза", "${profile.behavior.heatmap.longestPauseHours} ч"))
                body.append(row("Тональность", profile.behavior.sentiment.tone))
                body.append("</tbody></table>")
                if (profile.behavior.topics.isNotEmpty()) {
                    body.append("<h2>Темы обсуждений</h2><table><thead><tr><th>Тема</th><th>Сообщений</th><th>Доля</th></tr></thead><tbody>")
                    profile.behavior.topics.forEach {
                        body.append("<tr><td>${escapeXml(it.name)}</td><td>${it.count}</td><td>${it.share}%</td></tr>")
                    }
                    body.append("</tbody></table>")
                }
                if (profile.content.links.isNotEmpty()) {
                    body.append("<h2>Ссылки</h2><ul>")
                    profile.content.links.take(20).forEach {
                        body.append("<li>${escapeXml(it.domain)} — ${it.count}×</li>")
                    }
                    body.append("</ul>")
                }
            }
            DossierTemplate.CIRCLE -> {
                body.append("<h2>Круг общения</h2>")
                if (profile.contacts.isEmpty()) body.append("<p class=\"muted\">Данные не найдены.</p>")
                else {
                    body.append("<table><thead><tr><th>Контакт</th><th>Ник</th><th>Сообщений</th><th>Реплаев</th></tr></thead><tbody>")
                    profile.contacts.take(60).forEachIndexed { index, contact ->
                        val name = if (anonymize) "Контакт ${index + 1}" else contact.name
                        body.append("<tr><td>${escapeXml(name)}</td>")
                        body.append("<td>${escapeXml(anonymize(contact.username, anonymize))}</td>")
                        body.append("<td>${contact.messages}</td><td>${contact.replies}</td></tr>")
                    }
                    body.append("</tbody></table>")
                }
                body.append("<h2>Группы и каналы</h2>")
                if (profile.groups.isEmpty()) body.append("<p class=\"muted\">Данные не найдены.</p>")
                else body.append("<ul>").append(profile.groups.joinToString("") { "<li>${escapeXml(it)}</li>" }).append("</ul>")
            }
        }

        val html = buildString {
            append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>${escapeXml(template.title)} — ${escapeXml(title)}</title>")
            append(
                "<style>body{background:#0B0E14;color:#EDEFF5;font-family:Inter,Arial,sans-serif;" +
                    "margin:0;padding:24px}h1{font-size:22px;margin:0 0 4px}h2{font-size:15px;color:#8B5CF6;" +
                    "text-transform:uppercase;letter-spacing:.06em;margin:24px 0 8px}p{margin:4px 0;color:#C7CEE0}" +
                    "table{width:100%;border-collapse:collapse;margin:6px 0}th,td{text-align:left;padding:6px 8px;" +
                    "border-bottom:1px solid #232B3B;font-size:13px}th{color:#8A93A6;font-weight:600}" +
                    ".muted{color:#5B6478}.foot{margin-top:28px;color:#5B6478;font-size:11px}</style>"
            )
            append("</head><body>")
            append("<h1>${escapeXml(template.title)}</h1>")
            append("<p class=\"muted\">${escapeXml(title)} · ${ProfileBuilder.dateText(profile.builtAt)}</p>")
            append(body)
            append("<div class=\"foot\">${escapeXml(DISCLAIMER)}</div>")
            append("</body></html>")
        }
        return html.toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------ XLSX

    private class Sheet(val name: String, val columns: List<Pair<String, Int>>, val rows: List<List<String>>)

    fun xlsx(profile: ProfileData, anonymize: Boolean = false): ByteArray {
        val sheets = listOf(
            summarySheet(profile, anonymize),
            messagesSheet(profile, anonymize),
            audienceSheet(profile, anonymize),
            identifiersSheet(profile, anonymize),
            methodSheet(profile)
        )
        return zipXlsx(sheets)
    }

    private fun summarySheet(profile: ProfileData, anonymize: Boolean): Sheet {
        val rows = ArrayList<List<String>>()
        fun row(key: String, value: String) { rows += listOf(key, value) }
        row("Запрос", anonymize(profile.query, anonymize))
        row("Объект", if (anonymize) "Объект" else profile.subject.title)
        row("Никнейм", anonymize(profile.subject.username, anonymize))
        row("Telegram ID", anonymize(profile.subject.id, anonymize))
        row("Ссылка", profile.subject.link)
        row("Статус", profile.subject.status.title)
        row("Сообщений", profile.summary.messages.toString())
        row("Медиа", profile.summary.media.toString())
        row("Реплаев", profile.summary.replies.toString())
        row("Контактов", profile.summary.contacts.toString())
        row("Групп", profile.summary.groups.toString())
        row("Период", if (profile.summary.hasRange) {
            "${day.format(Date(profile.summary.from))} — ${day.format(Date(profile.summary.to))}"
        } else "нет данных")
        row("География", profile.summary.geo.ifBlank { "нет данных" })
        row("Индекс токсичности", "${profile.reputation.toxicity}%")
        row("Индекс спама", "${profile.reputation.spam}%")
        row("Доверие", "${profile.reputation.trust}%")
        row("Полнота профиля", "${profile.completeness}%")
        row("Источники", profile.sources.joinToString(", ").ifBlank { "нет данных" })
        row("Собрано", day.format(Date(profile.builtAt)))
        row("Дисклеймер", DISCLAIMER)
        val events = ArrayList<List<String>>()
        profile.names.forEach { events += listOf("Смена имени", it.date, it.name) }
        profile.groups.forEach { events += listOf("Группа/канал", "", it) }
        profile.identifiers.forEach { events += listOf(it.kind, it.platform, it.value) }
        val all = rows.map { listOf(it[0], it[1], "") } + listOf(listOf("", "", "")) +
            listOf(listOf("СОБЫТИЯ И ИДЕНТИФИКАТОРЫ", "", "")) + events
        return Sheet(
            "Сводка",
            listOf("Показатель" to 38, "Значение" to 44, "Доп." to 24),
            all
        )
    }

    private fun messagesSheet(profile: ProfileData, anonymize: Boolean): Sheet {
        val rows = profile.messages.mapIndexed { index, message ->
            listOf(
                day.format(Date(message.at)),
                time.format(Date(message.at)),
                if (anonymize) "Пользователь ${index % 7 + 1}" else message.author,
                message.text,
                message.type.title,
                if (anonymize) "" else message.replyTo,
                message.url
            )
        }
        return Sheet(
            "Сообщения",
            listOf("Дата" to 12, "Время" to 10, "Отправитель" to 20, "Текст" to 60,
                "Тип" to 12, "Кому ответ" to 20, "Ссылка" to 32),
            rows
        )
    }

    private fun audienceSheet(profile: ProfileData, anonymize: Boolean): Sheet {
        val rows = profile.contacts.mapIndexed { index, contact ->
            listOf(
                if (anonymize) "Контакт ${index + 1}" else contact.name,
                anonymize(contact.username, anonymize),
                anonymize(contact.id, anonymize),
                contact.messages.toString(),
                contact.replies.toString(),
                if (contact.lastAt > 0) day.format(Date(contact.lastAt)) else "",
                if (contact.bot) "да" else "нет",
                contact.link
            )
        }
        return Sheet(
            "Круг общения",
            listOf("Контакт" to 22, "Ник" to 18, "ID" to 14, "Сообщений" to 12,
                "Реплаев" to 10, "Активность" to 14, "Бот" to 8, "Ссылка" to 30),
            rows
        )
    }

    private fun identifiersSheet(profile: ProfileData, anonymize: Boolean): Sheet {
        val rows = profile.identifiers.map { id ->
            listOf(
                id.kind,
                anonymize(id.value, anonymize),
                id.platform,
                id.confidence.title,
                id.evidence
            )
        } + listOf(listOf("", "", "", "", ""), listOf(DISCLAIMER, "", "", "", ""))
        return Sheet(
            "Идентификаторы",
            listOf("Тип" to 16, "Значение" to 34, "Платформа" to 16, "Достоверность" to 18, "Доказательство" to 40),
            rows
        )
    }

    private fun methodSheet(profile: ProfileData): Sheet {
        val rows = ArrayList<List<String>>()
        rows += listOf("Методология")
        rows += listOf("Профиль собран из модулей приложения; каждый факт в отчёте сопровождается источником.")
        rows += listOf("Репутационные индексы рассчитываются по словарям тональности и повторам сообщений.")
        rows += listOf("Если модуль не вернул данные, блок помечен как «нет данных» — значения не додумываются.")
        rows += listOf("")
        rows += listOf("Источники: " + profile.sources.joinToString(", ").ifBlank { "нет данных" })
        rows += listOf("Не найдено: " + profile.missing.joinToString(", ").ifBlank { "—" })
        rows += listOf("")
        rows += listOf(DISCLAIMER)
        return Sheet("Методология", listOf("Описание" to 110), rows)
    }

    // ------------------------------------------------------------ GraphML

    /** Социальный граф в GraphML — открывается в Gephi и yEd. */
    fun graphMl(profile: ProfileData, anonymize: Boolean = false): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\" ")
        sb.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        sb.append("xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns ")
        sb.append("http://graphml.graphdrawing.org/xmlns/1.0/graphml.xsd\">\n")
        sb.append("  <key id=\"label\" for=\"node\" attr.name=\"label\" attr.type=\"string\"/>\n")
        sb.append("  <key id=\"weight\" for=\"node\" attr.name=\"weight\" attr.type=\"int\"/>\n")
        sb.append("  <key id=\"center\" for=\"node\" attr.name=\"center\" attr.type=\"boolean\"/>\n")
        sb.append("  <key id=\"relation\" for=\"edge\" attr.name=\"relation\" attr.type=\"string\"/>\n")
        sb.append("  <key id=\"eweight\" for=\"edge\" attr.name=\"weight\" attr.type=\"int\"/>\n")
        sb.append("  <graph id=\"jb-osint\" edgedefault=\"undirected\">\n")
        val names = LinkedHashMap<String, String>()
        profile.nodes.forEachIndexed { index, node ->
            val alias = if (!anonymize) node.label else if (node.center) "Объект" else "Узел ${index}"
            names[node.id] = alias
            sb.append("    <node id=\"${escapeXml(node.id)}\">\n")
            sb.append("      <data key=\"label\">${escapeXml(alias)}</data>\n")
            sb.append("      <data key=\"weight\">${node.weight}</data>\n")
            sb.append("      <data key=\"center\">${node.center}</data>\n")
            sb.append("    </node>\n")
        }
        profile.edges.forEach { edge ->
            if (names[edge.from] == null || names[edge.to] == null) return@forEach
            sb.append("    <edge source=\"${escapeXml(edge.from)}\" target=\"${escapeXml(edge.to)}\">\n")
            sb.append("      <data key=\"relation\">${escapeXml(edge.relation)}</data>\n")
            sb.append("      <data key=\"eweight\">${edge.weight}</data>\n")
            sb.append("    </edge>\n")
        }
        sb.append("  </graph>\n")
        sb.append("  <!-- $DISCLAIMER -->\n")
        sb.append("</graphml>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------ XLSX writer

    private val headers = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")

    private fun zipXlsx(sheets: List<Sheet>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes(sheets.size))
            entry("_rels/.rels", rootRels())
            entry("xl/workbook.xml", workbook(sheets.map { it.name }))
            entry("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            entry("xl/styles.xml", STYLES)
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
        val lastRow = sheet.rows.size + 1
        val lastCol = headers.getOrElse(sheet.columns.size - 1) { "J" }
        sb.append("<dimension ref=\"A1:$lastCol$lastRow\"/>")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
        sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        sb.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>")
        sheet.columns.forEachIndexed { index, column ->
            sb.append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"${column.second}\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")
        sb.append("<row r=\"1\" ht=\"24\" customHeight=\"1\">")
        sheet.columns.forEachIndexed { index, column ->
            sb.append(cell("${headers[index]}1", column.first, 1))
        }
        sb.append("</row>")
        sheet.rows.forEachIndexed { rowIndex, row ->
            val r = rowIndex + 2
            sb.append("<row r=\"$r\">")
            row.forEachIndexed { colIndex, value ->
                if (value.isNotEmpty()) sb.append(cell("${headers.getOrElse(colIndex) { "J" }}$r", value, 2))
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun cell(ref: String, value: String, style: Int): String =
        "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(value)}</t></is></c>"

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
        sb.append("</Types>")
        return sb.toString()
    }

    private fun rootRels(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private fun workbook(names: List<String>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
        sb.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheets>")
        names.forEachIndexed { index, name ->
            sb.append("<sheet name=\"${escapeXml(name.take(31))}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
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

    private val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\">" +
            "<font><sz val=\"10\"/><color rgb=\"FFE8EAF0\"/><name val=\"Inter\"/></font>" +
            "<font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"Inter\"/></font>" +
            "</fonts>" +
            "<fills count=\"3\">" +
            "<fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF27306B\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "</fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"3\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">" +
            "<alignment vertical=\"top\" wrapText=\"1\"/></xf>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"

    // ------------------------------------------------------------ утилиты

    private fun anonymize(value: String, on: Boolean): String {
        if (!on || value.isBlank()) return value
        return if (value.startsWith("@")) "@hidden" else "скрыто"
    }

    private fun escapeXml(value: String): String {
        val sb = StringBuilder(value.length + 8)
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
}
