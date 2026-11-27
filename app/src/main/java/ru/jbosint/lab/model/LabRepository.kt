package ru.jbosint.lab.model

import org.json.JSONArray
import org.json.JSONObject

/** Тип проекта, определённый сервером по файлам репозитория. */
enum class LabProjectType(val wire: String, val title: String) {
    ANDROID("android", "Android / Gradle"),
    PYTHON("python", "Python"),
    NODE("node", "Node.js"),
    BASH("bash", "Bash"),
    GO("go", "Go"),
    PHP("php", "PHP"),
    PERL("perl", "Perl"),
    RUBY("ruby", "Ruby"),
    OTHER("other", "Другой");

    companion object {
        fun from(value: String?): LabProjectType =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/** Найденный исполняемый файл (относительный путь внутри проекта). */
data class LabScript(
    val name: String,
    val path: String,
    val type: LabProjectType,
    /** Заголовок кнопки из `# JB_BUTTON` (если размечен). */
    val button: String? = null,
    /** Имя параметра из `# JB_PARAM` (если скрипт принимает аргумент). */
    val param: String? = null,
    /** Есть ли системный интерпретатор для этого языка. */
    val available: Boolean = true,
    /** Команда установки интерпретатора (Termux). */
    val installHint: String? = null,
    /** Описание из `# JB_MODULE` (второе поле). */
    val description: String? = null
) {
    /** Команда запуска с уже подставленным путём (cwd на сервере = каталог проекта). */
    fun command(): String = when (type) {
        LabProjectType.PYTHON -> "python3 " + quote(path)
        LabProjectType.NODE -> "node " + quote(path)
        LabProjectType.BASH -> "sh " + quote(path)
        LabProjectType.GO -> "go run " + quote(path)
        LabProjectType.PHP -> "php " + quote(path)
        LabProjectType.PERL -> "perl " + quote(path)
        LabProjectType.RUBY -> "ruby " + quote(path)
        LabProjectType.ANDROID -> "./gradlew " + quote(path)
        LabProjectType.OTHER -> "sh " + quote(path)
    }

    private fun quote(value: String): String =
        if (value.any { it.isWhitespace() || it == '\'' }) "'" + value.replace("'", "'\\''") + "'"
        else value
}

data class LabProject(
    val url: String,
    val ref: String,
    val dir: String,
    val type: LabProjectType,
    val scripts: List<LabScript> = emptyList()
)

data class LabRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val tookMs: Long
)

data class LabLogLine(
    val at: Long = System.currentTimeMillis(),
    val text: String,
    val error: Boolean = false
)

/** Настройки клиента: адрес локального сервера и токен из setup_lab.sh. */
data class LabApiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val token: String = ""
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8081"
        const val DEFAULT_TIMEOUT_SEC = 300
    }
}

/** Готовая команда-кнопка (динамические действия на экране). */
data class LabCommandTemplate(val title: String, val command: String)


/** Доступность интерпретатора в системе. */
data class LabRuntime(
    val id: String,
    val title: String,
    val available: Boolean,
    val install: String?
)

/** Карточка результата из JSON-вывода скрипта. */
data class LabResultCard(
    val service: String,
    val password: String,
    val date: String,
    val url: String,
    val extra: Map<String, String> = emptyMap()
)

/** Терпимый разбор JSON-вывода скрипта в карточки. null — это не JSON-результат. */
object LabResultParser {

    fun parse(text: String): List<LabResultCard>? {
        val raw = text.trim()
        if (raw.isEmpty() || (raw.first() != '{' && raw.first() != '[')) return null
        val items = ArrayList<JSONObject>()
        val array = runCatching { JSONArray(raw) }.getOrNull()
        if (array != null) {
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { items += it }
        } else {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val nested = listOf("results", "data", "items", "cards", "leaks", "records")
                .firstNotNullOfOrNull { key -> root.optJSONArray(key) }
            if (nested != null) {
                for (i in 0 until nested.length()) nested.optJSONObject(i)?.let { items += it }
            } else {
                items += root
            }
        }
        val cards = items.mapNotNull { toCard(it) }
        return cards.ifEmpty { null }
    }

    private fun toCard(obj: JSONObject): LabResultCard? {
        val service = first(obj, "service", "ресурс", "resource", "source", "name", "база")
        val password = first(obj, "password", "пароль", "hash", "хеш", "pass")
        val date = first(obj, "date", "дата", "breach_date")
        val url = first(obj, "url", "link", "ссылка", "source_url")
        if (service.isBlank() && password.isBlank() && url.isBlank()) return null
        val extra = LinkedHashMap<String, String>()
        obj.keys().forEach { key ->
            if (extra.size >= 6) return@forEach
            val value = obj.opt(key)
            if (value is String && value.isNotBlank() && key.lowercase() !in setOf(
                    "service", "ресурс", "resource", "source", "name", "база",
                    "password", "пароль", "hash", "хеш", "pass",
                    "date", "дата", "breach_date", "url", "link", "ссылка", "source_url"
                )
            ) {
                extra[key.take(30)] = value.take(200)
            }
        }
        return LabResultCard(
            service = service.take(200),
            password = password.take(300),
            date = date.take(60),
            url = url.take(500),
            extra = extra
        )
    }

    private fun first(obj: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = obj.opt(key)
            if (value is String && value.isNotBlank() && value != "null") return value.trim()
            if (value is Number) return value.toString()
        }
        return ""
    }
}

/** Экспорт карточек результатов в JSON/CSV/HTML (без Android). */
object LabResultExport {

    fun toJson(cards: List<LabResultCard>): String {
        val array = JSONArray()
        cards.forEach { card ->
            val obj = JSONObject()
                .put("service", card.service)
                .put("password", card.password)
                .put("date", card.date)
                .put("url", card.url)
            if (card.extra.isNotEmpty()) obj.put("extra", JSONObject(card.extra as Map<*, *>))
            array.put(obj)
        }
        return array.toString(2)
    }

    fun toCsv(cards: List<LabResultCard>): String {
        val rows = ArrayList<List<String>>()
        rows += listOf("service", "password", "date", "url", "extra")
        cards.forEach { card ->
            rows += listOf(
                cell(card.service), cell(card.password), cell(card.date), cell(card.url),
                cell(card.extra.entries.joinToString("; ") { "${it.key}: ${it.value}" })
            )
        }
        return rows.joinToString("\r\n") { row -> row.joinToString(";") } + "\r\n"
    }

    fun toHtml(cards: List<LabResultCard>): String = buildString {
        append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
        append("<title>JB Lab — результаты</title><style>")
        append("body{font:14px/1.5 sans-serif;margin:24px;color:#1b1e26}")
        append("table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:6px;border-bottom:1px solid #e4e7ec}")
        append("th{font-size:11px;color:#6b7280;text-transform:uppercase}</style></head><body>")
        append("<h1>JB Lab — результаты</h1><table><thead><tr>")
        append("<th>Сервис</th><th>Пароль/хеш</th><th>Дата</th><th>Ссылка</th></tr></thead><tbody>")
        cards.forEach { card ->
            append("<tr><td>").append(esc(card.service)).append("</td>")
            append("<td>").append(esc(card.password)).append("</td>")
            append("<td>").append(esc(card.date)).append("</td>")
            append("<td>")
            if (card.url.startsWith("http")) {
                append("<a href=\"").append(esc(card.url)).append("\" rel=\"noopener noreferrer\">")
                    .append(esc(card.url)).append("</a>")
            } else {
                append(esc(card.url))
            }
            append("</td></tr>")
        }
        append("</tbody></table></body></html>")
    }

    private fun cell(value: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ")
        val safe = if (clean.isNotEmpty() && clean[0] in "=+-@") "'$clean" else clean
        return if (safe.contains(';') || safe.contains('"') || safe.startsWith(" ") || safe.endsWith(" ")) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else safe
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

class LabException(message: String) : Exception(message)
