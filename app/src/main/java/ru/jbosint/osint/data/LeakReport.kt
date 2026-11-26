package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.data.source.DataSourceRegistry
import ru.jbosint.osint.data.source.LeakOrigin
import ru.jbosint.osint.data.source.LeakRecord

/**
 * Запись отчёта утечек (схема `jb-osint/leak-scan/2.0`).
 *
 * [password] берётся ТОЛЬКО из локального индекса дампов. Для онлайн-источников
 * пароль всегда null, а факт его наличия показывается через [hadPassword].
 */
data class LeakReportRow(
    val value: String,
    val password: String? = null,
    val where: String = "",
    val source: String = "",
    val hadPassword: Boolean = false,
    val date: String = "",
    val url: String = ""
)

/** Источник в отчёте утечек: карточка с записями. */
data class LeakReportSource(
    val id: String,
    val title: String,
    val hits: Int,
    val cached: Boolean = false,
    val note: String? = null,
    val rows: List<LeakReportRow> = emptyList()
)

/** Разобранный отчёт: версия 2.0 или совместимая 1.0. */
data class LeakReport(
    val schema: String,
    val query: String,
    val createdAt: Long,
    val offline: Boolean,
    val cached: Boolean,
    val note: String,
    val passwordMode: Boolean,
    val sources: List<LeakReportSource>
)

/** Преобразование сырого факта источника в запись отчёта. Чистые правила без Android. */
object LeakRows {

    /**
     * null — факт не является записью утечки (сводка, статус, тип данных).
     * Пароль подставляется только для [indexId].
     */
    fun fromFact(fact: RawFact, indexId: String = "leak_index"): LeakReportRow? {
        if (!fact.kind.startsWith("Утечка")) return null
        val attrs = fact.attributes
        val isIndex = fact.sourceId == indexId
        val password = if (isIndex) attrs["password"]?.takeIf { it.isNotBlank() } else null
        val where = if (isIndex) {
            attrs["where"].orEmpty()
                .ifBlank { attrs["Файл"].orEmpty() }
                .ifBlank { attrs["file"].orEmpty() }
        } else {
            val record = LeakRecord(fact.value, fact.sourceId, attrs, fact.sourceUrl)
            val source = DataSourceRegistry.all.firstOrNull { it.info.id == fact.sourceId }
            (source?.resolveOrigin(record) ?: LeakOrigin.from(record)).ifBlank {
                // У HIBP/LeakCheck значение факта — название утечки, это и есть происхождение.
                // У остальных без явных метаданных происхождение неизвестно.
                when (fact.sourceId) {
                    "hibp", "leakcheck" -> fact.value
                    else -> ""
                }
            }
        }
        val hadPassword = password != null ||
            attrs["hadPassword"]?.equals("true", ignoreCase = true) == true ||
            attrs["Пароль"]?.isNotBlank() == true ||
            attrs["Пароли"]?.isNotBlank() == true
        return LeakReportRow(
            value = fact.subject.ifBlank { fact.value },
            password = password,
            where = where,
            source = fact.sourceId,
            hadPassword = hadPassword,
            date = attrs["Дата"].orEmpty(),
            url = fact.sourceUrl
        )
    }
}

/**
 * Кодек отчёта утечек.
 *
 * Экспорт всегда пишется в схему 2.0. Пароль включается в файл только при
 * включённом режиме показа; иначе поле `password` равно null. Парсер читает
 * и 2.0, и старую 1.0 без ошибок.
 */
object LeakReportCodec {

    const val SCHEMA_V1 = "jb-osint/leak-scan/1.0"
    const val SCHEMA_V2 = "jb-osint/leak-scan/2.0"

    fun encode(
        query: String,
        createdAt: Long,
        offline: Boolean,
        cached: Boolean,
        note: String,
        sources: List<LeakReportSource>,
        showPasswords: Boolean
    ): String {
        val root = JSONObject()
            .put("schema", SCHEMA_V2)
            .put("query", query)
            .put("createdAt", createdAt)
            .put("offline", offline)
            .put("cached", cached)
            .put("note", note)
            .put("passwordMode", showPasswords)
        val array = JSONArray()
        sources.forEach { source ->
            val rows = JSONArray()
            source.rows.forEach { row ->
                val obj = JSONObject()
                    .put("value", row.value)
                    .put("where", row.where)
                    .put("source", row.source.ifBlank { source.id })
                    .put("hadPassword", row.hadPassword || row.password != null)
                    .put("date", row.date)
                    .put("url", row.url)
                if (showPasswords && !row.password.isNullOrBlank()) {
                    obj.put("password", row.password)
                } else {
                    obj.put("password", JSONObject.NULL)
                }
                rows.put(obj)
            }
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("title", source.title)
                    .put("hits", source.hits)
                    .put("cached", source.cached)
                    .put("note", source.note.orEmpty())
                    .put("rows", rows)
            )
        }
        root.put("sources", array)
        return root.toString(2)
    }

    fun decode(text: String): LeakReport? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val schema = root.optString("schema", SCHEMA_V1)
        val sources = ArrayList<LeakReportSource>()
        val array = root.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until array.length()) {
            val source = array.optJSONObject(i) ?: continue
            val id = source.optString("id")
            val rows = ArrayList<LeakReportRow>()
            val rowsArray = source.optJSONArray("rows") ?: JSONArray()
            for (j in 0 until rowsArray.length()) {
                val row = rowsArray.optJSONObject(j) ?: continue
                val password = if (row.has("password") && !row.isNull("password")) {
                    row.optString("password").takeIf { it.isNotBlank() }
                } else null
                rows += LeakReportRow(
                    value = row.optString("value"),
                    password = password,
                    where = row.optString("where"),
                    source = row.optString("source").ifBlank { id },
                    hadPassword = row.optBoolean("hadPassword", false) || password != null,
                    date = row.optString("date"),
                    url = row.optString("url")
                )
            }
            sources += LeakReportSource(
                id = id,
                title = source.optString("title"),
                hits = source.optInt("hits", rows.size),
                cached = source.optBoolean("cached", false),
                note = source.optString("note").takeIf { it.isNotBlank() },
                rows = rows
            )
        }
        return LeakReport(
            schema = schema,
            query = root.optString("query"),
            createdAt = root.optLong("createdAt", 0L),
            offline = root.optBoolean("offline", false),
            cached = root.optBoolean("cached", false),
            note = root.optString("note"),
            passwordMode = root.optBoolean("passwordMode", false),
            sources = sources
        )
    }

    /**
     * CSV без паролей: только факт наличия (`hadPassword`), значение, происхождение
     * и источник. Опасные для формул значения экранируются апострофом.
     */
    fun toCsv(sources: List<LeakReportSource>): String {
        val rows = ArrayList<List<String>>()
        rows += listOf("value", "hadPassword", "where", "source", "date", "url")
        sources.forEach { source ->
            source.rows.forEach { row ->
                rows += listOf(
                    formulaSafe(row.value),
                    (row.hadPassword || row.password != null).toString(),
                    formulaSafe(row.where),
                    formulaSafe(row.source.ifBlank { source.id }),
                    formulaSafe(row.date),
                    formulaSafe(row.url)
                )
            }
        }
        return rows.joinToString("\r\n") { line ->
            line.joinToString(";") { cell(it) }
        } + "\r\n"
    }

    /** HTML без паролей: показываем «есть/нет», но не значение. */
    fun toHtml(
        query: String,
        createdAt: Long,
        sources: List<LeakReportSource>
    ): String = buildString {
        append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>JB OSINT — утечки</title><style>")
        append("body{font:14px/1.5 -apple-system,Segoe UI,Roboto,Arial,sans-serif;margin:24px;color:#1b1e26}")
        append("h1{font-size:20px}table{width:100%;border-collapse:collapse;font-size:13px}")
        append("th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #e4e7ec;vertical-align:top}")
        append("th{color:#6b7280;font-size:11px;text-transform:uppercase}")
        append("a{color:#4f5bff;text-decoration:none;word-break:break-all}")
        append("</style></head><body>")
        append("<h1>JB OSINT — отчёт об утечках</h1>")
        append("<p>Объект: <b>").append(esc(query)).append("</b><br>")
        append("Сформировано: ").append(esc(stamp(createdAt))).append("</p>")
        append("<p>Пароли в этом файле не выводятся — показан только факт наличия.</p>")
        sources.forEach { source ->
            append("<h2>").append(esc(source.title)).append("</h2>")
            if (source.rows.isEmpty()) {
                append("<p>Записей нет.</p>")
            } else {
                append("<table><thead><tr><th>Значение</th><th>Пароль</th><th>Источник</th>")
                append("<th>Дата</th><th>Ссылка</th></tr></thead><tbody>")
                source.rows.forEach { row ->
                    append("<tr><td>").append(esc(row.value)).append("</td>")
                    append("<td>").append(if (row.hadPassword || row.password != null) "есть" else "нет").append("</td>")
                    append("<td>").append(esc(row.where.ifBlank { "источник не указан" })).append("</td>")
                    append("<td>").append(esc(row.date)).append("</td>")
                    append("<td>")
                    if (row.url.startsWith("http")) {
                        append("<a href=\"").append(esc(row.url)).append("\" rel=\"noopener noreferrer\">")
                            .append(esc(row.url)).append("</a>")
                    }
                    append("</td></tr>")
                }
                append("</tbody></table>")
            }
        }
        append("</body></html>")
    }

    private fun stamp(millis: Long): String =
        java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru"))
            .format(java.util.Date(if (millis > 0) millis else System.currentTimeMillis()))

    private fun formulaSafe(value: String): String =
        if (value.isNotEmpty() && value[0] in "=+-@") "'$value" else value

    private fun cell(value: String): String {
        val clean = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val needsQuotes = clean.contains(';') || clean.contains('"') ||
            clean.startsWith(" ") || clean.endsWith(" ")
        return if (!needsQuotes) clean else "\"" + clean.replace("\"", "\"\"") + "\""
    }

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
}
