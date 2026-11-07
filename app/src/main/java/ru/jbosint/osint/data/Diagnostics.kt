package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Диагностика модулей: показывает, что именно ушло на сервер и что вернулось,
 * в виде текста, который можно скопировать и передать разработчику.
 * Значения ключей и токенов маскируются.
 */
data class DiagBlock(
    val index: Int,
    val title: String,
    val kind: String,
    val enabled: Boolean,
    val endpoint: String,
    val method: String,
    val code: Int,
    val elapsedMs: Long,
    val bytes: Int,
    val checkDetail: String,
    val structure: String,
    val facts: Int,
    val fields: String,
    val sample: String,
    val error: String?,
    val advice: String
)

object DiagMask {

    private val secretKeys = setOf(
        "token", "key", "apikey", "api_key", "api-key", "access_token", "accesstoken",
        "authorization", "auth", "secret", "password", "pass", "cookie", "session",
        "subscription-token", "x-api-key", "x-subscription-token", "folderid"
    )

    private fun isSecret(name: String): Boolean {
        val clean = name.lowercase().trim('"', '\'', ' ')
        return secretKeys.any { clean == it || clean.endsWith("_$it") || clean.contains("token") }
    }

    /** Маскирует значения секретных параметров в адресе. */
    fun url(raw: String): String {
        val parts = raw.split("?", limit = 2)
        if (parts.size == 1) return raw
        val query = parts[1].split("&").joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            if (isSecret(name)) "$name=***" else pair
        }
        return parts[0] + "?" + query
    }

    /** Маскирует значения секретных полей в JSON, оставляя структуру читаемой. */
    fun json(text: String): String {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root != null) return maskObject(root).toString(2)
        val array = runCatching { JSONArray(text) }.getOrNull()
        if (array != null) return maskArray(array).toString(2)
        return text
    }

    private fun maskObject(node: JSONObject): JSONObject {
        val out = JSONObject()
        node.keys().forEach { name ->
            val value = node.opt(name)
            when {
                isSecret(name) -> out.put(name, "***")
                value is JSONObject -> out.put(name, maskObject(value))
                value is JSONArray -> out.put(name, maskArray(value))
                else -> out.put(name, value)
            }
        }
        return out
    }

    private fun maskArray(array: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until array.length()) {
            when (val value = array.opt(i)) {
                is JSONObject -> out.put(maskObject(value))
                is JSONArray -> out.put(maskArray(value))
                else -> out.put(value)
            }
        }
        return out
    }
}

/** Краткое дерево ответа: какие объекты и массивы есть и сколько в них элементов. */
object JsonShape {

    fun describe(body: String, maxLines: Int = 18): String {
        val root = runCatching { JSONObject(body) }.getOrNull()
        if (root != null) {
            val lines = ArrayList<String>()
            walk(root, "", lines, maxLines)
            return lines.joinToString("\n").ifBlank { "объект без полей" }
        }
        val array = runCatching { JSONArray(body) }.getOrNull()
        if (array != null) {
            val lines = ArrayList<String>()
            lines += "[] (массив, ${array.length()})"
            for (i in 0 until minOf(array.length(), 3)) {
                val item = array.optJSONObject(i) ?: continue
                walk(item, "[$i]", lines, maxLines)
            }
            return lines.joinToString("\n")
        }
        return "не JSON: ${body.take(160).replace("\n", " ")}"
    }

    private fun walk(node: JSONObject, prefix: String, lines: MutableList<String>, maxLines: Int) {
        if (lines.size >= maxLines) return
        node.keys().forEach { name ->
            if (lines.size >= maxLines) return@forEach
            val path = if (prefix.isBlank()) name else "$prefix.$name"
            when (val value = node.opt(name)) {
                is JSONObject -> {
                    lines += "$path (объект)"
                    walk(value, path, lines, maxLines)
                }
                is JSONArray -> {
                    lines += "$path[] (массив, ${value.length()})"
                    val first = value.optJSONObject(0)
                    if (first != null && lines.size < maxLines) walk(first, "$path[0]", lines, maxLines)
                }
                else -> {
                    val type = when (value) {
                        null, JSONObject.NULL -> "null"
                        is Number -> "число"
                        is Boolean -> "логическое"
                        else -> "строка"
                    }
                    lines += "$path ($type)"
                }
            }
        }
    }
}

object DiagReport {

    fun render(
        appVersion: String,
        android: String,
        query: String,
        blocks: List<DiagBlock>,
        startedAt: Long
    ): String {
        val time = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale("ru"))
            .format(java.util.Date(startedAt))
        val sb = StringBuilder()
        sb.appendLine("JB OSINT · диагностика модулей")
        sb.appendLine("Версия: $appVersion · Android $android · $time")
        sb.appendLine("Тестовый запрос: $query")
        sb.appendLine("Секреты скрыты: значения ключей и токенов заменены на ***")
        sb.appendLine()

        blocks.forEach { b ->
            sb.appendLine("[${b.index}] ${b.title} · ${b.kind} · ${if (b.enabled) "ВКЛ" else "ВЫКЛ"}")
            sb.appendLine("Запрос: ${b.method} ${b.endpoint}")
            val size = if (b.bytes > 0) " · ${b.bytes} байт" else ""
            sb.appendLine("Ответ: ${if (b.code == 0) "нет данных" else "код ${b.code}"} · ${b.elapsedMs} мс$size")
            if (b.checkDetail.isNotBlank()) sb.appendLine("Проверка: ${b.checkDetail}")
            if (b.structure.isNotBlank()) {
                sb.appendLine("Структура:")
                b.structure.lineSequence().forEach { sb.appendLine("  $it") }
            }
            sb.appendLine("Фактов: ${b.facts}" + if (b.fields.isNotBlank()) " · поля: ${b.fields}" else "")
            if (b.error != null) sb.appendLine("Ошибка: ${b.error}")
            if (b.sample.isNotBlank()) {
                sb.appendLine("Образец ответа:")
                b.sample.lineSequence().forEach { sb.appendLine("  $it") }
            }
            sb.appendLine("Оценка: ${b.advice}")
            sb.appendLine()
        }

        val working = blocks.count { it.advice.startsWith("работает") }
        val empty = blocks.count { it.facts == 0 }
        sb.appendLine("Итого: модулей ${blocks.size}, рабочих $working, без фактов $empty")
        sb.appendLine("Подсказка: если у модуля «0 фактов», добавьте правила разбора «JSON-путь → поле» в карточке модуля.")
        return sb.toString().trimEnd()
    }
}
