package ru.jbosint.osint.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.agent.WatchItem

/**
 * Мониторинг объявлений.
 *
 * Пользователь сохраняет критерий (например, «Toyota Camry 2021 до 2 500 000»),
 * приложение при проверке запрашивает открытый веб-индекс и сравнивает находки
 * с предыдущим прогоном. Новые ссылки попадают в список «новое», цена ниже
 * заданного порога подсвечивается.
 *
 * Честное ограничение: фоновая работа без открытого приложения не выполняется —
 * проверка идёт по кнопке или при открытии раздела. Уведомления внутри
 * приложения, без системных push.
 */
class WatchStore(context: Context) {

    private val secure = SecureStore(context.applicationContext)
    private val keys: KeySource = KeyRepository(context.applicationContext)
    private val prefs = context.getSharedPreferences("jb_watch", Context.MODE_PRIVATE)

    fun list(): List<WatchItem> {
        val raw = secure.get("watch_list") ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> decode(array.optJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    fun add(title: String, query: String, priceMax: Int = 0): WatchItem {
        val item = WatchItem(
            id = "w" + System.currentTimeMillis().toString(36),
            title = title.trim().ifBlank { query.trim() },
            query = query.trim(),
            priceMax = priceMax
        )
        save(list() + item)
        return item
    }

    fun remove(id: String) = save(list().filterNot { it.id == id })

    fun clear() {
        secure.remove("watch_list")
        prefs.edit().clear().apply()
    }

    fun save(items: List<WatchItem>) {
        val array = JSONArray()
        items.take(30).forEach { array.put(encode(it)) }
        secure.put("watch_list", array.toString())
    }

    /** Проверка одного критерия: новые ссылки относительно предыдущего прогона. */
    suspend fun check(item: WatchItem): WatchCheck {
        val connector = Connectors.byId("ddg")
            ?: return WatchCheck(item, emptyList(), false, "Модуль веб-поиска недоступен")
        val query = buildQuery(item)
        val facts = runCatching { connector.collect(query, keys) }
            .getOrElse { return WatchCheck(item, emptyList(), false, it.message ?: "Запрос не удался") }
        val urls = facts.map { it.value }.filter { it.startsWith("http") }
        val seenKey = "seen_${item.id}"
        val seen = prefs.getStringSet(seenKey, emptySet()).orEmpty()
        val fresh = urls.filter { url -> seen.none { it.equals(url, true) } }
        val merged = (seen + urls).toList().takeLast(300).toSet()
        prefs.edit().putStringSet(seenKey, merged).apply()

        val sample = facts.firstOrNull()
        val updated = item.copy(
            lastChecked = System.currentTimeMillis(),
            lastUrl = sample?.value.orEmpty(),
            lastTitle = sample?.attributes?.get("Фрагмент").orEmpty().take(120),
            hits = urls.size
        )
        save(list().map { if (it.id == item.id) updated else it })
        return WatchCheck(updated, fresh.take(10), changed = fresh.isNotEmpty(), error = null)
    }

    private fun buildQuery(item: WatchItem): String =
        if (item.priceMax > 0) "${item.query} цена до ${item.priceMax}" else item.query

    private fun encode(item: WatchItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("title", item.title)
        .put("query", item.query)
        .put("priceMax", item.priceMax)
        .put("createdAt", item.createdAt)
        .put("lastChecked", item.lastChecked)
        .put("lastTitle", item.lastTitle)
        .put("lastUrl", item.lastUrl)
        .put("hits", item.hits)

    private fun decode(obj: JSONObject?): WatchItem? {
        obj ?: return null
        val id = obj.optString("id")
        if (id.isBlank()) return null
        return WatchItem(
            id = id,
            title = obj.optString("title"),
            query = obj.optString("query"),
            priceMax = obj.optInt("priceMax"),
            createdAt = obj.optLong("createdAt"),
            lastChecked = obj.optLong("lastChecked"),
            lastTitle = obj.optString("lastTitle"),
            lastUrl = obj.optString("lastUrl"),
            hits = obj.optInt("hits")
        )
    }
}

/** Результат проверки критерия мониторинга. */
data class WatchCheck(
    val item: WatchItem,
    val fresh: List<String>,
    val changed: Boolean,
    val error: String?
) {
    val summary: String
        get() = when {
            error != null -> "Ошибка: $error"
            changed -> "Новые объявления: ${fresh.size}"
            item.hits > 0 -> "Проверено, новых нет (всего найдено ${item.hits})"
            else -> "Проверено, совпадений нет"
        }
}
