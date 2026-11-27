package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.lab.model.LabResultCard
import ru.jbosint.lab.model.LabResultExport
import ru.jbosint.lab.model.LabResultParser

/** JSON-вывод скриптов → карточки и экспорт. Без Android. */
class LabResultTest {

    @Test
    fun parsesJsonArrayIntoCards() {
        val json = """
            [
              {"service":"Tele2","password":"qwerty","date":"2022-06","url":"https://example.org/1"},
              {"ресурс":"База A","пароль":"secret","дата":"2021","ссылка":"https://example.org/2"}
            ]
        """.trimIndent()
        val cards = LabResultParser.parse(json)
        assertEquals(2, cards?.size)
        assertEquals("Tele2", cards?.get(0)?.service)
        assertEquals("qwerty", cards?.get(0)?.password)
        assertEquals("База A", cards?.get(1)?.service)
        assertEquals("secret", cards?.get(1)?.password)
    }

    @Test
    fun parsesNestedResults() {
        val json = """{"results":[{"service":"S","password":"p","url":"https://x"}]}"""
        val cards = LabResultParser.parse(json)
        assertEquals(1, cards?.size)
        assertEquals("S", cards?.first()?.service)
    }

    @Test
    fun nonJsonIsNotACardList() {
        assertNull(LabResultParser.parse("plain text output"))
        assertNull(LabResultParser.parse(""))
    }

    @Test
    fun exportsCsvAndHtmlWithoutLeakingNothingExtra() {
        val cards = listOf(
            LabResultCard("Tele2", "qwerty", "2022-06", "https://example.org")
        )
        val csv = LabResultExport.toCsv(cards)
        assertTrue(csv.contains("Tele2"))
        assertTrue(csv.contains("qwerty"))
        assertTrue(csv.contains("service"))
        val html = LabResultExport.toHtml(cards)
        assertTrue(html.contains("Tele2"))
        assertTrue(html.contains("https://example.org"))
        val json = LabResultExport.toJson(cards)
        assertTrue(json.contains("\"service\""))
        assertTrue(json.contains("qwerty"))
    }
}
