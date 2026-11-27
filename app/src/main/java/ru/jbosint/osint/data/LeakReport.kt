package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.data.source.DataSourceRegistry
import ru.jbosint.osint.data.source.LeakOrigin
import ru.jbosint.osint.data.source.LeakRecord

/** Причина, почему пароль показан или отсутствует. */
enum class PasswordStatus(val wire: String, val title: String) {
    FOUND("found", "Пароль найден"),
    MASKED("masked", "Пароль найден, но не сохранён в кэше"),
    PAYWALLED("paywalled", "Полные записи недоступны на текущем тарифе источника"),
    NOT_IN_INDEX("not_in_index", "В локальных дампах пароля не найдено"),
    API_WITHOUT_PASSWORD("api_without_password", "Источник подтверждает факт утечки, пароли не передаёт"),
    NONE("none", "Совпадений не найдено");

    companion object {
        fun fromWire(value: String?): PasswordStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: NONE
    }
}

/**
 * Запись отчёта утечек (схема `jb-osint/leak-scan/2.0`).
 *
 * [password] приходит из локального индекса дампов и из API, которые отдают
 * полные записи (LeakCheck с полным тарифом, Dyxless). В JSON значение попадает
 * только при включённом показе паролей; CSV/HTML его не содержат никогда.
 */
data class LeakReportRow(
    val value: String,
    val password: String? = null,
    val where: String = "",
    val source: String = "",
    val hadPassword: Boolean = false,
    val date: String = "",
    val url: String = "",
    /** Название набора/утечки (Tele2.ru, Collection #1…). */
    val dataTypes: List<String> = emptyList(),
    val passwordStatus: PasswordStatus = PasswordStatus.NONE,
    val passwordSource: String = "",
    val sourceRef: String = "",
    val recordId: String = ""
) {
    val resource: String get() = where
    val breachDate: String get() = date
    val dataTypesText: String get() = dataTypes.joinToString(", ")
}

/** Источник в отчёте утечек: карточка с записями. */
data class LeakReportSource(
    val id: String,
    val title: String,
    val hits: Int,
    val cached: Boolean = false,
    val note: String? = null,
    val rows: List<LeakReportRow> = emptyList(),
    /** Причина на уровне источника: например, 403/тариф. */
    val status: String? = null
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

    fun fromFact(
        fact: RawFact,
        indexId: String = "leak_index",
        sourceStatus: String? = null
    ): LeakReportRow? {
        if (!fact.kind.startsWith("Утечка")) return null
        val attrs = fact.attributes
        val isIndex = fact.sourceId == indexId
        val rawPassword = when {
            isIndex -> attrs["password"]
            else -> (attrs["Пароль"] ?: attrs["Пароли"])
                ?.substringBefore(',')
                ?.trim()
        }?.takeIf { it.isNotBlank() && it != "null" }
        val hadPassword = rawPassword != null ||
            attrs["hadPassword"]?.equals("true", ignoreCase = true) == true ||
            attrs["Пароль"]?.isNotBlank() == true ||
            attrs["Пароли"]?.isNotBlank() == true

        val resource = if (isIndex) {
            attrs["service"].orEmpty()
                .ifBlank { attrs["where"].orEmpty() }
                .ifBlank { attrs["Файл"].orEmpty() }
                .ifBlank { attrs["file"].orEmpty() }
        } else {
            attrs["База"]?.takeIf { it.isNotBlank() }
                ?: attrs["Наборы данных"]?.takeIf { it.isNotBlank() }
                ?: when (fact.sourceId) {
                    "dyxless" -> fact.kind.takeIf { it.isNotBlank() && it != "Запись" } ?: ""
                    else -> fact.value
                }
        }

        val baseTypes = if (isIndex) {
            val parsed = attrs["Типы данных"].orEmpty()
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            parsed.ifEmpty {
                listOf(if (fact.kind.contains("телефон", ignoreCase = true)) "телефон" else "email")
            }
        } else {
            val raw = attrs["Данные"] ?: attrs["Ещё"] ?: attrs["fields"]
                ?: if (fact.sourceId == "dyxless") {
                    attrs.keys.filterNot { it in setOf("Дата", "База", "Пароль") }.joinToString(", ")
                } else ""
            raw.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
        }
        val hasPasswordType = baseTypes.any {
            it.contains("password", ignoreCase = true) || it.contains("парол", ignoreCase = true)
        }
        val dataTypes = (baseTypes + if (rawPassword != null && !hasPasswordType) listOf("пароль") else emptyList())
            .distinct()

        val paywalled = sourceStatus?.let {
            it.contains("403", true) || it.contains("тариф", true) ||
                it.contains("полные записи", true)
        } == true
        val status = when {
            rawPassword != null -> PasswordStatus.FOUND
            isIndex -> PasswordStatus.NOT_IN_INDEX
            paywalled -> PasswordStatus.PAYWALLED
            hadPassword -> PasswordStatus.MASKED
            else -> PasswordStatus.API_WITHOUT_PASSWORD
        }

        val file = attrs["Файл"] ?: attrs["file"] ?: ""
        val line = attrs["Строка"] ?: attrs["line"] ?: ""
        val sourceRef = if (isIndex) {
            buildString {
                if (file.isNotBlank()) append("leaks/").append(file)
                if (line.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("строка ").append(line)
                }
            }
        } else {
            fact.sourceUrl
        }
        val recordId = if (isIndex) "$file:$line" else attrs["record_id"].orEmpty()

        return LeakReportRow(
            value = fact.subject.ifBlank { fact.value },
            password = rawPassword,
            where = resource,
            source = fact.sourceId,
            hadPassword = hadPassword,
            date = attrs["Дата"].orEmpty().ifBlank { attrs["breach_date"].orEmpty() },
            url = fact.sourceUrl,
            dataTypes = dataTypes,
            passwordStatus = status,
            passwordSource = if (isIndex) "local_index" else fact.sourceId,
            sourceRef = sourceRef,
            recordId = recordId
        )
    }

    /**
     * Дедупликация: `resource + breach_date + record_id`, а если record_id нет —
     * `resource + breach_date`. Пароль и полезные поля сливаются.
     */
    fun dedupe(rows: List<LeakReportRow>): List<LeakReportRow> {
        val merged = LinkedHashMap<String, LeakReportRow>()
        rows.forEach { row ->
            val key = if (row.recordId.isNotBlank()) {
                "${row.resource}|${row.breachDate}|${row.recordId}"
            } else {
                "${row.resource}|${row.breachDate}"
            }
            val previous = merged[key]
            merged[key] = if (previous == null) row else previous.copy(
                password = previous.password ?: row.password,
                passwordStatus = when {
                    previous.password != null -> previous.passwordStatus
                    row.password != null -> row.passwordStatus
                    previous.passwordStatus != PasswordStatus.NONE -> previous.passwordStatus
                    else -> row.passwordStatus
                },
                hadPassword = previous.hadPassword || row.hadPassword,
                dataTypes = (previous.dataTypes + row.dataTypes).distinct(),
                sourceRef = previous.sourceRef.ifBlank { row.sourceRef },
                url = previous.url.ifBlank { row.url },
                passwordSource = previous.passwordSource.ifBlank { row.passwordSource }
            )
        }
        return merged.values.toList()
    }
}

/**
 * Кодек отчёта утечек. Экспорт всегда в схему 2.0; пароль включается только при
 * включённом режиме. CSV и HTML пароль не содержат никогда.
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
                    .put("resource", row.resource)
                    .put("where", row.resource)
                    .put("breach_date", row.breachDate)
                    .put("date", row.breachDate)
                    .put("data_types", JSONArray(row.dataTypes))
                    .put("source", row.source.ifBlank { source.id })
                    .put("hadPassword", row.hadPassword || row.password != null)
                    .put("password_status", row.passwordStatus.wire)
                    .put("password_source", row.passwordSource)
                    .put("source_ref", row.sourceRef)
                    .put("record_id", row.recordId)
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
                    .put("status", source.status.orEmpty())
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
                val types = row.optJSONArray("data_types")?.let { typesArray ->
                    (0 until typesArray.length()).mapNotNull { index ->
                        typesArray.optString(index).takeIf { it.isNotBlank() }
                    }
                } ?: row.optString("data_types").split(',').map { it.trim() }.filter { it.isNotBlank() }
                rows += LeakReportRow(
                    value = row.optString("value"),
                    password = password,
                    where = row.optString("resource").ifBlank { row.optString("where") },
                    source = row.optString("source").ifBlank { id },
                    hadPassword = row.optBoolean("hadPassword", false) || password != null,
                    date = row.optString("breach_date").ifBlank { row.optString("date") },
                    url = row.optString("url"),
                    dataTypes = types,
                    passwordStatus = PasswordStatus.fromWire(row.optString("password_status")),
                    passwordSource = row.optString("password_source"),
                    sourceRef = row.optString("source_ref"),
                    recordId = row.optString("record_id")
                )
            }
            sources += LeakReportSource(
                id = id,
                title = source.optString("title"),
                hits = source.optInt("hits", rows.size),
                cached = source.optBoolean("cached", false),
                note = source.optString("note").takeIf { it.isNotBlank() },
                rows = rows,
                status = source.optString("status").takeIf { it.isNotBlank() }
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

    /** CSV без паролей: значение, ресурс, дата, типы, статус, источник, ссылка. */
    fun toCsv(sources: List<LeakReportSource>): String {
        val rows = ArrayList<List<String>>()
        rows += listOf(
            "Значение", "Есть пароль", "Ресурс утечки", "Дата утечки", "Типы данных",
            "Статус пароля", "Источник пароля", "Источник", "Ссылка", "URL"
        )
        sources.forEach { source ->
            source.rows.forEach { row ->
                rows += listOf(
                    formulaSafe(row.value),
                    (row.hadPassword || row.password != null).toString(),
                    formulaSafe(row.resource),
                    formulaSafe(row.breachDate),
                    formulaSafe(row.dataTypesText),
                    row.passwordStatus.title,
                    formulaSafe(row.passwordSource),
                    formulaSafe(row.source.ifBlank { source.id }),
                    formulaSafe(row.sourceRef),
                    formulaSafe(row.url)
                )
            }
        }
        return rows.joinToString("\r\n") { line -> line.joinToString(";") { cell(it) } } + "\r\n"
    }

    /** HTML без паролей: показываем «есть/нет» и статус, но не значение. */
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
        append("<p>Пароли в этом файле не выводятся — показан только статус.</p>")
        sources.forEach { source ->
            append("<h2>").append(esc(source.title)).append("</h2>")
            source.status?.let { append("<p class=\"muted\">").append(esc(it)).append("</p>") }
            if (source.rows.isEmpty()) {
                append("<p>Записей нет.</p>")
            } else {
                append("<table><thead><tr><th>Значение</th><th>Ресурс утечки</th><th>Дата</th>")
                append("<th>Типы данных</th><th>Пароль</th><th>Статус пароля</th><th>Источник</th></tr></thead><tbody>")
                source.rows.forEach { row ->
                    append("<tr><td>").append(esc(row.value)).append("</td>")
                    append("<td>").append(esc(row.resource.ifBlank { "источник не указан" })).append("</td>")
                    append("<td>").append(esc(row.breachDate)).append("</td>")
                    append("<td>").append(esc(row.dataTypesText)).append("</td>")
                    append("<td>").append(if (row.hadPassword || row.password != null) "есть" else "—").append("</td>")
                    append("<td>").append(esc(row.passwordStatus.title)).append("</td>")
                    append("<td>")
                    if (row.url.startsWith("http")) {
                        append("<a href=\"").append(esc(row.url)).append("\" rel=\"noopener noreferrer\">")
                            .append(esc(row.passwordSource.ifBlank { row.source })).append("</a>")
                    } else {
                        append(esc(row.passwordSource.ifBlank { row.source }))
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
