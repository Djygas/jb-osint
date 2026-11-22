package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.PageReader
import ru.jbosint.osint.data.Remote

/** Серверный фолбэк чтения страниц: разбор ответа /scrape и маркер источника. */
class ServerFallbackTest {

    private val answer = """
        {
          "status": 200,
          "url": "https://romashka.ru/contacts",
          "bytes": 1024,
          "blocked": false,
          "content": {
            "title": "ООО «Ромашка» — контакты",
            "description": "Контакты и реквизиты",
            "text": "Ромашка. Почта: info@romashka.ru. Телефон: +7 495 123-45-67. Подробности на сайте.",
            "links": ["https://romashka.ru/about", "javascript:void(0)", "https://vk.com/romashka"]
          }
        }
    """.trimIndent()

    @Test
    fun parsesServerContent() {
        val page = Remote.parseScrape(answer, "https://romashka.ru/")
        assertTrue(page.ok)
        assertEquals("https://romashka.ru/contacts", page.url)
        assertEquals("ООО «Ромашка» — контакты", page.title)
        assertEquals(2, page.links.size)
        assertEquals(Remote.SERVER_VIA, page.via)
        assertEquals("", page.error)
    }

    @Test
    fun blockedAnswerIsNotOk() {
        val page = Remote.parseScrape(
            """{"status":403,"blocked":true,"content":{"title":"Just a moment...","text":""}}""",
            "https://romashka.ru/"
        )
        assertFalse(page.ok)
        assertTrue(page.error.contains("403"))
        assertEquals(Remote.SERVER_VIA, page.via)
    }

    @Test
    fun brokenAnswerIsNotOk() {
        val page = Remote.parseScrape("<html>oops</html>", "https://romashka.ru/")
        assertFalse(page.ok)
        assertEquals(Remote.SERVER_VIA, page.via)
    }

    @Test
    fun emptyContentIsNotOk() {
        val page = Remote.parseScrape("""{"status":200,"blocked":false}""", "https://romashka.ru/")
        assertFalse(page.ok)
    }

    @Test
    fun serverPageMarksFactsWithMode() {
        val page = Remote.parseScrape(answer, "https://romashka.ru/")
        val facts = PageReader.facts(page)
        assertTrue(facts.isNotEmpty())
        assertEquals("серверный режим", facts.first().attributes["Получено"])
        assertTrue(facts.any { it.value == "info@romashka.ru" })
    }

    @Test
    fun localPageHasNoServerMarker() {
        val page = PageReader.parse(
            "<html><head><title>Локальная страница</title></head><body>Текст страницы достаточной длины для разбора.</body></html>",
            "https://local.example/"
        )
        assertTrue(page.ok)
        val facts = PageReader.facts(page)
        assertTrue(facts.isNotEmpty())
        assertFalse(facts.first().attributes.containsKey("Получено"))
    }
}
