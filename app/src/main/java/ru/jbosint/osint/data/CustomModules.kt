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
data class CustomModule(
    val id: String,
    val title: String,
    val urlTemplate: String,
    val auth: CustomAuth,
    val headerName: String = "X-API-Key",
    val paramName: String = "api_key",
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val hasKey: Boolean = false
)

enum class CustomAuth(val title: String, val hint: String) {
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
        CustomAuth.PARAM, CustomAuth.NONE -> emptyMap()
    }

    /** Хост для карточки модуля — название сервиса аналитик видит только у себя. */
    fun hostOf(urlTemplate: String): String = runCatching {
        val afterScheme = urlTemplate.trim().substringAfter("://", urlTemplate.trim())
        afterScheme.substringBefore('/').substringBefore('?')
    }.getOrDefault(urlTemplate.trim())
}

/**
 * Универсальный разбор ответа произвольного сервиса.
 * Известные ключи переводятся в понятные подписи, остальные остаются как есть —
 * это имена полей, а не названия сервисов, поэтому они безопасны для отчёта.
 */
object CustomResponseParser {

    private val arrayKeys = listOf(
        "data", "results", "items", "response", "hits", "matches", "records", "entries", "list", "docs", "objects"
    )

    private val labels = mapOf(
        "email" to "Почта", "email_address" to "Почта", "mail" to "Почта",
        "phone" to "Телефон", "phone_number" to "Телефон", "mobile" to "Телефон",
        "name" to "Имя", "full_name" to "Имя", "first_name" to "Имя", "last_name" to "Фамилия",
        "username" to "Никнейм", "login" to "Логин", "nickname" to "Никнейм",
        "address" to "Адрес", "city" to "Город", "country" to "Страна", "region" to "Регион",
        "url" to "Ссылка", "link" to "Ссылка", "website" to "Сайт", "domain" to "Домен",
        "ip" to "IP-адрес", "ip_address" to "IP-адрес",
        "company" to "Организация", "organization" to "Организация", "org" to "Организация",
        "position" to "Должность", "title" to "Наименование", "id" to "Идентификатор",
        "created_at" to "Создано", "date" to "Дата", "description" to "Описание"
    )

    fun parse(json: JSONObject, query: String, maxFacts: Int = 24): List<RawFact> {
        val facts = ArrayList<RawFact>()
        val array = findArray(json, 0)
        if (array != null) {
            for (i in 0 until minOf(array.length(), 8)) {
                if (facts.size >= maxFacts) break
                when (val item = array.opt(i)) {
                    is JSONObject -> collectObject(item, query, facts, maxFacts, 5)
                    is JSONArray -> collectArray(item, query, facts, maxFacts)
                    null -> Unit
                    else -> addFact(facts, "Запись", item.toString().take(180), query)
                }
            }
        }
        if (facts.isEmpty()) collectObject(json, query, facts, maxFacts, 8)
        return facts.distinctBy { it.kind + "|" + it.value }
    }

    private fun findArray(node: JSONObject, depth: Int): JSONArray? {
        if (depth > 2) return null
        arrayKeys.forEach { keyName ->
            node.optJSONArray(keyName)?.let { if (it.length() > 0) return it }
        }
        // Вложенные контейнеры: data → items, response → results и т.п.
        node.keys().forEach { keyName ->
            val child = node.optJSONObject(keyName) ?: return@forEach
            findArray(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun collectObject(
        node: JSONObject,
        query: String,
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
                is JSONArray -> collectArray(value, query, facts, maxFacts)
                else -> {
                    val text = value.toString().trim()
                    if (text.isEmpty()) return@forEach
                    addFact(facts, kindOf(rawKey), text.take(200), query)
                    fields++
                }
            }
        }
    }

    private fun collectArray(array: JSONArray, query: String, facts: MutableList<RawFact>, maxFacts: Int) {
        for (i in 0 until minOf(array.length(), 6)) {
            val value = array.opt(i) ?: continue
            if (value is JSONObject || value is JSONArray) continue
            val text = value.toString().trim()
            if (text.isNotEmpty()) addFact(facts, "Запись", text.take(200), query)
            if (facts.size >= maxFacts) return
        }
    }

    private fun addFact(facts: MutableList<RawFact>, kind: String, value: String, query: String) {
        if (value.isBlank()) return
        val category = when {
            kind == "Ссылка" || kind == "Сайт" || kind == "Домен" || kind == "IP-адрес" -> FactCategory.TECHNICAL
            kind == "Почта" || kind == "Телефон" -> FactCategory.CONTACT
            kind == "Имя" || kind == "Фамилия" || kind == "Никнейм" || kind == "Логин" -> FactCategory.PERSON
            kind == "Адрес" || kind == "Город" || kind == "Страна" || kind == "Регион" -> FactCategory.LOCATION
            kind == "Организация" || kind == "Должность" -> FactCategory.ORGANIZATION
            else -> FactCategory.OTHER
        }
        facts += RawFact(
            category = category,
            kind = kind,
            value = value,
            subject = query,
            attributes = emptyMap(),
            sourceId = CustomModuleSpec.SOURCE_ID
        )
    }

    private fun kindOf(rawKey: String): String {
        val key = rawKey.lowercase()
        labels[key]?.let { return it }
        return if (key.startsWith("http") || rawKey.contains("url", true)) "Ссылка" else rawKey
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
        val response = Net.fetch(url, CustomModuleSpec.headers(module, key))
        if (!response.ok) return emptyList()
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return emptyList()
        return CustomResponseParser.parse(json, query).map { it.copy(sourceId = sourceKey) }
    }
}

/** Проверка пользовательского модуля: один пробный запрос. */
class CustomModuleChecker(private val store: CustomModuleStore) {

    suspend fun check(module: CustomModule): KeyCheck = withContext(Dispatchers.IO) {
        val error = CustomModuleSpec.validate(module.title, module.urlTemplate)
        if (error != null) return@withContext KeyCheck(KeyState.INVALID, error)
        val key = store.key(module.id)
        if (module.auth != CustomAuth.NONE && key.isBlank()) {
            return@withContext KeyCheck(KeyState.EMPTY, "Ключ не задан")
        }
        runCatching {
            val url = CustomModuleSpec.buildUrl(module, key, "osint")
            val response = Net.fetch(url, CustomModuleSpec.headers(module, key), attempts = 1)
            when {
                response.code in 200..299 -> KeyCheck(KeyState.VALID, "Модуль ответил, ключ принят")
                response.code == 401 || response.code == 403 -> KeyCheck(KeyState.INVALID, "Ключ отклонён (${response.code})")
                response.code == 404 || response.code == 400 -> KeyCheck(KeyState.VALID, "Адрес доступен (${response.code})")
                response.code == 429 -> KeyCheck(KeyState.VALID, "Ключ активен, лимит запросов")
                else -> KeyCheck(KeyState.INVALID, "Ответ сервиса ${response.code}")
            }
        }.getOrElse {
            KeyCheck(KeyState.OFFLINE, "Нет соединения с модулем")
        }
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
        .put("header", module.headerName)
        .put("param", module.paramName)
        .put("enabled", module.enabled)
        .put("created", module.createdAt)
        .toString()

    private fun decode(json: String): CustomModule? = runCatching {
        val root = JSONObject(json)
        val id = root.getString("id")
        CustomModule(
            id = id,
            title = root.optString("title"),
            urlTemplate = root.optString("url"),
            auth = runCatching { CustomAuth.valueOf(root.optString("auth")) }.getOrDefault(CustomAuth.HEADER),
            headerName = root.optString("header", "X-API-Key"),
            paramName = root.optString("param", "api_key"),
            enabled = root.optBoolean("enabled", true),
            createdAt = root.optLong("created"),
            hasKey = key(id).isNotBlank()
        )
    }.getOrNull()
}
