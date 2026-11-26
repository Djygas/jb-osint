package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.data.LeakRules
import ru.jbosint.osint.data.LeakScan

/**
 * Правила локального индекса утечек: только чистые функции, без Android.
 * Проверяем то, на чём держится критерий приёмки «поиск по дампу за секунды»
 * и обещание «пароли не хранятся».
 */
class LeakScanTest {

    @Test
    fun hashIsStableAndNormalized() {
        val a = LeakRules.hash("  Ivan.Petrov@Mail.RU ")
        val b = LeakRules.hash("ivan.petrov@mail.ru")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertFalse(a.contains("@"))
    }

    @Test
    fun hashDiffersForDifferentAddresses() {
        assertTrue(LeakRules.hash("a@x.ru") != LeakRules.hash("b@x.ru"))
    }

    @Test
    fun maskKeepsDomainOnly() {
        assertEquals("iv***@mail.ru", LeakRules.mask("ivan.petrov@mail.ru"))
        assertEquals("a***@x.ru", LeakRules.mask("ab@x.ru"))
        assertEquals("***", LeakRules.mask("ab"))
    }

    @Test
    fun emailsInFindsAndDeduplicates() {
        val line = "1;Ivan;ivan@mail.ru;ivan@mail.ru;+79991234567"
        assertEquals(listOf("ivan@mail.ru"), LeakRules.emailsIn(line))
    }

    @Test
    fun emailsInCapsAtThree() {
        val line = "a@x.ru b@x.ru c@x.ru d@x.ru"
        assertEquals(3, LeakRules.emailsIn(line).size)
    }

    @Test
    fun phoneInNormalizesDigits() {
        assertEquals("89991234567", LeakRules.phoneIn("8 (999) 123-45-67"))
        assertNull(LeakRules.phoneIn("8-99"))
    }

    @Test
    fun hasSecretDetectsPasswordColumn() {
        assertTrue(LeakRules.hasSecret("ivan@mail.ru;qwerty123", "ivan@mail.ru"))
        assertFalse(LeakRules.hasSecret("ivan@mail.ru", "ivan@mail.ru"))
        assertFalse(LeakRules.hasSecret("ivan@mail.ru;ivan", "ivan@mail.ru"))
    }

    @Test
    fun indexableByExtension() {
        assertTrue(LeakRules.indexable("dump.csv"))
        assertTrue(LeakRules.indexable("base.SQL"))
        assertFalse(LeakRules.indexable("photo.jpg"))
        assertFalse(LeakRules.indexable(".hidden.csv"))
        assertEquals("sql", LeakRules.format("base.SQL"))
    }

    @Test
    fun factsSurviveCacheRoundTrip() {
        val facts = listOf(
            RawFact(
                category = FactCategory.OTHER,
                kind = "Утечка",
                value = "iv***@mail.ru",
                subject = "ivan@mail.ru",
                attributes = mapOf("Дата" to "2024"),
                sourceId = "leakcheck"
            )
        )
        val restored = LeakScan.decodeFacts(LeakScan.encodeFacts(facts), "ivan@mail.ru")
        assertEquals(1, restored.size)
        assertEquals("iv***@mail.ru", restored[0].value)
        assertEquals("2024", restored[0].attributes["Дата"])
        assertEquals("leakcheck", restored[0].sourceId)
        assertFalse(restored[0].value.contains("qwerty"))
    }

    @Test
    fun chainStartsWithFreeSourceAndKeepsOrder() {
        assertEquals("hibp", LeakScan.CHAIN.first())
        assertEquals(listOf("hibp", "leakcheck", "hudsonrock", "dyxless"), LeakScan.CHAIN)
    }
}
