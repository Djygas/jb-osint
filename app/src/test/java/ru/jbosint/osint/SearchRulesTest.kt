package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.Connectors
import ru.jbosint.osint.data.CustomModuleSpec
import ru.jbosint.osint.data.SearchEngine
import ru.jbosint.osint.data.SearchJournal
import ru.jbosint.osint.data.TelegramRules

/**
 * Правила, из-за которых поиск «молчал»: валидация Telegram-ника,
 * запуск открытых источников без ключей, журнал модулей и проверка
 * пользовательского модуля.
 */
class SearchRulesTest {

    // ------------------------------------------------------------ Telegram

    @Test
    fun validTelegramUsernames() {
        listOf("telegram", "a1234", "short", "UPPER_lower_123").forEach { value ->
            assertTrue("должен быть валиден: $value", TelegramRules.parse(value).valid)
        }
        assertEquals("toxa_1", TelegramRules.parse("@toxa_1").normalized)
    }

    @Test
    fun invalidTelegramUsernames() {
        listOf("a_1", "1start", "toolongusername_more_than_32_chars", "user-name", "user.name").forEach { value ->
            val result = TelegramRules.parse(value)
            assertFalse("должен быть невалиден: $value", result.valid)
            assertTrue("нет причины отказа для $value", result.error.isNotBlank())
        }
    }

    @Test
    fun numericTelegramIdAccepted() {
        val result = TelegramRules.parse("123456789")
        assertTrue(result.valid)
        assertEquals(TelegramRules.Kind.ID, result.kind)
    }

    @Test
    fun telegramLinkParsed() {
        val result = TelegramRules.parse("https://t.me/telegram")
        assertTrue(result.valid)
        assertEquals("telegram", result.normalized)
    }

    // ------------------------------------------------------------ открытые источники

    @Test
    fun emptyModuleListFallsBackToKeyless() {
        assertTrue(SearchEngine.needsFallback(emptyList()))
        assertTrue(SearchEngine.needsFallback(listOf("rdap")))
        assertFalse(SearchEngine.needsFallback(listOf("ddg")))
        assertFalse(SearchEngine.needsFallback(listOf("telesint")))
    }

    @Test
    fun everyFallbackModuleHasConnector() {
        assertTrue(SearchEngine.KEYLESS_FALLBACK.isNotEmpty())
        SearchEngine.KEYLESS_FALLBACK.forEach { id ->
            assertNotNull("нет коннектора для $id", Connectors.byId(id))
        }
    }

    // ------------------------------------------------------------ журнал

    @Test
    fun journalKeepsNewestFirstAndSummarises() {
        SearchJournal.clear()
        SearchJournal.record("ddg", "запрос", 3, true, 120)
        SearchJournal.record("github", "запрос", 0, false, 80, "HTTP 401")
        SearchJournal.record("page", "запрос", 5, true, 300)
        val last = SearchJournal.last()
        assertEquals(3, last.size)
        assertEquals("page", last.first().moduleId)
        assertEquals("ddg", last.last().moduleId)
        val summary = SearchJournal.summary("запрос")
        assertTrue(summary.contains("ответили: 2"))
        assertTrue(summary.contains("без данных: 1"))
        SearchJournal.clear()
        assertTrue(SearchJournal.last().isEmpty())
    }

    // ------------------------------------------------------------ свой модуль

    @Test
    fun customModuleRequiresHttpsAndPlaceholder() {
        assertNull(CustomModuleSpec.validate("telelog", "https://api.example.com/lookup/{query}"))
        assertNotNull(CustomModuleSpec.validate("telelog", "http://api.example.com/{query}"))
        assertNotNull(CustomModuleSpec.validate("telelog", "https://api.example.com/lookup"))
        assertNotNull(CustomModuleSpec.validate("", "https://api.example.com/{query}"))
        assertNotNull(CustomModuleSpec.validate("Funstat", "https://api.example.com/{query} extra"))
    }
}
