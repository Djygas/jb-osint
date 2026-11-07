package ru.jbosint.osint

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.FaceSearch

class FaceSearchTest {

    @Test
    fun `parses results array with percent similarity`() {
        val body = """
            {"results":[
              {"url":"https://vk.com/id1","title":"Иван","similarity":92},
              {"url":"https://ok.ru/profile/2","name":"Профиль","score":0.78},
              {"url":"not-a-url","similarity":99}
            ]}
        """.trimIndent()
        val hits = FaceSearch.parse(body)
        assertEquals(2, hits.size)
        assertEquals("https://vk.com/id1", hits.first().url)
        assertEquals(92, hits.first().similarity)
        assertEquals(78, hits[1].similarity)
        assertEquals("Профиль", hits[1].title)
    }

    @Test
    fun `parses nested envelope and drops weak matches`() {
        val body = JSONObject()
            .put("data", JSONObject()
                .put("matches", org.json.JSONArray().apply {
                    put(JSONObject().put("page_url", "https://site.example/a").put("confidence", 40))
                    put(JSONObject().put("page_url", "https://site.example/b").put("confidence", 67))
                }))
            .toString()
        val hits = FaceSearch.parse(body)
        assertEquals(1, hits.size)
        assertEquals("https://site.example/b", hits.first().url)
        assertEquals("Требует проверки", hits.first().status)
    }

    @Test
    fun `handles bare array and provider field`() {
        val body = """
            [{"link":"https://example.org/x","page_title":"Страница","match":0.95,"provider":"ProFaceFinder"}]
        """.trimIndent()
        val hit = FaceSearch.parse(body).single()
        assertEquals(95, hit.similarity)
        assertEquals("ProFaceFinder", hit.provider)
        assertEquals("Страница", hit.title)
    }

    @Test
    fun `garbage response yields no hits`() {
        assertTrue(FaceSearch.parse("<html>error</html>").isEmpty())
        assertTrue(FaceSearch.parse("{}").isEmpty())
    }

    @Test
    fun `status thresholds`() {
        assertEquals("Подтверждено", FaceSearch.statusFor(91, true))
        assertEquals("Требует проверки", FaceSearch.statusFor(91, false))
        assertEquals("Требует проверки", FaceSearch.statusFor(60, false))
        assertEquals("Не подтверждено", FaceSearch.statusFor(59, true))
    }
}
