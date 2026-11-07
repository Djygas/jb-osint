package ru.jbosint.osint.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.ReportMeta
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.core.model.SourceRef
import ru.jbosint.osint.core.report.Csv
import ru.jbosint.osint.core.report.HtmlReport

/** HTML-отчёт и CSV: экранирование, разделы, BOM и кавычки. */
class HtmlReportTest {

    private val meta = ReportMeta(
        analyst = "Аналитик",
        reportId = "R-1",
        createdAt = 1_760_000_000_000L,
        appVersion = "1.13.0",
        queryType = "Telegram-канал"
    )

    @Test
    fun htmlEscapesUserData() {
        val data = AggregatedData(
            query = "<script>alert(1)</script>",
            facts = listOf(
                Fact(
                    FactCategory.PERSON, "Профиль", "@a<b>&\"'", "субъект",
                    mapOf("Описание" to "<img src=x>"), setOf("telesint"), 1,
                    urls = setOf("https://t.me/a?x=1&y=2")
                )
            ),
            stats = SearchStats(sourcesAttempted = 1, sourcesResponded = 1),
            sources = listOf(SourceRef("telesint", "TeleSINT", "https://telesint.dev"))
        )
        val html = HtmlReport(meta, data).build()
        assertFalse(html.contains("<script>alert(1)"))
        assertTrue(html.contains("&lt;script&gt;alert(1)"))
        assertTrue(html.contains("@a&lt;b&gt;&amp;&quot;&#39;"))
        assertTrue(html.contains("&lt;img src=x&gt;"))
        assertTrue(html.contains("https://t.me/a?x=1&amp;y=2"))
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("<style>"))
    }

    @Test
    fun htmlHasSummarySourcesAndEmptyState() {
        val empty = HtmlReport(meta, AggregatedData(query = "@none")).build()
        assertTrue(empty.contains("Сводка"))
        assertTrue(empty.contains("По запросу не получено сведений"))

        val data = AggregatedData(
            query = "@chan",
            facts = listOf(
                Fact(FactCategory.OTHER, "Telegram-канал", "OSINT", "@chan", mapOf("Подписчиков" to "42"), setOf("telesint"), 2)
            ),
            stats = SearchStats(sourcesAttempted = 2, sourcesResponded = 1),
            sources = listOf(SourceRef("telesint", "TeleSINT", "https://telesint.dev"))
        )
        val html = HtmlReport(meta, data, intro = "Аудитория канала", factsTitle = "Профиль канала").build()
        assertTrue(html.contains("Аудитория канала"))
        assertTrue(html.contains("Профиль канала"))
        assertTrue(html.contains("TeleSINT"))
        assertTrue(html.contains("Подписчиков: 42"))
        assertTrue(html.contains("Требует проверки"))
    }

    @Test
    fun csvQuotesAndKeepsBom() {
        val text = Csv.of(
            listOf(
                listOf("ID", "Имя", "Комментарий"),
                listOf("1", "Иван", "строка; с разделителем"),
                listOf("2", "Пётр", "кавычка \"внутри\""),
                listOf("3", "Анна", "перенос\nстроки")
            )
        )
        assertTrue(text.startsWith("ID;Имя"))
        assertTrue(text.contains("\"строка; с разделителем\""))
        assertTrue(text.contains("\"кавычка \"\"внутри\"\"\""))
        assertTrue(text.contains("перенос строки"))
        assertTrue(text.endsWith("\r\n"))
        assertTrue(Csv.BOM == "\uFEFF")
    }
}
