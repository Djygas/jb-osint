package ru.jbosint.osint

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.AdviceOrigin
import ru.jbosint.osint.data.AiCallEntry
import ru.jbosint.osint.data.AssistantStats
import ru.jbosint.osint.data.DiagBlock
import ru.jbosint.osint.data.ModuleAnalyzer
import ru.jbosint.osint.data.ModuleAnswerParser
import ru.jbosint.osint.data.ModuleApply
import ru.jbosint.osint.data.ModuleAssistantStore
import ru.jbosint.osint.data.ModuleCacheEntry
import ru.jbosint.osint.data.ModuleIssue
import ru.jbosint.osint.data.ModulePlanner
import ru.jbosint.osint.data.ModulePrompts
import ru.jbosint.osint.data.ModuleReportBuilder
import ru.jbosint.osint.data.ModulePromptItem
import ru.jbosint.osint.data.TokenEstimate
import java.io.File

/**
 * Логика ИИ-ассистента модулей без сети и Android: разбор ответов, кэш,
 * пакетная отправка и отчёт. Именно эти тесты доказывают, что экономия
 * токенов не выдумана: неоплаченные вызовы видны в счётчиках.
 */
class ModuleAssistantTest {

    private fun block(
        id: String = "hibp",
        title: String = "HIBP",
        kind: String = "встроенный модуль",
        code: Int = 200,
        facts: Int = 3,
        keyState: String = "VALID",
        error: String? = null,
        structure: String = "",
        fields: String = "",
        advice: String = "работает",
        elapsed: Long = 120L
    ) = DiagBlock(
        index = 1,
        title = title,
        kind = kind,
        enabled = true,
        endpoint = "адрес задан в коде модуля",
        method = "GET",
        code = code,
        elapsedMs = elapsed,
        bytes = 0,
        checkDetail = "Активен",
        structure = structure,
        facts = facts,
        fields = fields,
        sample = "",
        error = error,
        advice = advice,
        moduleId = id,
        keyState = keyState
    )

    // ------------------------------------------------------------ локальный разбор

    @Test
    fun `working module needs no ai`() {
        val issues = ModuleAnalyzer.issues(block())
        assertEquals(listOf(ModuleIssue.OK), issues)
        assertFalse(ModuleAnalyzer.needsAi(block(), issues))
    }

    @Test
    fun `missing key is explained locally`() {
        val b = block(keyState = "EMPTY", facts = 0, code = 0, advice = "ключ не задан")
        val issues = ModuleAnalyzer.issues(b)
        assertTrue(issues.contains(ModuleIssue.NO_KEY))
        assertFalse(ModuleAnalyzer.needsAi(b, issues))
        assertTrue(ModuleAnalyzer.localAdvice(b, issues).startsWith("Ключ не задан"))
    }

    @Test
    fun `http codes are classified without model`() {
        val cases = mapOf(
            403 to ModuleIssue.AUTH,
            404 to ModuleIssue.ENDPOINT,
            429 to ModuleIssue.RATE_LIMIT,
            500 to ModuleIssue.SERVER,
            422 to ModuleIssue.HTTP_CLIENT
        )
        cases.forEach { (code, expected) ->
            val b = block(kind = "пользовательский модуль", code = code, facts = 0, keyState = "")
            assertTrue("код $code", ModuleAnalyzer.issues(b).contains(expected))
        }
        // 5xx, 4xx-маршрут и лимит решаются правилом — модель не нужна.
        assertFalse(ModuleAnalyzer.needsAi(block(code = 500, facts = 0, keyState = ""), listOf(ModuleIssue.SERVER)))
        assertFalse(ModuleAnalyzer.needsAi(block(code = 404, facts = 0, keyState = ""), listOf(ModuleIssue.ENDPOINT)))
        assertFalse(ModuleAnalyzer.needsAi(block(code = 429, facts = 0, keyState = ""), listOf(ModuleIssue.RATE_LIMIT)))
        // Нестандартные параметры запроса — как раз случай для модели.
        assertTrue(ModuleAnalyzer.needsAi(block(code = 422, facts = 0, keyState = ""), listOf(ModuleIssue.HTTP_CLIENT)))
    }

    @Test
    fun `unparsed custom json asks ai but builtin empty does not`() {
        val custom = block(
            id = "c1", title = "Свой", kind = "пользовательский модуль", code = 200, facts = 0,
            keyState = "", error = "поля не распознаны", structure = "data (объект)", fields = ""
        )
        val customIssues = ModuleAnalyzer.issues(custom)
        assertTrue(customIssues.contains(ModuleIssue.UNPARSED))
        assertTrue(ModuleAnalyzer.needsAi(custom, customIssues))

        val builtin = block(id = "hunter", title = "Hunter", facts = 0, keyState = "VALID")
        val builtinIssues = ModuleAnalyzer.issues(builtin)
        assertTrue(builtinIssues.contains(ModuleIssue.EMPTY))
        assertFalse(ModuleAnalyzer.needsAi(builtin, builtinIssues))
    }

    @Test
    fun `fingerprint ignores counts but reacts to issues`() {
        val first = block(code = 200, facts = 0, keyState = "", kind = "пользовательский модуль",
            structure = "items[] (массив, 3)\nitems[0].email (строка)")
        val second = block(code = 200, facts = 0, keyState = "", kind = "пользовательский модуль",
            structure = "items[] (массив, 7)\nitems[0].email (строка)")
        val issues = ModuleAnalyzer.issues(first)
        assertEquals(ModuleAnalyzer.fingerprint(first, issues), ModuleAnalyzer.fingerprint(second, issues))
        assertNotEquals(
            ModuleAnalyzer.fingerprint(first, issues),
            ModuleAnalyzer.fingerprint(first.copy(code = 422), issues)
        )
    }

    // ------------------------------------------------------------------- кэш

    @Test
    fun `cache persists between instances and respects ttl`() {
        val file = File.createTempFile("jb-modules", ".json")
        val store = ModuleAssistantStore(file)
        val now = 1_760_000_000_000L
        store.put(
            ModuleCacheEntry(
                fingerprint = "fp1",
                moduleId = "c1",
                diagnosis = "Нужны правила разбора",
                steps = listOf("Добавьте data.items[].email → email"),
                provider = "deepseek",
                model = "deepseek-chat",
                tokens = 420,
                createdAt = now
            )
        )
        val restored = ModuleAssistantStore(file)
        val hit = restored.cached("fp1", now + 1000)
        assertEquals("Нужны правила разбора", hit?.diagnosis)

        restored.touch("fp1")
        val stats = restored.stats()
        assertEquals(1, stats.cacheHits)
        assertEquals(420, stats.tokensSaved)
        assertTrue(restored.cacheSize() >= 1)

        // Просроченная запись не отдаётся: рекомендация «стареет» за месяц.
        val aged = ModuleAssistantStore(file)
        assertNull(aged.cached("fp1", now + ModuleAssistantStore.TTL_MS + 1))
        file.delete()
    }

    @Test
    fun `journal records calls and totals tokens`() {
        val file = File.createTempFile("jb-modules-journal", ".json")
        val store = ModuleAssistantStore(file)
        store.recordCall(
            AiCallEntry(
                at = 1000L, purpose = "diagnose_modules", modules = listOf("A", "B"),
                promptChars = 600, answerChars = 300, tokensIn = 200, tokensOut = 100,
                provider = "openai", model = "gpt-4o-mini", ok = true
            )
        )
        store.recordCall(
            AiCallEntry(
                at = 2000L, purpose = "diagnose_modules", modules = listOf("C"),
                promptChars = 400, answerChars = 0, tokensIn = 130, tokensOut = 0,
                provider = "openai", model = "gpt-4o-mini", ok = false, note = "401"
            )
        )
        store.recordLocal(4)
        val stats = store.stats()
        assertEquals(1, stats.aiCalls)
        assertEquals(4, stats.localOnly)
        assertEquals(430, stats.tokensSpent)
        assertEquals(4, stats.avoidedCalls)
        assertEquals(2, store.journal().size)
        assertEquals("C", store.journal().first().modules.first())

        val restored = ModuleAssistantStore(file)
        assertEquals(1, restored.stats().aiCalls)
        file.delete()
    }

    // -------------------------------------------------------------- планировщик

    @Test
    fun `planner batches unknown modules and reuses cache`() {
        val file = File.createTempFile("jb-modules-plan", ".json")
        val store = ModuleAssistantStore(file)
        val blocks = (1..6).map { n ->
            block(
                id = "c$n", title = "Свой $n", kind = "пользовательский модуль",
                code = 200, facts = 0, keyState = "", error = "поля не распознаны",
                structure = "data.items[] (массив, $n)"
            )
        } + block(id = "hibp", title = "HIBP", facts = 5)

        val plan = ModulePlanner.plan(blocks, store, now = 5000L)
        assertEquals(7, plan.advices.size)
        assertEquals(6, plan.aiItems)
        assertEquals(2, plan.batches.size)
        assertEquals(5, plan.batches[0].size)
        assertEquals(1, plan.batches[1].size)
        assertTrue(plan.batches.all { batch -> batch.size <= ModulePlanner.BATCH_SIZE })
        assertEquals(1, plan.localCount)

        // Модель ответила — рекомендации попали в кэш.
        val batch = plan.batches.flatten()
        val answers = HashMap<Int, ModuleAnswerParser.Answer>()
        batch.forEach { item ->
            answers[item.number] = ModuleAnswerParser.Answer(
                cause = "Нестандартные поля ответа",
                steps = listOf("Добавьте data.items[].email → email"),
                rules = "data.items[].email → email"
            )
        }
        ModuleApply.cacheEntries(plan, answers, "deepseek", "deepseek-chat", 1200, now = 6000L)
            .forEach { store.put(it) }

        val second = ModulePlanner.plan(blocks, store, now = 7000L)
        assertEquals(6, second.cacheHits)
        assertFalse(second.needAi)
        assertTrue(second.advices.filter { it.origin == AdviceOrigin.CACHE }.all { it.advice.isNotBlank() })
        file.delete()
    }

    // ------------------------------------------------------------------- промт

    @Test
    fun `prompt is compact json and masks secrets`() {
        val items = (1..3).map { n ->
            ModulePromptItem(
                number = n, title = "Свой $n", kind = "пользовательский модуль",
                status = "поля не распознаны", code = 200, facts = 0,
                structure = "data (объект)", fields = "",
                error = "token=supersecret value rejected"
            )
        }
        val prompt = ModulePrompts.batch(items)
        val json = prompt.substringBefore("\n\n")
        val root = JSONObject(json)
        assertEquals("diagnose_modules", root.getString("task"))
        assertEquals(3, root.getJSONArray("modules").length())
        assertFalse(prompt.contains("supersecret"))
        assertTrue(prompt.contains("token=***"))
        assertTrue(prompt.length < 4000)
    }

    @Test
    fun `parser accepts fences single object and ignores unknown numbers`() {
        val raw = "```json\n[{\"n\":1,\"cause\":\"Причина\",\"steps\":[\"Шаг один\",\"Шаг два\"]," +
            "\"rules\":\"data.email → email\"},{\"n\":9,\"cause\":\"Лишний\"}]\n```"
        val parsed = ModuleAnswerParser.parse(raw, setOf(1, 2))
        assertEquals(1, parsed.size)
        val answer = parsed.getValue(1)
        assertEquals("Причина", answer.cause)
        assertEquals(2, answer.steps.size)
        assertEquals("data.email → email", answer.rules)

        val single = ModuleAnswerParser.parse("{\"n\":2,\"cause\":\"Нет прав\",\"steps\":\"Проверьте ключ\"}", setOf(2))
        assertEquals("Нет прав", single.getValue(2).cause)
    }

    @Test
    fun `apply replaces local advice with ai answer`() {
        val file = File.createTempFile("jb-modules-apply", ".json")
        val store = ModuleAssistantStore(file)
        val blocks = listOf(
            block(id = "c1", title = "Свой", kind = "пользовательский модуль", code = 422, facts = 0,
                keyState = "", error = "unprocessable")
        )
        val plan = ModulePlanner.plan(blocks, store)
        assertEquals(1, plan.batches.size)
        val answers = mapOf(
            plan.batches[0][0].number to ModuleAnswerParser.Answer(
                cause = "Сервис ждёт параметр q",
                steps = listOf("Передайте ключ в заголовке X-Api-Key"),
                rules = ""
            )
        )
        val updated = ModuleApply.apply(plan, answers, "openai", "gpt-4o-mini")
        assertEquals(AdviceOrigin.AI, updated[0].origin)
        assertEquals("Сервис ждёт параметр q", updated[0].advice)
        assertEquals(1, updated[0].steps.size)
        file.delete()
    }

    // ------------------------------------------------------------------ отчёт

    @Test
    fun `report counts working failing and empty modules`() {
        val file = File.createTempFile("jb-modules-report", ".json")
        val store = ModuleAssistantStore(file)
        val blocks = listOf(
            block(id = "hibp", title = "HIBP", facts = 4),
            block(id = "vk", title = "VK", facts = 0, keyState = "INVALID", code = 0, error = "ключ отклонён"),
            block(id = "hunter", title = "Hunter", facts = 0)
        )
        val plan = ModulePlanner.plan(blocks, store)
        val stats = AssistantStats(aiCalls = 1, cacheHits = 2, localOnly = 3, tokensSpent = 500, tokensSaved = 1500)
        val data = ModuleReportBuilder.data(
            query = "test", appVersion = "1.20.0", android = "Android 14",
            advices = plan.advices, stats = stats, now = 1000L
        )
        assertEquals(3, data.rows.size)
        val summary = data.stats.toMap()
        assertEquals("3", summary["Модулей проверено"])
        assertEquals("1", summary["Работают"])
        assertEquals("1", summary["Требуют настройки"])
        assertEquals("1", summary["Отвечают, но 0 фактов"])
        assertEquals("75%", summary["Экономия"])
        file.delete()
    }

    @Test
    fun `token estimate is stable`() {
        assertEquals(0, TokenEstimate.of(0))
        assertEquals(1, TokenEstimate.of(1))
        assertEquals(334, TokenEstimate.of(1000))
        assertEquals(67, TokenEstimate.of(200))
        val array = JSONArray().put(1).put(2)
        assertEquals(2, array.length())
    }
}
