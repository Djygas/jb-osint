package ru.jbosint.osint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.aggregate.Aggregator
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.ReportMeta
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.core.report.JsonReport
import ru.jbosint.osint.core.report.PdfReport
import ru.jbosint.osint.core.report.ReportFonts
import ru.jbosint.osint.core.report.ReportNaming
import ru.jbosint.osint.core.report.XlsxReport
import java.util.zip.ZipInputStream

class AssistantReportTest {

    private val data = Aggregator.aggregate(
        "Иван Петров",
        listOf(
            RawFact(
                category = FactCategory.CONTACT,
                kind = "Почта",
                value = "ivan.petrov@example.ru",
                subject = "Иван Петров",
                attributes = emptyMap(),
                sourceId = "s1"
            ),
            RawFact(
                category = FactCategory.PERSON,
                kind = "Никнейм",
                value = "ivan_p",
                subject = "Иван Петров",
                attributes = emptyMap(),
                sourceId = "s2"
            )
        ),
        SearchStats(sourcesAttempted = 2, sourcesResponded = 2)
    )

    private val analysis = "Объект связан с двумя независимыми цифровыми следами.\nПочта подтверждена источником."

    private fun meta(analysis: String?, narrative: String? = null) = ReportMeta(
        analyst = "А. Соколов",
        reportId = ReportNaming.reportId("Иван Петров", 1_760_000_000_000L),
        createdAt = 1_760_000_000_000L,
        aiAnalysis = analysis.orEmpty(),
        aiNarrative = narrative.orEmpty()
    )

    @Test
    fun `json report contains ai block`() {
        val json = JsonReport(meta(analysis, "Резюме исследования для заказчика."), data).build()
        assertTrue(json.contains("\"ai_analysis\""))
        assertTrue(json.contains("\"analysis\": \"Объект связан"))
        assertTrue(json.contains("\"narrative\": \"Резюме исследования"))
        assertTrue(json.contains("\"model\": \"external\""))
    }

    @Test
    fun `json report omits empty ai block`() {
        val json = JsonReport(meta(null, null), data).build()
        assertFalse(json.contains("ai_analysis"))
    }

    @Test
    fun `xlsx report adds assistant sheet`() {
        val bytes = XlsxReport(meta(analysis, "Краткое резюме."), data).build()
        val entries = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                entry = zip.nextEntry
            }
        }
        val workbook = bytesToString(bytes, "xl/workbook.xml")
        assertTrue("Лист ИИ должен быть в книге", workbook.contains("ИИ-заключение"))
        assertTrue(entries.any { it.startsWith("xl/worksheets/") })
    }

    @Test
    fun `pdf report renders with ai section`() {
        val bytes = PdfReport(meta(analysis, "Краткое резюме исследования."), data, ReportFonts.fromClasspath()).build()
        assertTrue("PDF слишком мал: ${bytes.size}", bytes.size > 20_000)
        assertEquals("%PDF-1.7", String(bytes, 0, 8))
    }

    private fun bytesToString(bytes: ByteArray, name: String): String {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return ""
    }
}
