package ru.jbosint.osint.data

import ru.jbosint.osint.core.model.RawFact

data class ConnectorResult(
    val facts: List<RawFact> = emptyList(),
    val warnings: List<String> = emptyList(),
    val sourceId: String? = null,
    val durationMs: Long = 0,
    val confirmed: Boolean = true
) {
    companion object {
        fun ok(sourceId: String, facts: List<RawFact>, durationMs: Long = 0, confirmed: Boolean = true) =
            ConnectorResult(facts = facts, sourceId = sourceId, durationMs = durationMs, confirmed = confirmed)

        fun warn(sourceId: String, warning: String, facts: List<RawFact> = emptyList(), confirmed: Boolean = false) =
            ConnectorResult(facts = facts, warnings = listOf(warning), sourceId = sourceId, confirmed = confirmed)
    }
}
