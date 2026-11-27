package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.jbosint.osint.data.LeakRules

/** Разбор строк дампов: CSV, TXT и JSON. Без Android. */
class LeakDumpParserTest {

    @Test
    fun parsesCsvColumns() {
        val record = LeakRules.parseRecord(
            "a@x.ru;pw123;Tele2.ru;2022-06;https://example.org/dump",
            "a@x.ru"
        )
        assertEquals("pw123", record.password)
        assertEquals("Tele2.ru", record.service)
        assertEquals("2022-06", record.date)
        assertEquals("https://example.org/dump", record.sourceUrl)
        assertEquals("Email+Password", record.dataType)
    }

    @Test
    fun parsesTxtPasswordAtService() {
        val record = LeakRules.parseRecord("a@x.ru:pw@Tele2.ru", "a@x.ru")
        assertEquals("pw", record.password)
        assertEquals("Tele2.ru", record.service)
    }

    @Test
    fun parsesPipeSeparated() {
        val record = LeakRules.parseRecord("a@x.ru|pw|LinkedIn|2021-01", "a@x.ru")
        assertEquals("pw", record.password)
        assertEquals("LinkedIn", record.service)
        assertEquals("2021-01", record.date)
    }

    @Test
    fun parsesJsonLine() {
        val record = LeakRules.parseRecord(
            """{"email":"a@x.ru","password":"pw","service":"LinkedIn","date":"2021-05","source_url":"https://x"}""",
            "a@x.ru"
        )
        assertEquals("pw", record.password)
        assertEquals("LinkedIn", record.service)
        assertEquals("2021-05", record.date)
        assertEquals("https://x", record.sourceUrl)
    }

    @Test
    fun lineWithoutPasswordHasNoPassword() {
        val record = LeakRules.parseRecord("a@x.ru", "a@x.ru")
        assertNull(record.password)
        assertEquals("Email", record.dataType)
    }
}
