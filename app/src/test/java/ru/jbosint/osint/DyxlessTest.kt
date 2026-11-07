package ru.jbosint.osint

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.data.DyxlessApi

/**
 * Dyxless — платный асинхронный API: важно, чтобы в запрос попадали
 * только точные идентификаторы и жёсткий лимит расхода, иначе модуль
 * молча спишет деньги за заведомо пустой поиск.
 */
class DyxlessTest {

    private fun request(query: String, cap: Double = 10.0): JSONObject =
        DyxlessApi.request(query, cap) ?: error("запрос не построен для «$query»")

    @Test
    fun emailBecomesRecordsSearchWithBudget() {
        val body = request("example@example.com")
        assertEquals("records.search", body.getString("product"))
        val condition = body.getJSONObject("query")
        assertEquals("email", condition.getString("field"))
        assertEquals("eq", condition.getString("op"))
        assertEquals("example@example.com", condition.getString("value"))
        assertEquals("10.000000", body.getJSONObject("billing").getString("maxAmount"))
        assertEquals("RUB", body.getJSONObject("billing").getString("currency"))
        assertEquals("all", body.getJSONArray("select").getString(0))
        assertEquals(50, body.getJSONObject("result").getInt("maxRecords"))
    }

    @Test
    fun phoneIsNormalisedToDigits() {
        val condition = request("+7 (999) 123-45-67").getJSONObject("query")
        assertEquals("phone", condition.getString("field"))
        assertEquals("+79991234567", condition.getString("value"))
    }

    @Test
    fun ipUsesIpField() {
        assertEquals("ip", request("203.0.113.10").getJSONObject("query").getString("field"))
    }

    @Test
    fun telegramNickUsesUsernameProduct() {
        val body = request("@synthetic_user")
        assertEquals("telegram.search", body.getString("product"))
        val condition = body.getJSONObject("query")
        assertEquals("username", condition.getString("field"))
        assertEquals("synthetic_user", condition.getString("value"))
        val select = body.getJSONArray("select")
        assertEquals("baseName", select.getString(0))
    }

    @Test
    fun telegramIdNeedsExplicitPrefix() {
        // 9–11 цифр без префикса — телефон; Telegram по ID только явно.
        val body = request("id:123456789")
        assertEquals("telegram.search", body.getString("product"))
        val condition = body.getJSONObject("query")
        assertEquals("id", condition.getString("field"))
        assertEquals("123456789", condition.getString("value"))
        assertNull(DyxlessApi.request("id:not-a-number", 10.0))
    }

    @Test
    fun fullNameBuildsThreeConditions() {
        val all = request("Иванов Иван Иванович").getJSONObject("query").getJSONArray("all")
        assertEquals(3, all.length())
        assertEquals("last_name", all.getJSONObject(0).getString("field"))
        assertEquals("Иванов", all.getJSONObject(0).getString("value"))
        assertEquals("first_name", all.getJSONObject(1).getString("field"))
        assertEquals("sur_name", all.getJSONObject(2).getString("field"))
    }

    @Test
    fun partialNameAndDomainAreNotSentToPaidApi() {
        // Одинокая фамилия и домен денег не стоят: Dyxless по ним не ищет.
        assertNull(DyxlessApi.request("Иван", 10.0))
        assertNull(DyxlessApi.request("example.com", 10.0))
    }

    @Test
    fun budgetIsClamped() {
        assertEquals("0.010000", request("example@example.com", 0.000_001)
            .getJSONObject("billing").getString("maxAmount"))
        assertEquals("10000.000000", request("example@example.com", 999_999.0)
            .getJSONObject("billing").getString("maxAmount"))
    }

    @Test
    fun recordParsedIntoContactFact() {
        val data = JSONArray(
            """[{"email":"a@b.c","phone":"79991234567","last_name":"Иванов",
               "first_name":"Иван","sur_name":"Иванович","table_name":"base_1"}]"""
        )
        val facts = DyxlessApi.parse(data, "a@b.c")
        assertEquals(1, facts.size)
        val fact = facts.first()
        assertEquals(FactCategory.CONTACT, fact.category)
        assertEquals("a@b.c", fact.value)
        assertEquals("Иванов Иван Иванович", fact.subject)
        assertEquals("Иванов", fact.attributes["Фамилия"])
        assertEquals("base_1", fact.attributes["База"])
        assertEquals("dyxless", fact.sourceId)
    }

    @Test
    fun extendedReportKeepsSectionNameAndFields() {
        val data = JSONArray(
            """[{"baseName":"Emails по телефону","fields":{"Телефон":"79991234567","Email":"a@b.c"}}]"""
        )
        val fact = DyxlessApi.parse(data, "+79991234567").first()
        assertEquals("Emails по телефону", fact.kind)
        assertEquals("a@b.c", fact.value)
        assertEquals(FactCategory.CONTACT, fact.category)
        assertEquals("79991234567", fact.attributes["Телефон"])
    }

    @Test
    fun emptyResultGivesNoFacts() {
        assertTrue(DyxlessApi.parse(JSONArray("[]"), "a@b.c").isEmpty())
        assertTrue(DyxlessApi.parse(JSONArray("""[{"fields":{}}]"""), "a@b.c").isEmpty())
    }

    @Test
    fun relativeResultUrlResolvedAgainstApiHost() {
        assertNotNull(DyxlessApi.request("a@bb.cc", 1.0))
        assertEquals(
            "https://api.dyxless.im/v3/searches/1/results",
            DyxlessApi.absolute("/v3/searches/1/results")
        )
        assertEquals(
            "https://other.example/v3/x",
            DyxlessApi.absolute("https://other.example/v3/x")
        )
    }
}
