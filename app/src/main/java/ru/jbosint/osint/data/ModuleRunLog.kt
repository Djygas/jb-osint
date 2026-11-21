package ru.jbosint.osint.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.JbApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

data class ModuleRunEntry(
    val at: Long = System.currentTimeMillis(),
    val moduleId: String,
    val action: String,
    val ok: Boolean,
    val code: Int = 0,
    val durationMs: Long = 0,
    val facts: Int = 0,
    val message: String = ""
) {
    fun line(): String {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
        val status = if (ok) "OK" else "ERR"
        val meta = buildList {
            if (code != 0) add("http=$code")
            if (durationMs > 0) add("${durationMs}ms")
            if (facts > 0) add("facts=$facts")
        }.joinToString(" ")
        val tail = if (message.isBlank()) "" else " · ${DiagMask.text(message)}"
        return "[$ts] $status $moduleId/$action${if (meta.isBlank()) "" else " ($meta)"}$tail"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at)
        put("moduleId", moduleId)
        put("action", action)
        put("ok", ok)
        put("code", code)
        put("durationMs", durationMs)
        put("facts", facts)
        put("message", DiagMask.text(message).take(300))
    }

    companion object {
        fun fromJson(o: JSONObject): ModuleRunEntry = ModuleRunEntry(
            at = o.optLong("at", System.currentTimeMillis()),
            moduleId = o.optString("moduleId"),
            action = o.optString("action"),
            ok = o.optBoolean("ok"),
            code = o.optInt("code"),
            durationMs = o.optLong("durationMs"),
            facts = o.optInt("facts"),
            message = o.optString("message")
        )
    }
}

object ModuleRunLog {
    private const val MAX = 120
    private const val FILE = "module_run_log.json"
    private val entries = ConcurrentLinkedDeque<ModuleRunEntry>()
    @Volatile private var loaded = false
    private val lock = Any()

    private fun fileOrNull(): File? = runCatching {
        val ctx: Context = JbApp.instance
        File(ctx.filesDir, FILE)
    }.getOrNull()

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = fileOrNull()
            if (f != null && f.exists()) {
                runCatching {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) {
                        val e = ModuleRunEntry.fromJson(arr.optJSONObject(i) ?: continue)
                        entries.addLast(e)
                    }
                    while (entries.size > MAX) entries.pollFirst()
                }
            }
            loaded = true
        }
    }

    private fun persist() {
        val f = fileOrNull() ?: return
        runCatching {
            val arr = JSONArray()
            snapshot().forEach { arr.put(it.toJson()) }
            f.writeText(arr.toString())
        }
    }

    fun add(entry: ModuleRunEntry) {
        ensureLoaded()
        val safe = entry.copy(message = DiagMask.text(entry.message).take(300))
        synchronized(lock) {
            entries.addFirst(safe)
            while (entries.size > MAX) entries.pollLast()
            persist()
        }
    }

    fun snapshot(): List<ModuleRunEntry> {
        ensureLoaded()
        return synchronized(lock) { entries.toList() }
    }

    fun clear() {
        ensureLoaded()
        synchronized(lock) {
            entries.clear()
            persist()
        }
    }
}
