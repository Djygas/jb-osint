package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.LeakCheckApi
import ru.jbosint.osint.data.QueryDetector
import ru.jbosint.osint.data.QueryKind

/**
 * LeakCheck: разбор ответа и построение запроса. Проверяется, что модуль
 * не выводит пароли и не выдумывает утечки, когда сервис их не нашёл.
 */
class LeakCheckTest {

    private val found = """
        {"success":true,"found":1675,
         "fields":["email","password","dob","phone","unknown_field"],
         "sources":[{"name":"KomplettFritid.no","date":"2021-02"},
                    {"name":"Carhartt.com","date":"2026-08"}]}
    """.trimIndent()

    @Test
    fun urlCarriesKeyCheckAndType() {
        val url = LeakCheckApi.url("example@example.com", QueryKind.EMAIL, "abc123")
        assertTrue(url!!.startsWith(LeakCheckApi.BASE + "?"))
        assertTrue(url.contains("key=abc123"))
        assertTrue(url.contains("check=example%40example.com"))
        assertTrue(url.contains("type=email"))
    }

    @Test
    fun domainAndPhoneUseTheirOwnType() {
        assertTrue(LeakCheckApi.url("example.com", QueryKind.DOMAIN, "k")!!.contains("type=domain"))
        assertTrue(LeakCheckApi.url("+79161234567", QueryKind.PHONE, "k")!!.contains("type=phone"))
    }

    @Test
    fun nameIsNotSentToService() {
        assertNull(LeakCheckApi.url("Иван Иванов", QueryDetector.detect("Иван Иванов"), "k"))
    }

    @Test
    fun sourcesBecomeFactsWithDate() {
        val facts = LeakCheckApi.parse(found, "example@example.com")
        val breaches = facts.filter { it.kind == "Утечка" }
        assertEquals(2, breaches.size)
        assertEquals("KomplettFritid.no", breaches.first().value)
        assertEquals("2021-02", breaches.first().attributes["Дата"])
    }

    @Test
    fun recordCountAndFieldTypesAreReported() {
        val facts = LeakCheckApi.parse(found, "example@example.com")
        assertEquals("1675", facts.first { it.kind == "Записей в утечках" }.value)
        val types = facts.first { it.kind == "Типы данных" }.value
        assertTrue(types.contains("почта"))
        assertTrue(types.contains("дата рождения"))
        // неизвестное поле остаётся как есть, но ничего не теряется
        assertTrue(types.contains("unknown_field"))
    }

    @Test
    fun passwordsAppearOnlyAsFieldNames() {
        val facts = LeakCheckApi.parse(found, "example@example.com")
        // отдельного факта с паролем нет — только перечисление типов данных
        assertTrue(facts.none { it.kind == "Пароль" })
        val types = facts.first { it.kind == "Типы данных" }.value
        assertEquals("пароль", types.split(", ").first { it == "пароль" })
    }

    @Test
    fun notFoundYieldsNothing() {
        assertTrue(LeakCheckApi.parse("""{"success":false,"error":"Not found"}""", "x@example.com").isEmpty())
    }

    @Test
    fun brokenBodyYieldsNothing() {
        assertTrue(LeakCheckApi.parse("<html>502</html>", "x@example.com").isEmpty())
    }

    @Test
    fun sourcesAreCappedAndDeduplicated() {
        val many = (1..20).joinToString(",") { """{"name":"Leak$it","date":"2020-01"}""" }
        val body = """{"success":true,"found":900,"fields":[],"sources":[$many]}"""
        val breaches = LeakCheckApi.parse(body, "x@example.com").filter { it.kind == "Утечка" }
        assertEquals(12, breaches.size)
        val summary = LeakCheckApi.parse(body, "x@example.com").first { it.kind == "Записей в утечках" }
        assertEquals("12 из 20", summary.attributes["Показано наборов"])
    }
}
