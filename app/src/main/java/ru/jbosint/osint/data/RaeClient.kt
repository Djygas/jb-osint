package ru.jbosint.osint.data

/** Один эндпоинт, найденный в сгенерированном клиенте. */
data class RaeFunction(
    val name: String,
    val method: String,
    val path: String,
    val params: List<String>
)

/**
 * Метаданные клиента, сгенерированного Reverse API Engineer.
 * Секреты в заголовках маскируются: в приложение попадает только структура.
 */
data class RaeClient(
    val baseUrl: String,
    val headers: Map<String, String>,
    val functions: List<RaeFunction>
) {
    val host: String get() = baseUrl.removeSuffix("/").substringAfter("://").substringBefore("/")

    /** Шаблон для «Своего модуля»: первый эндпоинт + маркер {query}. */
    fun urlTemplate(): String {
        val first = functions.firstOrNull()
        val path = first?.path.orEmpty()
        val full = baseUrl.trimEnd('/') + path
        if (full.contains("{")) {
            return full.replaceFirst(Regex("\\{[^}]+}"), CustomModuleSpec.PLACEHOLDER)
        }
        val separator = if (full.contains('?')) "&" else "?"
        return "$full${separator}query=${CustomModuleSpec.PLACEHOLDER}"
    }
}

/**
 * Разбор Python-клиента RAE без запуска кода. Поддерживает типовые шаблоны
 * `requests`/`httpx`/`session`: base_url, статические заголовки, def-функции.
 */
object RaeParser {

    private val BASE_URL = Regex(
        "(?i)(?:base_url|baseUrl|BASE_URL|api_base|API_BASE)\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']"
    )
    private val DEF = Regex("(?m)^\\s*def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)")
    private val METHOD_CALL = Regex("(?i)\\.(get|post|put|delete|patch)\\s*\\(")
    private val METHOD_STRING = Regex("\"(GET|POST|PUT|DELETE|PATCH)\"|'(GET|POST|PUT|DELETE|PATCH)'")
    private val PATH_DOUBLE = Regex("\"(/[^\"\\s]*)\"")
    private val PATH_SINGLE = Regex("'(/[^'\\s]*)'")
    private val PATH_AFTER_BRACE = Regex("\\}(/[^\"']*)")
    private val HEADER_BLOCK = Regex(
        "(?is)(?:default_headers|DEFAULT_HEADERS|self\\.headers|headers)\\s*=\\s*\\{(.*?)}"
    )
    private val HEADER_PAIR = Regex("[\"']([A-Za-z0-9_-]+)[\"']\\s*:\\s*[\"']([^\"']*)[\"']")
    private val TOKENISH = Regex("[A-Za-z0-9_\\-.]{24,}")

    /** null — если в файле нет base_url: это не клиент RAE. */
    fun parse(source: String): RaeClient? {
        val text = source.replace("\r\n", "\n")
        val base = BASE_URL.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('/') ?: return null
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        val headers = parseHeaders(text)
        val functions = parseFunctions(text)
        return RaeClient(baseUrl = base, headers = headers, functions = functions)
    }

    fun maskValue(value: String): String = TOKENISH.replace(value) { "***" }

    private fun parseHeaders(text: String): Map<String, String> {
        val block = HEADER_BLOCK.find(text)?.groupValues?.get(1) ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        HEADER_PAIR.findAll(block).take(20).forEach { match ->
            val name = match.groupValues[1]
            val value = maskValue(match.groupValues[2])
            if (name.isNotBlank() && name.length <= 64) result[name] = value
        }
        return result
    }

    private fun parseFunctions(text: String): List<RaeFunction> {
        val matches = DEF.findAll(text).toList()
        val result = mutableListOf<RaeFunction>()
        matches.forEachIndexed { index, match ->
            val name = match.groupValues[1]
            if (name.startsWith("_")) return@forEachIndexed
            val args = match.groupValues[2]
            val bodyStart = match.range.last + 1
            val bodyEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val body = text.substring(bodyStart.coerceAtMost(text.length), bodyEnd.coerceAtMost(text.length))
            if (result.size >= 40) return@forEachIndexed
            result += RaeFunction(
                name = name,
                method = method(body),
                path = path(body),
                params = params(args)
            )
        }
        return result
    }

    private fun method(body: String): String {
        METHOD_CALL.find(body)?.let { return it.groupValues[1].uppercase() }
        METHOD_STRING.find(body)?.let { match ->
            return (match.groupValues[1].ifBlank { match.groupValues[2] }).uppercase()
        }
        return "GET"
    }

    private fun path(body: String): String {
        val candidates = buildList {
            PATH_DOUBLE.findAll(body).forEach { add(it.groupValues[1]) }
            PATH_SINGLE.findAll(body).forEach { add(it.groupValues[1]) }
            PATH_AFTER_BRACE.findAll(body).forEach { add(it.groupValues[1]) }
        }
        return candidates
            .filter { it.startsWith("/") && it.length > 1 }
            .maxByOrNull { it.length }
            ?: ""
    }

    private fun params(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "self" && it != "cls" }
            .map { it.substringBefore(':').substringBefore('=').trim() }
            .filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
            .take(12)
    }
}
