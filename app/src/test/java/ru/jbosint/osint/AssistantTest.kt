package ru.jbosint.osint

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.data.AssistantCatalog
import ru.jbosint.osint.data.AssistantClassification
import ru.jbosint.osint.data.AssistantParser
import ru.jbosint.osint.data.AssistantPrompts
import ru.jbosint.osint.data.WebResearch

class AssistantParserTest {

    private fun response(content: String): String =
        JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content))
                )
            )
            .toString()

    @Test
    fun extractsTextContent() {
        val body = response("Объект связан с двумя профилями.")
        assertEquals("Объект связан с двумя профилями.", AssistantParser.content(body))
    }

    @Test
    fun extractsContentFromArrayParts() {
        val body = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", "Первая часть. "))
                                .put(JSONObject().put("type", "text").put("text", "Вторая часть."))
                        )
                    )
                )
            )
            .toString()
        assertEquals("Первая часть. Вторая часть.", AssistantParser.content(body))
    }

    @Test
    fun rejectsErrorAndEmptyBodies() {
        assertNull(AssistantParser.content("{\"error\":{\"message\":\"unauthorized\"}}"))
        assertNull(AssistantParser.content("{\"choices\":[]}"))
        assertNull(AssistantParser.content("<html>401</html>"))
    }

    @Test
    fun stripsCodeFence() {
        val raw = "```json\n{\"type\": \"email\", \"relevance\": \"high\"}\n```"
        assertEquals("{\"type\": \"email\", \"relevance\": \"high\"}", AssistantParser.clean(raw))
    }

    @Test
    fun parsesSingleClassification() {
        val parsed = AssistantParser.classification("{\"type\": \"Phone\", \"relevance\": \"HIGH\"}")
        assertNotNull(parsed)
        assertEquals("phone", parsed!!.type)
        assertEquals("high", parsed.relevance)
        assertEquals("Телефон", parsed.typeLabel)
        assertEquals("высокая", parsed.relevanceLabel)
    }

    @Test
    fun unknownRelevanceFallsBackToMedium() {
        val parsed = AssistantParser.classification("{\"type\": \"username\", \"relevance\": \"critical\"}")!!
        assertEquals("medium", parsed.relevance)
    }

    @Test
    fun parsesBatchClassifications() {
        val raw = "Вот результат:\n[{\"type\":\"email\",\"relevance\":\"high\"},{\"type\":\"domain\",\"relevance\":\"medium\"}]"
        val list = AssistantParser.classifications(raw)
        assertEquals(2, list.size)
        assertEquals("email", list[0].type)
        assertEquals("domain", list[1].type)
    }

    @Test
    fun parsesBatchWrappedInObject() {
        val raw = "{\"results\":[{\"type\":\"ip\",\"relevance\":\"low\"}]}"
        val list = AssistantParser.classifications(raw)
        assertEquals(1, list.size)
        assertEquals("ip", list[0].type)
    }

    @Test
    fun classificationFallbackReturnsEmpty() {
        assertTrue(AssistantParser.classifications("не могу определить").isEmpty())
    }

    @Test
    fun parsesQueryInstructions() {
        val raw = "[{\"module\":\"профили\",\"query\":\"ivan_petrov\"},{\"module\":\"домены\",\"query\":\"example.ru\"}]"
        val list = AssistantParser.queries(raw)
        assertEquals(2, list.size)
        assertEquals("профили", list[0].module)
        assertEquals("ivan_petrov", list[0].query)
    }

    @Test
    fun queryInstructionsAreDeduplicatedAndLimited() {
        val array = JSONArray()
        repeat(9) { array.put(JSONObject().put("module", "профили").put("query", "ivan")) }
        assertTrue(AssistantParser.queries(array.toString()).size <= 1)
    }

    @Test
    fun parsesSearchStrings() {
        val raw = "[\"Иван Петров Москва\", \"ivan petrov vk\", \"ivan.petrov@example.ru\"]"
        val list = AssistantParser.searchStrings(raw)
        assertEquals(3, list.size)
        assertEquals("Иван Петров Москва", list[0])
    }

    @Test
    fun parsesSearchStringsFromLines() {
        val raw = "1. Иван Петров Москва\n2. ivan petrov vk\n"
        val list = AssistantParser.searchStrings(raw)
        assertTrue(list.contains("Иван Петров Москва"))
        assertTrue(list.contains("ivan petrov vk"))
    }

    @Test
    fun analysisRejectsTooShortAnswers() {
        assertNull(AssistantParser.analysis("Ок"))
        assertNotNull(AssistantParser.analysis("Объект исследования связан с тремя публичными профилями и доменом."))
    }

    @Test
    fun mapsErrorCodesToHumanText() {
        assertTrue(AssistantParser.error(401, "{}").contains("не принял ключ"))
        assertTrue(AssistantParser.error(402, "{}").contains("средств"))
        assertTrue(AssistantParser.error(404, "{}").contains("Модель не найдена"))
        assertTrue(AssistantParser.error(429, "{}").contains("Слишком много"))
        assertTrue(AssistantParser.error(503, "{}").contains("недоступен"))
    }

    @Test
    fun errorUsesServerMessage() {
        val message = AssistantParser.error(400, "{\"error\":{\"message\":\"max_tokens too large\"}}")
        assertTrue(message.contains("max_tokens too large"))
    }

    @Test
    fun categoryMappingCoversAllTypes() {
        assertEquals("Телефон", AssistantClassification("phone", "high").typeLabel)
        assertEquals("Почта", AssistantClassification("email", "high").typeLabel)
        assertEquals("Никнейм", AssistantClassification("username", "high").typeLabel)
        assertEquals("Домен", AssistantClassification("domain", "high").typeLabel)
        assertEquals("IP-адрес", AssistantClassification("ip", "high").typeLabel)
        assertEquals("Имя", AssistantClassification("person", "high").typeLabel)
        assertEquals("Прочее", AssistantClassification("other", "high").typeLabel)
    }
}

class AssistantPromptTest {

    private fun head(prompt: String): JSONObject =
        JSONObject(prompt.substringBefore("\n\n"))

    private val facts = listOf(
        Fact(
            category = ru.jbosint.osint.core.model.FactCategory.CONTACT,
            kind = "Почта",
            value = "ivan.petrov@example.ru",
            subject = "Иван Петров",
            attributes = mapOf("Профиль" to "https://example.ru/ivan"),
            sourceIds = setOf("s1", "s2"),
            observations = 2
        ),
        Fact(
            category = ru.jbosint.osint.core.model.FactCategory.PERSON,
            kind = "Никнейм",
            value = "ivan_p",
            subject = "Иван Петров",
            attributes = emptyMap(),
            sourceIds = setOf("s1"),
            observations = 1
        )
    )

    @Test
    fun analyzePayloadIsValidJsonWithFacts() {
        val prompt = AssistantPrompts.analyze("Иван Петров", facts, emptyList())
        val json = head(prompt)
        assertEquals("analyze", json.getString("task"))
        val data = json.getJSONArray("data")
        assertEquals(2, data.length())
        assertEquals("email", data.getJSONObject(0).getString("type"))
        assertEquals(2, data.getJSONObject(0).getInt("sources"))
        assertEquals(Confidence.PROBABLE.title, data.getJSONObject(0).getString("confidence"))
        assertTrue(prompt.contains("Иван Петров"))
    }

    @Test
    fun synthesizePayloadIncludesStatistics() {
        val data = AggregatedData(
            query = "Иван Петров",
            facts = facts,
            links = emptyList(),
            stats = ru.jbosint.osint.core.model.SearchStats(
                sourcesAttempted = 4,
                sourcesResponded = 3,
                rawFacts = 6
            )
        )
        val prompt = AssistantPrompts.synthesize("Иван Петров", data, "Аналитический вывод.")
        val json = head(prompt)
        assertEquals("synthesize", json.getString("task"))
        assertEquals("Аналитический вывод.", json.getString("analysis"))
        assertEquals(4, json.getJSONObject("statistics").getInt("sources_attempted"))
        assertTrue(prompt.contains("Ключевые выводы"))
    }

    @Test
    fun classifyPayloadIsJson() {
        val json = head(AssistantPrompts.classify("ivan_p"))
        assertEquals("classify", json.getString("task"))
        assertEquals("ivan_p", json.getString("input"))
    }

    @Test
    fun classifyBatchAddsArray() {
        val json = head(AssistantPrompts.classifyBatch(listOf("a@b.ru", "8.8.8.8")))
        assertEquals(2, json.getJSONArray("input").length())
    }

    @Test
    fun queryPayloadListsModules() {
        val prompt = AssistantPrompts.queries(
            "Иван Петров",
            listOf("профили" to "соцсети", "домены" to "DNS"),
            ""
        )
        val json = head(prompt)
        assertEquals("generate_queries", json.getString("task"))
        assertEquals(2, json.getJSONArray("modules").length())
        assertTrue(prompt.contains("строго JSON-массив"))
    }

    @Test
    fun researchPayloadMentionsDuckDuckGo() {
        val json = head(AssistantPrompts.researchQueries("Иван Петров", "Почта: a@b.ru"))
        assertEquals("search", json.getString("task"))
        assertEquals("duckduckgo", json.getJSONArray("sources").getString(0))
    }

    @Test
    fun everyRoleHasSystemPromptAndLimit() {
        ru.jbosint.osint.data.AssistantRole.entries.forEach { role ->
            assertTrue(AssistantPrompts.system(role).isNotBlank())
            assertTrue(role.maxTokens in 200..4000)
        }
    }

    @Test
    fun catalogUsesRealisticModelIds() {
        assertTrue(AssistantCatalog.models.isNotEmpty())
        AssistantCatalog.models.forEach { model ->
            assertTrue("ID должен содержать владельца: ${model.id}", model.id.contains("/"))
            assertFalse("Модели из ТЗ недоступны на роутере", model.id.contains("llama-2"))
        }
        assertEquals("openai/gpt-oss-20b", AssistantCatalog.DEFAULT_MODEL)
        assertTrue(AssistantCatalog.CHAT_URL.startsWith("https://"))
    }
}

class WebResearchTest {

    private val html = """
        <div class="result">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.ru%2Fivan&amp;rut=abc">Иван Петров — профиль</a>
          <a class="result__snippet" href="#">Публичный профиль <b>Ивана Петрова</b> с открытыми контактами.</a>
        </div>
        <div class="result">
          <a rel="nofollow" class="result__a" href="https://second.example/profile">Second &amp; Co</a>
          <a class="result__snippet" href="#">Второй результат без разметки.</a>
        </div>
    """.trimIndent()

    @Test
    fun parsesLinksAndSnippets() {
        val hits = WebResearch.parse(html)
        assertEquals(2, hits.size)
        assertEquals("https://example.ru/ivan", hits[0].url)
        assertEquals("Иван Петров — профиль", hits[0].title)
        assertTrue(hits[0].snippet.contains("Публичный профиль"))
        assertEquals("https://second.example/profile", hits[1].url)
        assertEquals("Second & Co", hits[1].title)
    }

    @Test
    fun skipsJavascriptRedirect() {
        val hits = WebResearch.parse(
            "<a class=\"result__a\" href=\"https://duckduckgo.com/y.js?ad=1\">Реклама</a>"
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun parsesLiveDuckDuckGoMarkup() {
        val html = javaClass.classLoader!!.getResource("ddg_sample.html")!!.readText()
        val hits = WebResearch.parse(html)
        assertTrue("Ожидалось не меньше 3 результатов, получено ${hits.size}", hits.size >= 3)
        hits.forEach { hit ->
            assertTrue("Ссылка должна быть абсолютной: ${hit.url}", hit.url.startsWith("https://"))
            assertFalse(hit.url.contains("duckduckgo.com/l/"))
            assertTrue(hit.title.isNotBlank())
        }
        assertTrue(hits[0].url.contains("wikipedia.org"))
    }

    @Test
    fun digestNumbersHits() {
        val digest = WebResearch.digest(
            listOf(
                ru.jbosint.osint.data.WebHit("Первый", "https://a.ru", "фрагмент"),
                ru.jbosint.osint.data.WebHit("Второй", "https://b.ru", "")
            )
        )
        assertTrue(digest.startsWith("1. Первый — фрагмент"))
        assertTrue(digest.contains("2. Второй"))
    }
}
