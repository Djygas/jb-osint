package ru.jbosint.osint

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.CustomResponseParser
import ru.jbosint.osint.data.DiagBlock
import ru.jbosint.osint.data.DiagMask
import ru.jbosint.osint.data.DiagReport
import ru.jbosint.osint.data.DiagSamples
import ru.jbosint.osint.data.JsonShape

class DiagnosticsTest {

    private val payload = """
        {"data":{"items":[
          {"email":"ivan@example.com","country":"RU","city":"Москва","provider":"mail.ru"},
          {"email":"petr@example.org","country":"KZ","city":"Алматы","provider":"gmail.com"}
        ]},"meta":{"total":2}}
    """.trimIndent()

    @Test
    fun parserKeepsServiceContext() {
        val facts = CustomResponseParser.parse(JSONObject(payload), "ivan@example.com", "custom_1")
        val email = facts.first { it.value == "ivan@example.com" }
        assertEquals("RU", email.attributes["country"])
        assertEquals("Москва", email.attributes["city"])
        assertEquals("mail.ru", email.attributes["provider"])
        assertFalse(email.attributes.containsKey("email"))
    }

    @Test
    fun urlMaskHidesSecretsOnly() {
        val masked = DiagMask.url("https://api.example.com/v1?q=test&api_key=SECRET&token=ABC&page=2")
        assertTrue(masked.contains("api_key=***"))
        assertTrue(masked.contains("token=***"))
        assertTrue(masked.contains("q=test"))
        assertTrue(masked.contains("page=2"))
        assertFalse(masked.contains("SECRET"))
    }

    @Test
    fun jsonMaskKeepsStructure() {
        val masked = DiagMask.json("""{"api_key":"SECRET","name":"Иван","nested":{"token":"T"}}""")
        assertFalse(masked.contains("SECRET"))
        assertTrue(masked.contains("\"api_key\": \"***\""))
        assertTrue(masked.contains("Иван"))
    }

    @Test
    fun shapeDescribesArraysAndTypes() {
        val shape = JsonShape.describe(payload)
        assertTrue(shape.contains("data (объект)"))
        assertTrue(shape.contains("data.items[] (массив, 2)"))
        assertTrue(shape.contains("data.items[0].email (строка)"))
    }

    @Test
    fun reportContainsEveryBlockAndHint() {
        val blocks = listOf(
            DiagBlock(
                index = 1, title = "Brave Search", kind = "встроенный модуль", enabled = true,
                endpoint = "https://api.search.brave.com", method = "стандартный запрос коннектора",
                code = 200, elapsedMs = 120, bytes = 900, checkDetail = "Активен: ключ принят",
                structure = "", facts = 3, fields = "title, url", sample = "Профиль: @ivan",
                error = null, advice = "работает"
            ),
            DiagBlock(
                index = 2, title = "Мой сервис", kind = "пользовательский модуль", enabled = true,
                endpoint = "GET https://api.example.com/search?q=x&token=***", method = "GET",
                code = 200, elapsedMs = 400, bytes = 512,
                checkDetail = "авторизация: Bearer-заголовок",
                structure = "data.items[] (массив, 2)", facts = 0, fields = "",
                sample = "{}", error = null,
                advice = "ответ получен, но поля не распознаны"
            )
        )
        val text = DiagReport.render("1.5.0 (6)", "15 · Pixel", "example.com", blocks, 0L)
        assertTrue(text.startsWith("JB OSINT · диагностика модулей"))
        assertTrue(text.contains("[1] Brave Search"))
        assertTrue(text.contains("Фактов: 3"))
        assertTrue(text.contains("[2] Мой сервис"))
        assertTrue(text.contains("Секреты скрыты"))
        assertTrue(text.contains("Итого: модулей 2"))
        assertTrue(text.contains("JSON-путь → поле"))
    }

    @Test
    fun diagnosticsUsesProviderSpecificSampleQuery() {
        // Общий «osint» для HIBP/IPinfo/urlscan заведомо пуст — проверять надо своим типом данных.
        assertEquals("test@example.com", DiagSamples.forProvider("hibp"))
        assertEquals("8.8.8.8", DiagSamples.forProvider("ipinfo"))
        assertEquals("example.com", DiagSamples.forProvider("urlscan"))
        assertTrue(DiagSamples.acceptedFor("hibp")!!.contains("email"))
        assertNull(DiagSamples.forProvider("unknown-provider"))
    }
}
