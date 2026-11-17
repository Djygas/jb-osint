package ru.jbosint.osint.data

/**
 * Журнал работы модулей.
 *
 * Каждый вызов коннектора оставляет запись: модуль, запрос, сколько фактов,
 * сколько заняло, ошибка. Журнал нужен, чтобы «ничего не найдено» отличать от
 * «модуль упал» и «модуль не запускался»: это разные причины и лечатся по-разному.
 */
data class JournalEntry(
    val at: Long,
    val moduleId: String,
    val query: String,
    val facts: Int,
    val ok: Boolean,
    val ms: Long,
    val note: String = ""
)

object SearchJournal {

    private const val LIMIT = 200
    private val entries = ArrayDeque<JournalEntry>()

    fun add(entry: JournalEntry) = synchronized(entries) {
        entries.addFirst(entry)
        while (entries.size > LIMIT) entries.removeLast()
    }

    fun record(moduleId: String, query: String, facts: Int, ok: Boolean, ms: Long, note: String = "") =
        add(JournalEntry(System.currentTimeMillis(), moduleId, query, facts, ok, ms, note))

    fun last(limit: Int = 40): List<JournalEntry> = synchronized(entries) {
        entries.take(limit)
    }

    fun clear() = synchronized(entries) { entries.clear() }

    /** Короткая сводка для отчёта: сколько модулей ответило и сколько молчало. */
    fun summary(query: String): String = synchronized(entries) {
        val relevant = entries.filter { it.query.equals(query, ignoreCase = true) }
        if (relevant.isEmpty()) return "журнал пуст"
        val answered = relevant.count { it.ok }
        val failed = relevant.size - answered
        "ответили: $answered, без данных: $failed"
    }
}
