package ru.jbosint.osint.data

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
}

object ModuleRunLog {
    private const val MAX = 120
    private val entries = ConcurrentLinkedDeque<ModuleRunEntry>()

    fun add(entry: ModuleRunEntry) {
        entries.addFirst(entry)
        while (entries.size > MAX) entries.pollLast()
    }

    fun snapshot(): List<ModuleRunEntry> = entries.toList()

    fun clear() = entries.clear()
}
