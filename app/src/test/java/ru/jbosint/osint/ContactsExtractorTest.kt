package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.ContactsExtractor

class ContactsExtractorTest {

    private val report = """
        ОТЧЁТ О ПОИСКЕ (внешний сервис)
        ФИО: Иванов Иван Иванович
        Дата рождения: 12.03.1985
        Электронная почта: work.ivan@mail.ru, ivan.backup@yandex.ru
        Телефон: +7 (950) 007-39-00 и 8 950 007 39 00
        Логин: ivan_work
        vk.com: ivanov77
    """.trimIndent()

    @Test
    fun `extracts contacts from a third-party report`() {
        val extracted = ContactsExtractor.extract(report)

        assertEquals(listOf("work.ivan@mail.ru", "ivan.backup@yandex.ru"), extracted.emails)
        // Два формата одного номера приводятся к канону и не дублируются.
        assertEquals(listOf("+79500073900"), extracted.phones)
        assertTrue(extracted.usernames.contains("ivan_work"))
        assertEquals("Иванов Иван Иванович", extracted.names.first())
        assertTrue(extracted.dates.contains("12.03.1985"))
        assertTrue(extracted.accounts.any { it.resource == "vk.com" && it.login == "ivanov77" })
        // Зацепки: 2 почты + 1 телефон + 1 ник (ник из строки vk.com идёт как аккаунт).
        assertEquals(4, extracted.pivots().size)
    }

    @Test
    fun `empty text gives empty result`() {
        val extracted = ContactsExtractor.extract("   ")
        assertTrue(extracted.isEmpty)
    }
}
