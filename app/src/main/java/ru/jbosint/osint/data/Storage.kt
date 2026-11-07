package ru.jbosint.osint.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.Link
import ru.jbosint.osint.core.model.SearchStats
import java.util.UUID

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("jb_settings", Context.MODE_PRIVATE)

    var analyst: String
        get() = prefs.getString("analyst", "").orEmpty()
        set(value) = prefs.edit().putString("analyst", value.take(80)).apply()

    var demoMode: Boolean
        get() = prefs.getBoolean("demo_mode", false)
        set(value) = prefs.edit().putBoolean("demo_mode", value).apply()

    var secureScreen: Boolean
        get() = prefs.getBoolean("secure_screen", true)
        set(value) = prefs.edit().putBoolean("secure_screen", value).apply()

    var autoWipeDays: Int
        get() = prefs.getInt("auto_wipe_days", 30)
        set(value) = prefs.edit().putInt("auto_wipe_days", value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding", false)
        set(value) = prefs.edit().putBoolean("onboarding", value).apply()
}

data class HistoryEntry(
    val id: String,
    val query: String,
    val createdAt: Long,
    val analyst: String,
    val data: AggregatedData,
    val demo: Boolean,
    /** Аналитический вывод ИИ по цифровым следам. */
    val aiAnalysis: String = "",
    /** Оформленное ИИ-резюме исследования (для отчёта). */
    val aiNarrative: String = "",
    /** Выжимка интернет-исследования (DuckDuckGo + ИИ). */
    val aiResearch: String = ""
) {
    val facts: Int get() = data.facts.size
    val links: Int get() = data.links.size
    val confirmed: Int get() = data.facts.count { it.confidence == Confidence.CONFIRMED }
}

/** История поисков: хранится зашифрованной, там же лежат данные для повторного экспорта. */
class HistoryStore(context: Context) {

    private val secure = SecureStore(context.applicationContext)
    private val prefs = context.getSharedPreferences("jb_history_index", Context.MODE_PRIVATE)

    fun save(entry: HistoryEntry, keep: Int = 20) {
        val all = loadRaw().toMutableList()
        all.removeAll { it.first == entry.id }
        all.add(0, entry.id to Serialization.encode(entry))
        while (all.size > keep) {
            all.removeAt(all.size - 1)
        }
        all.forEach { (id, json) -> secure.put("hist_$id", json) }
        prefs.edit().putString("order", all.joinToString(",") { it.first }).apply()
    }

    fun list(): List<HistoryEntry> {
        val order = prefs.getString("order", "").orEmpty().split(',').filter { it.isNotBlank() }
        return order.mapNotNull { id -> secure.get("hist_$id")?.let { Serialization.decodeEntry(it) } }
    }

    fun get(id: String): HistoryEntry? = secure.get("hist_$id")?.let(Serialization::decodeEntry)

    fun delete(id: String) {
        secure.remove("hist_$id")
        val order = prefs.getString("order", "").orEmpty().split(',').filter { it.isNotBlank() && it != id }
        prefs.edit().putString("order", order.joinToString(",")).apply()
    }

    fun clear() {
        prefs.getString("order", "").orEmpty().split(',').filter { it.isNotBlank() }
            .forEach { secure.remove("hist_$it") }
        prefs.edit().remove("order").apply()
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun loadRaw(): List<Pair<String, String>> {
        val order = prefs.getString("order", "").orEmpty().split(',').filter { it.isNotBlank() }
        return order.mapNotNull { id -> secure.get("hist_$id")?.let { id to it } }
    }
}

/** Внутренняя (не пользовательская) сериализация снимка поиска. */
object Serialization {

    fun encode(entry: HistoryEntry): String {
        val root = JSONObject()
        root.put("id", entry.id)
        root.put("query", entry.query)
        root.put("createdAt", entry.createdAt)
        root.put("analyst", entry.analyst)
        root.put("demo", entry.demo)
        root.put("data", encodeData(entry.data))
        if (entry.aiAnalysis.isNotBlank()) root.put("aiAnalysis", entry.aiAnalysis)
        if (entry.aiNarrative.isNotBlank()) root.put("aiNarrative", entry.aiNarrative)
        if (entry.aiResearch.isNotBlank()) root.put("aiResearch", entry.aiResearch)
        return root.toString()
    }

    fun encodeData(data: AggregatedData): JSONObject {
        val root = JSONObject()
        root.put("query", data.query)
        val facts = JSONArray()
        data.facts.forEach { fact ->
            facts.put(
                JSONObject()
                    .put("cat", fact.category.name)
                    .put("kind", fact.kind)
                    .put("value", fact.value)
                    .put("subject", fact.subject)
                    .put("observations", fact.observations)
                    .put("sources", fact.sourceIds.size)
                    .put("attrs", JSONObject(fact.attributes as Map<*, *>))
            )
        }
        root.put("facts", facts)
        val links = JSONArray()
        data.links.forEach { link ->
            links.put(
                JSONObject()
                    .put("from", link.from)
                    .put("to", link.to)
                    .put("relation", link.relation)
                    .put("weight", link.weight)
                    .put("sources", link.sourceCount)
            )
        }
        root.put("links", links)
        root.put(
            "stats",
            JSONObject()
                .put("attempted", data.stats.sourcesAttempted)
                .put("responded", data.stats.sourcesResponded)
                .put("failed", data.stats.sourcesFailed)
                .put("raw", data.stats.rawFacts)
                .put("dupes", data.stats.duplicatesRemoved)
                .put("duration", data.stats.durationMs)
        )
        return root
    }

    fun decodeEntry(raw: String): HistoryEntry? = runCatching {
        val root = JSONObject(raw)
        HistoryEntry(
            id = root.getString("id"),
            query = root.getString("query"),
            createdAt = root.getLong("createdAt"),
            analyst = root.optString("analyst", ""),
            demo = root.optBoolean("demo", false),
            data = decodeData(root.getJSONObject("data")),
            aiAnalysis = root.optString("aiAnalysis", ""),
            aiNarrative = root.optString("aiNarrative", ""),
            aiResearch = root.optString("aiResearch", "")
        )
    }.getOrNull()

    fun decodeData(root: JSONObject): AggregatedData {
        val facts = ArrayList<Fact>()
        val factsArray = root.optJSONArray("facts") ?: JSONArray()
        for (i in 0 until factsArray.length()) {
            val obj = factsArray.getJSONObject(i)
            val sources = obj.optInt("sources", 1)
            val attrs = LinkedHashMap<String, String>()
            obj.optJSONObject("attrs")?.let { map ->
                map.keys().forEach { key -> attrs[key] = map.optString(key) }
            }
            facts.add(
                Fact(
                    category = runCatching { FactCategory.valueOf(obj.getString("cat")) }
                        .getOrDefault(FactCategory.OTHER),
                    kind = obj.optString("kind", "Сведение"),
                    value = obj.optString("value"),
                    subject = obj.optString("subject"),
                    attributes = attrs,
                    sourceIds = (1..sources).map { "s$it" }.toSet(),
                    observations = obj.optInt("observations", 1)
                )
            )
        }
        val links = ArrayList<Link>()
        val linksArray = root.optJSONArray("links") ?: JSONArray()
        for (i in 0 until linksArray.length()) {
            val obj = linksArray.getJSONObject(i)
            links.add(
                Link(
                    from = obj.optString("from"),
                    to = obj.optString("to"),
                    relation = obj.optString("relation"),
                    weight = obj.optInt("weight", 1),
                    sourceCount = obj.optInt("sources", 1)
                )
            )
        }
        val statsObj = root.optJSONObject("stats") ?: JSONObject()
        return AggregatedData(
            query = root.optString("query"),
            facts = facts,
            links = links,
            stats = SearchStats(
                sourcesAttempted = statsObj.optInt("attempted"),
                sourcesResponded = statsObj.optInt("responded"),
                sourcesFailed = statsObj.optInt("failed"),
                rawFacts = statsObj.optInt("raw"),
                duplicatesRemoved = statsObj.optInt("dupes"),
                durationMs = statsObj.optLong("duration")
            )
        )
    }
}
