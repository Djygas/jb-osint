package ru.jbosint.osint

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.PerplexityAgent

class PerplexityAgentTest {

    @Test
    fun `body carries preset, tools and limits`() {
        val body = PerplexityAgent.body(
            input = "Иван Петров",
            instructions = "Ты аналитик",
            preset = "high",
            tools = listOf("web_search"),
            model = "sonar-pro",
            maxTokens = 900
        )
        val json = JSONObject(body)
        assertEquals("Иван Петров", json.getString("input"))
        assertEquals("high", json.getString("effort"))
        assertEquals("sonar-pro", json.getString("model"))
        assertEquals(900, json.getInt("max_output_tokens"))
        assertEquals("web_search", json.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertFalse(json.has("response_format"))
        assertFalse(json.has("previous_response_id"))
    }

    @Test
    fun `structured body includes json schema`() {
        val json = JSONObject(
            PerplexityAgent.body("q", "i", "medium", listOf("web_search"), structured = true)
        )
        val format = json.getJSONObject("response_format")
        assertEquals("json_schema", format.getString("type"))
        val schema = format.getJSONObject("json_schema").getJSONObject("schema")
        assertTrue(schema.getJSONObject("properties").has("facts"))
        assertTrue(schema.getJSONObject("properties").has("sources"))
    }

    @Test
    fun `preset field can be renamed for api variants`() {
        val original = PerplexityAgent.body("q", "", "low", emptyList())
        val renamed = JSONObject(PerplexityAgent.withPresetField(original, "preset"))
        assertEquals("low", renamed.getString("preset"))
        assertFalse(renamed.has("effort"))
    }

    @Test
    fun `text reads output message parts`() {
        val body = """
            {"id":"resp_1","output":[
              {"type":"reasoning","summary":[]},
              {"type":"message","role":"assistant","content":[
                 {"type":"output_text","text":"Часть один. "},
                 {"type":"output_text","text":"Часть два."}
              ]}
            ]}
        """.trimIndent()
        assertEquals("Часть один. Часть два.", PerplexityAgent.text(body))
        assertEquals("resp_1", PerplexityAgent.responseId(body))
    }

    @Test
    fun `text prefers output_text shortcut`() {
        val body = """{"id":"r2","output_text":"Готовый ответ"}"""
        assertEquals("Готовый ответ", PerplexityAgent.text(body))
    }

    @Test
    fun `sources merge search results and citations`() {
        val body = """
            {"search_results":[
               {"title":"Профиль VK","url":"https://vk.com/id1","snippet":"страница"},
               {"title":"Без ссылки"}
            ],
             "citations":["https://example.org/a"]}
        """.trimIndent()
        val sources = PerplexityAgent.sources(body)
        assertEquals(2, sources.size)
        assertEquals("https://vk.com/id1", sources[0].url)
        assertEquals("https://example.org/a", sources[1].url)
    }

    @Test
    fun `report parses structured json and keeps response metadata`() {
        val payload = JSONObject()
            .put("id", "resp_9")
            .put("search_results", org.json.JSONArray().put(
                JSONObject().put("title", "Источник").put("url", "https://example.org/s")
            ))
            .put("output_text", JSONObject()
                .put("subject", "Иван Петров")
                .put("summary", "Найдены профили.")
                .put("facts", org.json.JSONArray().put(
                    JSONObject().put("type", "Профиль").put("value", "vk.com/id1")
                        .put("source", "VK").put("status", "Требует проверки")
                ))
                .put("sources", org.json.JSONArray().put(
                    JSONObject().put("title", "Страница").put("url", "https://example.org/s")
                ))
                .put("analysis", "Совпадение имени.")
                .put("limitations", "Один источник.")
                .toString())
            .toString()
        val report = PerplexityAgent.report(payload)
        assertEquals("Иван Петров", report.subject)
        assertEquals(1, report.facts.size)
        assertEquals("resp_9", report.responseId)
        assertEquals(1, report.sources.size)
        assertTrue(report.asText().contains("Установленные сведения"))
        assertTrue(report.asText().contains("https://example.org/s"))
    }

    @Test
    fun `report survives plain text answer`() {
        val body = """{"id":"r3","output_text":"Обычный текст без схемы"}"""
        val report = PerplexityAgent.report(body)
        assertEquals("Обычный текст без схемы", report.summary)
        assertTrue(report.facts.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `error payload becomes readable failure`() {
        PerplexityAgent.text("""{"error":{"message":"Invalid API key provided"}}""")
    }
}
