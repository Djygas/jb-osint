package ru.jbosint.osint.core.report

/**
 * Минимальный CSV-писатель: разделитель «;» (русская локаль Excel),
 * кавычки удваиваются, значения с разделителем/переносом строки берутся в кавычки.
 * BOM добавляется вызывающей стороной, если файл открывают в Excel.
 */
object Csv {

    const val BOM = "\uFEFF"

    fun of(rows: List<List<String>>, delimiter: Char = ';'): String = buildString {
        rows.forEach { row ->
            row.forEachIndexed { index, value ->
                if (index > 0) append(delimiter)
                append(cell(value, delimiter))
            }
            append("\r\n")
        }
    }

    private fun cell(value: String, delimiter: Char): String {
        val clean = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val needsQuotes = clean.contains(delimiter) || clean.contains('"') || clean.startsWith(" ") ||
            clean.endsWith(" ")
        if (!needsQuotes) return clean
        return "\"" + clean.replace("\"", "\"\"") + "\""
    }
}
