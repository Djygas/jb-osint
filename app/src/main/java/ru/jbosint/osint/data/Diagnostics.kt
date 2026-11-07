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
    val advice: String,
    /** Запрос, которым реально проверялся модуль (если отличается от общего). */
    val testQuery: String? = null
)

/**
 * Диагностика по одному общему запросу врёт: большинство API принимает
 * только свой тип данных. Для проверки каждому модулю подставляется
 * подходящий пример, а в отчёте видно, чем именно проверяли.
 */
object DiagSamples {

    private val samples = mapOf(
        "github" to "torvalds",
        "gitlab" to "root",
        "hibp" to "test@example.com",
        "hunter" to "example.com",
        "numverify" to "+14158586273",
        "ipinfo" to "8.8.8.8",
        "abuseipdb" to "8.8.8.8",
        "shodan" to "8.8.8.8",
        "virustotal" to "example.com",
        "urlscan" to "example.com",
        "securitytrails" to "example.com",
        "dyxless" to "diag-check-zz@example.invalid",
        "leakcheck" to "diag-check-zz@example.invalid",
        "telesint" to "@telegram",
        "faceapi" to "face-photo.jpg",
        "opencorporates" to "tesla",
        "tgstat" to "osint",
        "vk" to "Иван Иванов",
        "yandex" to "osint",
        "google" to "osint",
        "brave" to "osint",
        "serpapi" to "osint",
        "ddg" to "osint",
        "wikipedia" to "osint"
    )

    fun forProvider(id: String): String? = samples[id]

    /** Что модуль реально принимает — показывается, если фактов ноль. */
    val accepted: Map<String, String> = mapOf(
        "hibp" to "только email",
        "leakcheck" to "email, телефон, ник или домен",
        "faceapi" to "фотография лица (JPG/PNG), ключ и адрес API",
        "hunter" to "только домен",
        "ipinfo" to "только IP или домен",
        "abuseipdb" to "только IP",
        "shodan" to "IP, домен или название организации",
        "virustotal" to "домен, IP или хеш файла",
        "urlscan" to "только домен",
        "securitytrails" to "только домен",
        "numverify" to "только телефон",
        "vk" to "имя, фамилия или ник",
        "opencorporates" to "название компании",
        "tgstat" to "слово, канал или ссылка",
        "github" to "ник или имя",
        "gitlab" to "ник",
        "dyxless" to "email, телефон, IP, полное ФИО (3 слова), Telegram-ник или id:123456789",
        "telesint" to "ник (@name), телефон, имя или id:123456789"
    )

    fun acceptedFor(id: String): String? = accepted[id]
}

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

    /** Bearer-заголовок или JWT, попавшие в текст ответа или в сообщение об ошибке. */
    private val bearer = Regex("""(?i)(bearer\s+)([A-Za-z0-9_\-\.=:]{8,})""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\b""")
    private val inText = Regex(
        """(?i)(\b(?:api[_-]?key|apikey|access[_-]?token|token|secret|password|passwd|session)\b\s*[=:]\s*)([^\s&"',;)\]]+)"""
    )

    /**
     * Маскирует секреты в произвольном тексте: сервисы нередко возвращают
     * ошибку с полным адресом запроса или эхом ключа — такой ответ нельзя
     * показывать в отчёте. Работает и для JSON, и для HTML/plain.
     */
    fun text(raw: String): String {
        if (raw.isBlank()) return raw
        return raw
            .replace(jwt, "***")
            .replace(bearer) { m -> m.groupValues[1] + "***" }
            .replace(inText) { m -> m.groupValues[1] + "***" }
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
                value is String -> out.put(name, text(value))
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
                is String -> out.put(text(value))
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
        // Двойная защита: даже если какой-то блок собран в обход DiagMask,
        // на выходе не должно остаться ни ключа в адресе, ни JWT в тексте ответа.
        val safe = blocks.map { b ->
            b.copy(
                endpoint = DiagMask.text(b.endpoint),
                checkDetail = DiagMask.text(b.checkDetail),
                structure = DiagMask.text(b.structure),
                fields = DiagMask.text(b.fields),
                sample = DiagMask.text(b.sample),
                error = b.error?.let { DiagMask.text(it) },
                advice = DiagMask.text(b.advice),
                testQuery = b.testQuery?.let { DiagMask.text(it) }
            )
        }
        sb.appendLine("JB OSINT · диагностика модулей")
        sb.appendLine("Версия: $appVersion · Android $android · $time")
        sb.appendLine("Тестовый запрос: $query")
        sb.appendLine("Секреты скрыты: значения ключей и токенов заменены на ***")
        sb.appendLine()

        safe.forEach { b ->
            sb.appendLine("[${b.index}] ${b.title} · ${b.kind} · ${if (b.enabled) "ВКЛ" else "ВЫКЛ"}")
            sb.appendLine("Запрос: ${b.method} ${b.endpoint}")
            val size = if (b.bytes > 0) " · ${b.bytes} байт" else ""
            sb.appendLine("Ответ: ${if (b.code == 0) "нет данных" else "код ${b.code}"} · ${b.elapsedMs} мс$size")
            if (b.checkDetail.isNotBlank()) sb.appendLine("Проверка: ${b.checkDetail}")
            if (b.testQuery != null && b.testQuery != query) {
                sb.appendLine("Тестовый запрос модуля: ${b.testQuery}")
            }
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

        val working = safe.count { it.advice.startsWith("работает") }
        val empty = safe.count { it.facts == 0 }
        sb.appendLine("Итого: модулей ${safe.size}, рабочих $working, без фактов $empty")
        sb.appendLine("Подсказка: «0 фактов» чаще всего значит, что запрос не того типа — многие API принимают только email, IP или домен (см. «Оценка»).")
        sb.appendLine("Для пользовательских модулей добавьте правила разбора «JSON-путь → поле» в карточке модуля.")
        return sb.toString().trimEnd()
    }
}
