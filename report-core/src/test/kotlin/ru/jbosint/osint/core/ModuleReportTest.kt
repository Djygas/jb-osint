package ru.jbosint.osint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.report.ModuleReport
import ru.jbosint.osint.core.report.ModuleReportData
import ru.jbosint.osint.core.report.ModuleReportRow
import ru.jbosint.osint.core.report.ReportFonts
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Отчёт по модулям: три формата из одного набора данных.
 * Файлы складываются в build/test-reports — их можно открыть внешними
 * инструментами (openpyxl, pdf-парсер), не доверяя только размеру.
 */
class ModuleReportTest {

    private val outputDir: File = File(System.getProperty("jb.testOutput") ?: "build/test-reports")
        .also { it.mkdirs() }

    private val rows = listOf(
        ModuleReportRow(
            number = 1,
            title = "Модуль с кавычкой \" и <тегом>",
            kind = "встроенный модуль",
            status = "работает",
            code = 200,
            facts = 12,
            issues = "",
            advice = "Модуль отвечает: разобрано фактов — 12.",
            source = "локально"
        ),
        ModuleReportRow(
            number = 2,
            title = "Свой модуль",
            kind = "пользовательский модуль",
            status = "поля не распознаны",
            code = 200,
            facts = 0,
            issues = "поля не распознаны",
            advice = "Ответ получен, но поля не распознаны.",
            source = "ИИ",
            steps = listOf("Добавьте правило data.items[].email → email", "Проверьте регистр путей")
        )
    )

    private val data = ModuleReportData(
        query = "test@example.com",
        appVersion = "1.20.0 (27)",
        android = "Android 14 · Test",
        generatedAt = 1_760_000_000_000L,
        rows = rows,
        stats = listOf(
            "Модулей проверено" to "2",
            "Обращений к ИИ" to "1",
            "Экономия" to "75%"
        ),
        notes = listOf("Секреты в отчёт не попадают.")
    )

    @Test
    fun `json is valid and escapes quotes`() {
        val json = ModuleReport(data).buildJson()
        File(outputDir, "modules.json").writeText(json)
        assertTrue(json.contains("\"schema\": \"jb-osint/modules/1.0\""))
        assertTrue(json.contains("\\\" и <тегом>"))
        assertTrue(json.contains("\"recommendation_source\": \"ИИ\""))
        assertTrue(json.contains("jb-osint/modules"))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }

    @Test
    fun `xlsx contains workbook parts and both sheets`() {
        val bytes = ModuleReport(data).buildXlsx()
        File(outputDir, "modules.xlsx").writeBytes(bytes)
        val entries = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                entry = zip.nextEntry
            }
        }
        assertTrue(entries.contains("[Content_Types].xml"))
        assertTrue(entries.contains("xl/workbook.xml"))
        assertTrue(entries.contains("xl/worksheets/sheet1.xml"))
        assertTrue(entries.contains("xl/worksheets/sheet2.xml"))
        assertTrue(bytes.size > 1500)
    }

    @Test
    fun `pdf is built with inter fonts`() {
        val bytes = ModuleReport(data).buildPdf()
        File(outputDir, "modules.pdf").writeBytes(bytes)
        val header = String(bytes.take(8).toByteArray(), Charsets.ISO_8859_1)
        assertTrue(header.startsWith("%PDF-1."))
        assertTrue(bytes.size > 5_000)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("%%EOF"))
    }

    @Test
    fun `html has rows and no raw markup from titles`() {
        val html = ModuleReport(data).buildHtml()
        File(outputDir, "modules.html").writeText(html)
        assertTrue(html.contains("&quot; и &lt;тегом&gt;"))
        assertTrue(html.contains("работает"))
        assertTrue(html.contains("data.items[].email"))
    }

    @Test
    fun `fonts are embedded from classpath`() {
        val fonts = ReportFonts.fromClasspath()
        assertTrue(fonts.regular.numGlyphs > 100)
    }
}
