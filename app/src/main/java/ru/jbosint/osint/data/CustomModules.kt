package ru.jbosint.osint.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import java.util.UUID

/**
 * Пользовательский модуль: название и адрес задаёт сам аналитик.
 *
 * Название сервиса — внутренняя метка, видимая только на экране «API-ключи».
 * В отчёты (PDF/XLSX/JSON) оно не попадает: источник фактов всегда помечается
 * нейтральным идентификатором [CustomModuleSpec.SOURCE_ID], а отчёт и так
 * показывает только марку «Поиск JB OSINT».
 */
/**
 * Единый формат сведений, к которому приводится ответ любого модуля.
 * Совпадающие [kind] у разных модулей сливаются агрегатором и подтверждают друг друга.
 */
enum class FactField(val token: String, val kind: String, val category: FactCategory) {
    EMAIL("email", "Почта", FactCategory.CONTACT),
    PHONE("phone", "Телефон", FactCategory.CONTACT),
    USERNAME("username", "Никнейм", FactCategory.PERSON),
    FULLNAME("fullname", "Полное имя", FactCategory.PERSON),
    IP("ip", "IP-адрес", FactCategory.TECHNICAL),
    DOMAIN("domain", "Домен", FactCategory.TECHNICAL),
    LEAK("leak", "Утечка", FactCategory.OTHER),
    OTHER("other", "Сведение", FactCategory.OTHER);

    companion object {
        fun fromToken(token: String): FactField =
            entries.firstOrNull { it.token.equals(token, ignoreCase = true) } ?: OTHER
    }
}

/**
 * Правило разбора: JSON-путь в ответе модуля → поле единого формата.
 * [regex] необязателен: если задан, из значения берётся первая группа совпадения.
 */
data class FieldRule(
    val path: String,
    val field: FactField = FactField.OTHER,
    val regex: String = ""
)

data class CustomModule(
    val id: String,
    val title: String,
    val urlTemplate: String,
    val auth: CustomAuth,
    val method: String = "GET",
    val headerName: String = "X-API-Key",
    val paramName: String = "api_key",
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val hasKey: Boolean = false,
    val fields: List<FieldRule> = emptyList()
)

enum class CustomAuth(val title: String, val hint: String) {
    AUTO("Определить автоматически", "подбор GET/POST и способа передачи ключа при проверке"),
    BEARER("Bearer-заголовок", "Authorization: Bearer <ключ>"),
    HEADER("Свой заголовок", "например, X-API-Key: <ключ>"),
    PARAM("Параметр адреса", "ключ добавляется в URL, например ?api_key=<ключ>"),
    NONE("Без ключа", "модуль без авторизации")
}

/** Чистые функции сборки запроса: без Android и без сети — их удобно тестировать. */
object CustomModuleSpec {

    /** Нейтральный идентификатор источника: никаких названий сервисов в данных отчёта. */
    const val SOURCE_ID = "custom"

    const val MAX_TITLE = 60
    const val PLACEHOLDER = "{query}"

    /** null — всё в порядке, иначе текст ошибки для интерфейса. */
    fun validate(title: String, urlTemplate: String): String? {
        if (title.isBlank()) return "Укажите название модуля"
        if (title.length > MAX_TITLE) return "Название длиннее $MAX_TITLE символов"
        val url = urlTemplate.trim()
        if (url.isBlank()) return "Укажите URL-шаблон"
        if (!url.startsWith("https://", ignoreCase = true)) return "Разрешён только HTTPS"
        if (!url.contains(PLACEHOLDER)) return "В адресе должен быть маркер $PLACEHOLDER"
        if (url.count { it == ' ' } > 0) return "В адресе не должно быть пробелов"
        return null
    }

    fun validateRules(rules: List<FieldRule>): String? {
        if (rules.size > 24) return "Слишком много правил разбора (максимум 24)"
        rules.forEach { rule ->
            if (rule.path.isBlank()) return "Укажите путь к полю в ответе"
            if (rule.regex.isNotBlank() && runCatching { Regex(rule.regex) }.isFailure) {
                return "Некорректное регулярное выражение: ${rule.regex}"
            }
        }
        return null
    }

    fun buildUrl(module: CustomModule, key: String, query: String): String {
        var url = module.urlTemplate.trim().replace(PLACEHOLDER, Net.urlEncode(query))
        if (module.auth == CustomAuth.PARAM && key.isNotBlank()) {
            val name = module.paramName.trim().ifBlank { "api_key" }
            val separator = if (url.contains('?')) "&" else "?"
            url = url + separator + name + "=" + Net.urlEncode(key)
        }
        return url
    }

    fun headers(module: CustomModule, key: String): Map<String, String> = when (module.auth) {
        CustomAuth.BEARER -> if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")
        CustomAuth.HEADER ->
            if (key.isBlank()) emptyMap()
            else mapOf(module.headerName.trim().ifBlank { "X-API-Key" } to key)
        CustomAuth.PARAM, CustomAuth.NONE, CustomAuth.AUTO -> emptyMap()
    }

    /** Хост для карточки модуля — название сервиса аналитик видит только у себя. */
    fun hostOf(urlTemplate: String): String = runCatching {
        val afterScheme = urlTemplate.trim().substringAfter("://", urlTemplate.trim())
        afterScheme.substringBefore('/').substringBefore('?')
    }.getOrDefault(urlTemplate.trim())
}

/** Приведение значений к единому виду, чтобы совпадения из разных модулей склеивались. */
object Normalizer {

    fun canonical(field: FactField, raw: String): String {
        val value = raw.trim().trim('"').replace('\u00A0', ' ')
        if (value.isEmpty()) return ""
        return when (field) {
            FactField.EMAIL -> value.lowercase()
            FactField.PHONE -> {
                val digits = value.filter { it.isDigit() }
                when {
                    digits.length == 11 && digits.startsWith("8") -> "+7" + digits.drop(1)
                    digits.length == 10 -> "+7$digits"
                    digits.length > 11 && digits.startsWith("00") -> "+" + digits.drop(2)
                    else -> "+" + digits
                }
            }
            FactField.USERNAME -> value.lowercase().trim('@').trimStart('@').ifBlank { value }
            FactField.DOMAIN -> value.lowercase()
                .removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore('?').removePrefix("www.").trimEnd('.')
            FactField.IP -> value.lowercase()
            FactField.FULLNAME -> value.split(Regex("\\s+")).joinToString(" ")
            FactField.LEAK, FactField.OTHER -> value
        }
    }
}

/**
 * Простейший JSON-путь: `data.username`, `data.items[0].email`, `.items[].value`.
 * Пустой сегмент `[]` означает «все элементы массива».
 */
object JsonPath {

    fun select(root: Any?, path: String): List<Any?> {
        val clean = path.trim().removePrefix("$").removePrefix(".")
        if (clean.isBlank()) return listOf(root)
        var current: List<Any?> = listOf(root)
        val segments = clean.split('.')
        for (segment in segments) {
            if (segment.isBlank()) continue
            val name = segment.substringBefore('[')
            val selectors = Regex("\\[([^\\]]*)]").findAll(segment).map { it.groupValues[1].trim() }.toList()
            val next = ArrayList<Any?>()
            for (node in current) {
                var values: List<Any?> = when (node) {
                    is JSONObject -> if (name.isBlank()) listOf(node) else listOfNotNull(node.opt(name).takeIf { it != JSONObject.NULL })
                    is JSONArray -> if (name.isBlank()) listOf(node) else listOfNotNull(
                        (0 until node.length()).map { node.opt(it) }.firstOrNull { it is JSONObject && it.has(name) }
                            ?.let { (it as JSONObject).opt(name) }
                    )
                    is Map<*, *> -> listOfNotNull(node[name])
                    is List<*> -> listOf(node)
                    else -> emptyList()
                }
                for (selector in selectors) {
                    values = values.flatMap { value ->
                        when (value) {
                            is JSONArray -> when {
                                selector.isBlank() -> (0 until value.length()).map { value.opt(it) }
                                selector == "*" -> (0 until value.length()).map { value.opt(it) }
                                selector.toIntOrNull() != null -> listOfNotNull(value.opt(selector.toInt()))
                                else -> emptyList()
                            }
                            is Map<*, *> -> when {
                                selector.isBlank() || selector == "*" -> value.values.toList()
                                else -> listOfNotNull(value[selector])
                            }
                            is List<*> -> when {
                                selector.isBlank() || selector == "*" -> value
                                selector.toIntOrNull() != null -> listOfNotNull(value.getOrNull(selector.toInt()))
                                else -> emptyList()
                            }
                            else -> emptyList()
                        }
                    }
                }
                next.addAll(values)
            }
            current = next
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    /** Плоские строковые значения по пути — без вложенных объектов и массивов. */
    fun strings(root: Any?, path: String): List<String> = select(root, path)
        .flatMap { node ->
            when (node) {
                is JSONArray -> (0 until node.length()).mapNotNull { node.opt(it) }
                else -> listOf(node)
            }
        }
        .mapNotNull { node ->
            when (node) {
                null, JSONObject.NULL -> null
                is JSONObject -> null
                is JSONArray -> null
                is String -> node
                is Number, is Boolean -> node.toString()
                else -> null
            }
        }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

/**
 * Универсальный разбор ответа: сначала — правила пользователя (JSON-путь + поле +
 * необязательная регулярка), затем — эвристика по известным именам полей.
 * Названия сервисов в данные отчёта не попадают: только тип поля и значение.
 */
object CustomResponseParser {

    private val arrayKeys = listOf(
        "data", "results", "items", "response", "hits", "matches", "records", "entries", "list", "docs", "objects"
    )

    private val heuristic = mapOf(
        "email" to FactField.EMAIL, "email_address" to FactField.EMAIL, "mail" to FactField.EMAIL,
        "phone" to FactField.PHONE, "phone_number" to FactField.PHONE, "mobile" to FactField.PHONE,
        "username" to FactField.USERNAME, "login" to FactField.USERNAME, "nickname" to FactField.USERNAME,
        "name" to FactField.FULLNAME, "full_name" to FactField.FULLNAME,
        "ip" to FactField.IP, "ip_address" to FactField.IP,
        "domain" to FactField.DOMAIN, "website" to FactField.DOMAIN, "site" to FactField.DOMAIN,
        "breach" to FactField.LEAK, "leak" to FactField.LEAK, "breaches" to FactField.LEAK
    )

    fun parse(
        json: JSONObject,
        query: String,
        sourceKey: String,
        rules: List<FieldRule> = emptyList(),
        maxFacts: Int = 24
    ): List<RawFact> {
        if (rules.isNotEmpty()) return parseByRules(json, query, sourceKey, rules, maxFacts)
        return parseHeuristic(json, query, sourceKey, maxFacts)
    }

    private fun parseByRules(
        json: JSONObject,
        query: String,
        sourceKey: String,
        rules: List<FieldRule>,
        maxFacts: Int
    ): List<RawFact> {
        val facts = ArrayList<RawFact>()
        rules.forEach { rule ->
            if (facts.size >= maxFacts) return@forEach
            val regex = rule.regex.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }
            JsonPath.strings(json, rule.path).forEach { raw ->
                val extracted = if (regex == null) raw else {
                    val match = regex.find(raw) ?: return@forEach
                    match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                }
                val value = Normalizer.canonical(rule.field, extracted)
                if (value.isNotBlank()) {
                    facts += RawFact(
                        category = rule.field.category,
                        kind = rule.field.kind,
                        value = value.take(200),
                        subject = query,
                        attributes = ruleContext(json, rule.path),
                        sourceId = sourceKey
                    )
                }
            }
        }
        return facts.distinctBy { it.kind + "|" + it.value }
    }

    private fun parseHeuristic(json: JSONObject, query: String, sourceKey: String, maxFacts: Int): List<RawFact> {
        val facts = ArrayList<RawFact>()
        val array = findArray(json, 0)
        if (array != null) {
            for (i in 0 until minOf(array.length(), 8)) {
                if (facts.size >= maxFacts) break
                when (val item = array.opt(i)) {
                    is JSONObject -> collectObject(item, query, sourceKey, facts, maxFacts, 5)
                    is JSONArray -> collectArray(item, query, sourceKey, facts, maxFacts)
                    null -> Unit
                    else -> addFact(facts, FactField.OTHER, item.toString().take(180), query, sourceKey)
                }
            }
        }
        if (facts.isEmpty()) collectObject(json, query, sourceKey, facts, maxFacts, 8)
        return facts.distinctBy { it.kind + "|" + it.value }
    }

    private fun findArray(node: JSONObject, depth: Int): JSONArray? {
        if (depth > 2) return null
        arrayKeys.forEach { keyName ->
            node.optJSONArray(keyName)?.let { if (it.length() > 0) return it }
        }
        node.keys().forEach { keyName ->
            val child = node.optJSONObject(keyName) ?: return@forEach
            findArray(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun collectObject(
        node: JSONObject,
        query: String,
        sourceKey: String,
        facts: MutableList<RawFact>,
        maxFacts: Int,
        maxFields: Int
    ) {
        var fields = 0
        node.keys().forEach { rawKey ->
            if (facts.size >= maxFacts || fields >= maxFields) return@forEach
            when (val value = node.opt(rawKey)) {
                null, JSONObject.NULL -> Unit
                is JSONObject -> Unit
                is JSONArray -> collectArray(value, query, sourceKey, facts, maxFacts)
                else -> {
                    val field = heuristic[rawKey.lowercase()]
                        ?: if (rawKey.contains("url", true) || rawKey.contains("link", true)) FactField.DOMAIN else FactField.OTHER
                    addFact(facts, field, value.toString(), query, sourceKey, contextOf(node, rawKey))
                    fields++
                }
            }
        }
    }

    private fun collectArray(array: JSONArray, query: String, sourceKey: String, facts: MutableList<RawFact>, maxFacts: Int) {
        for (i in 0 until minOf(array.length(), 6)) {
            val value = array.opt(i) ?: continue
            if (value is JSONObject || value is JSONArray) continue
            addFact(facts, FactField.OTHER, value.toString(), query, sourceKey)
            if (facts.size >= maxFacts) return
        }
    }

    private fun addFact(
        facts: MutableList<RawFact>,
        field: FactField,
        raw: String,
        query: String,
        sourceKey: String,
        context: Map<String, String> = emptyMap()
    ) {
        val value = Normalizer.canonical(field, raw)
        if (value.isBlank()) return
        facts += RawFact(
            category = field.category,
            kind = field.kind,
            value = value.take(200),
            subject = query,
            attributes = context,
            sourceId = sourceKey
        )
    }

    /**
     * Соседние поля того же объекта — чтобы факт не терял смысл ответа сервиса
     * (например, «страна», «провайдер», «источник утечки» рядом с адресом почты).
     */
    private fun contextOf(node: JSONObject, excludeKey: String? = null, limit: Int = 4): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        node.keys().forEach { rawKey ->
            if (out.size >= limit) return@forEach
            if (rawKey.equals(excludeKey, true)) return@forEach
            val value = node.opt(rawKey)
            if (value == null || value == JSONObject.NULL) return@forEach
            if (value is JSONObject || value is JSONArray) return@forEach
            val text = value.toString().trim()
            if (text.isBlank() || text.length > 160) return@forEach
            out[rawKey.take(40)] = text
        }
        return out
    }

    private fun ruleContext(root: JSONObject, path: String): Map<String, String> {
        val segments = path.split('.')
            .map { it.substringBefore('[').trim() }
            .filter { it.isNotBlank() }
        if (segments.isEmpty()) return emptyMap()
        var node: JSONObject = root
        segments.dropLast(1).forEach { segment ->
            node = node.optJSONObject(segment)
                ?: node.optJSONArray(segment)?.optJSONObject(0)
                ?: return emptyMap()
        }
        val context = contextOf(node, segments.last(), 3).toMutableMap()
        context["json_path"] = path.take(60)
        return context
    }
}

/** Модуль-обёртка над HTTP-запросом. Название сервиса наружу не отдаётся. */
class CustomConnector(
    private val module: CustomModule,
    private val key: String,
    private val sourceKey: String = CustomModuleSpec.SOURCE_ID
) : Connector {

    override val id: String = sourceKey

    override suspend fun collect(query: String, keys: KeyRepository): List<RawFact> {
        if (CustomModuleSpec.validate(module.title, module.urlTemplate) != null) return emptyList()
        if (module.auth != CustomAuth.NONE && key.isBlank()) return emptyList()
        val url = CustomModuleSpec.buildUrl(module, key, query)
        val response = Net.fetch(url, CustomModuleSpec.headers(module, key), method = module.method)
        if (!response.ok) return emptyList()
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return emptyList()
        return CustomResponseParser.parse(json, query, sourceKey, module.fields)
    }
}


/**
 * Автоподбор параметров подключения нового модуля:
 * метод (GET или POST без тела) и способ передачи ключа
 * (Bearer, X-Api-Key, параметр адреса). Останов — на первом 2xx;
 * 401/403 считается признаком рабочего адреса (метод выбран, ключ не принят).
 */
class CustomConnectionProbe {

    data class Setup(
        val method: String,
        val auth: CustomAuth,
        val headerName: String,
        val paramName: String,
        val code: Int
    )

    data class Result(
        val ok: Boolean,
        val setup: Setup?,
        val detail: String
    )

    private data class Attempt(val method: String, val auth: CustomAuth, val code: Int, val reachable: Boolean)

    private val transports: List<CustomAuth> = listOf(
        CustomAuth.BEARER,
        CustomAuth.HEADER,
        CustomAuth.PARAM
    )

    suspend fun detect(module: CustomModule, key: String): Result = withContext(Dispatchers.IO) {
        val candidates = if (key.isBlank()) listOf(CustomAuth.NONE) else transports
        val attempts = ArrayList<Attempt>()
        var networkError = false

        for (method in listOf("GET", "POST")) {
            var rejected: Attempt? = null
            for (auth in candidates) {
                if (networkError) break
                val variant = module.copy(auth = auth, method = method)
                val url = CustomModuleSpec.buildUrl(variant, key, "osint")
                val outcome = runCatching {
                    Net.fetch(url, CustomModuleSpec.headers(variant, key), attempts = 1, method = method)
                }
                val response = outcome.getOrNull()
                if (response == null) {
                    networkError = true
                    break
                }
                val reachable = response.code in 200..299 || response.code == 401 || response.code == 403
                val attempt = Attempt(method, auth, response.code, reachable)
                attempts += attempt
                if (response.code in 200..299) {
                    return@withContext Result(
                        ok = true,
                        setup = Setup(method, auth, variant.headerName, variant.paramName, response.code),
                        detail = "Модуль настроен успешно"
                    )
                }
                if (reachable && rejected == null) rejected = attempt
            }
            // Адрес ответил отказом авторизации — метод рабочий, второй не проверяем.
            if (rejected != null) {
                return@withContext Result(
                    ok = false,
                    setup = Setup(
                        rejected.method, rejected.auth,
                        module.headerName, module.paramName, rejected.code
                    ),
                    detail = describe(rejected.code)
                )
            }
        }

        if (networkError) return@withContext Result(false, null, "Нет соединения")
        val last = attempts.lastOrNull()
        Result(false, last?.let {
            Setup(it.method, it.auth, module.headerName, module.paramName, it.code)
        }, last?.let { describe(it.code) } ?: "Нет соединения")
    }

    fun describe(code: Int): String = when (code) {
        401 -> "Ошибка 401: Unauthorized — ключ отклонён"
        403 -> "Ошибка 403: Forbidden — доступ запрещён"
        404 -> "Ошибка 404: Not Found — адрес не найден"
        400 -> "Ошибка 400: Bad Request — проверьте шаблон адреса"
        429 -> "Модуль отвечает (429: превышен лимит запросов)"
        in 200..299 -> "Модуль отвечает"
        in 500..599 -> "Ошибка $code: сервер недоступен"
        else -> "Ответ сервера: код $code"
    }
}

/** Проверка пользовательского модуля: один пробный запрос. */
class CustomModuleChecker(private val store: CustomModuleStore) {

    suspend fun check(module: CustomModule): KeyCheck = withContext(Dispatchers.IO) {
        val error = CustomModuleSpec.validate(module.title, module.urlTemplate)
        if (error != null) return@withContext KeyCheck(KeyState.INVALID, "Ошибка настроек: $error")
        val key = store.key(module.id)
        if (module.auth != CustomAuth.NONE && key.isBlank()) {
            return@withContext KeyCheck(KeyState.EMPTY, "Ключ не задан")
        }
        runCatching {
            val url = CustomModuleSpec.buildUrl(module, key, "osint")
            val response = Net.fetch(url, CustomModuleSpec.headers(module, key), attempts = 1, method = module.method)
            val code = response.code
            when {
                code in 200..299 -> KeyCheck(KeyState.VALID, "Модуль отвечает")
                code == 401 -> KeyCheck(KeyState.INVALID, "Ошибка 401: Unauthorized — ключ отклонён")
                code == 403 -> KeyCheck(KeyState.INVALID, "Ошибка 403: Forbidden — доступ запрещён")
                code == 404 -> KeyCheck(KeyState.INVALID, "Ошибка 404: Not Found — адрес не найден")
                code == 400 -> KeyCheck(KeyState.INVALID, "Ошибка 400: Bad Request — проверьте шаблон адреса")
                code == 429 -> KeyCheck(KeyState.VALID, "Модуль отвечает (429: превышен лимит запросов)")
                code in 500..599 -> KeyCheck(KeyState.INVALID, "Ошибка $code: ${reason(code)} — сервер недоступен")
                else -> KeyCheck(KeyState.VALID, "Модуль отвечает (код $code)")
            }
        }.getOrElse {
            KeyCheck(KeyState.OFFLINE, "Нет соединения")
        }
    }

    private fun reason(code: Int): String = when (code) {
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Server Error"
    }
}

/**
 * Хранилище пользовательских модулей: описание — в зашифрованном виде,
 * ключ — отдельным зашифрованным полем и никогда не попадает в JSON модуля.
 */
class CustomModuleStore(context: Context) {

    private val secure = SecureStore(context.applicationContext)
    private val prefs = context.getSharedPreferences("jb_custom_modules", Context.MODE_PRIVATE)

    fun list(): List<CustomModule> = order().mapNotNull { id ->
        secure.get("cm_$id")?.let { decode(it) }
    }

    fun get(id: String): CustomModule? = secure.get("cm_$id")?.let { decode(it) }

    fun key(id: String): String = secure.get("cm_key_$id").orEmpty()

    fun save(module: CustomModule, key: String? = null) {
        val id = module.id.ifBlank { UUID.randomUUID().toString() }
        val created = module.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val stored = module.copy(id = id, createdAt = created, title = module.title.trim().take(CustomModuleSpec.MAX_TITLE))
        secure.put("cm_$id", encode(stored))
        if (key != null) secure.put("cm_key_$id", key.trim())
        val ids = order().toMutableList()
        if (id !in ids) ids += id
        prefs.edit().putString("order", ids.joinToString(",")).apply()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val module = get(id) ?: return
        save(module.copy(enabled = enabled), null)
    }

    fun delete(id: String) {
        secure.remove("cm_$id")
        secure.remove("cm_key_$id")
        prefs.edit().putString("order", order().filter { it != id }.joinToString(",")).apply()
    }

    fun wipe() {
        order().forEach { delete(it) }
        prefs.edit().remove("order").apply()
    }

    fun activeModules(): List<Pair<CustomModule, String>> = list()
        .filter { it.enabled }
        .mapNotNull { module ->
            val key = key(module.id)
            if (module.auth != CustomAuth.NONE && key.isBlank()) null else module to key
        }

    private fun order(): List<String> =
        prefs.getString("order", "").orEmpty().split(',').filter { it.isNotBlank() }

    private fun encode(module: CustomModule): String = JSONObject()
        .put("id", module.id)
        .put("title", module.title)
        .put("url", module.urlTemplate)
        .put("auth", module.auth.name)
        .put("method", module.method)
        .put("header", module.headerName)
        .put("param", module.paramName)
        .put("enabled", module.enabled)
        .put("created", module.createdAt)
        .put("fields", JSONArray().apply {
            module.fields.forEach { rule ->
                put(JSONObject()
                    .put("path", rule.path)
                    .put("field", rule.field.token)
                    .put("regex", rule.regex))
            }
        })
        .toString()

    private fun decode(json: String): CustomModule? = runCatching {
        val root = JSONObject(json)
        val id = root.getString("id")
        CustomModule(
            id = id,
            title = root.optString("title"),
            urlTemplate = root.optString("url"),
            auth = runCatching { CustomAuth.valueOf(root.optString("auth")) }.getOrDefault(CustomAuth.HEADER),
            method = root.optString("method", "GET").ifBlank { "GET" },
            headerName = root.optString("header", "X-API-Key"),
            paramName = root.optString("param", "api_key"),
            enabled = root.optBoolean("enabled", true),
            createdAt = root.optLong("created"),
            hasKey = key(id).isNotBlank(),
            fields = root.optJSONArray("fields")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    FieldRule(
                        path = item.optString("path"),
                        field = FactField.fromToken(item.optString("field")),
                        regex = item.optString("regex")
                    )
                }
            }.orEmpty()
        )
    }.getOrNull()
}
