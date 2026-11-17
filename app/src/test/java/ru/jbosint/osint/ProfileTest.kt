package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.Link
import ru.jbosint.osint.data.HistoryEntry
import ru.jbosint.osint.profile.DossierTemplate
import ru.jbosint.osint.profile.MsgType
import ru.jbosint.osint.profile.ProfileBuilder
import ru.jbosint.osint.profile.ProfileCompare
import ru.jbosint.osint.profile.ProfileDiff
import ru.jbosint.osint.profile.ProfileExporter
import ru.jbosint.osint.profile.ProfileMessages
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Тесты модуля «Профиль пользователя»: сборка профиля из фактов, импорт
 * выгрузки сообщений, сравнение профилей и валидность форматов выгрузки.
 */
class ProfileTest {

    private fun entry(): HistoryEntry {
        val facts = listOf(
            Fact(
                category = FactCategory.PERSON,
                kind = "Telegram-профиль",
                value = "@toxa",
                subject = "Тоха",
                attributes = mapOf(
                    "ИД" to "123456789",
                    "Ник" to "@toxa",
                    "Ссылка" to "https://t.me/toxa",
                    "Premium" to "да",
                    "История имён" to "2026-11-16: Toxa → 2026-11-17: Toxa Plus"
                ),
                sourceIds = setOf("telesint"),
                observations = 1,
                firstSeen = 0L,
                urls = setOf("https://t.me/toxa")
            ),
            Fact(
                category = FactCategory.CONTACT,
                kind = "Телефон",
                value = "+79990001122",
                subject = "Тоха",
                attributes = emptyMap(),
                sourceIds = setOf("telesint", "leakcheck"),
                observations = 2,
                firstSeen = 0L,
                urls = emptySet()
            ),
            Fact(
                category = FactCategory.LOCATION,
                kind = "Город",
                value = "Москва",
                subject = "Тоха",
                attributes = emptyMap(),
                sourceIds = setOf("ipinfo"),
                observations = 1,
                firstSeen = 0L,
                urls = emptySet()
            )
        )
        return HistoryEntry(
            id = "e1",
            query = "@toxa",
            createdAt = 1_700_000_000_000L,
            analyst = "тест",
            data = AggregatedData(query = "@toxa", facts = facts, links = emptyList()),
            demo = false,
            queryType = "никнейм"
        )
    }

    private fun messages(): List<ru.jbosint.osint.profile.ProfileMessage> =
        listOf(
            ru.jbosint.osint.profile.ProfileMessage(1_700_000_000_000L, "Тоха", "привет, смотри https://jbvpn.ru", MsgType.LINK),
            ru.jbosint.osint.profile.ProfileMessage(1_700_000_600_000L, "Тоха", "спасибо, всё работает", MsgType.TEXT, replyTo = "Марина"),
            ru.jbosint.osint.profile.ProfileMessage(1_700_100_000_000L, "Марина", "ты дурак, сломал прод", MsgType.TEXT)
        )

    @Test
    fun profileHasIdentifiersAndNames() {
        val profile = ProfileBuilder.build(entry(), messages = messages())
        assertEquals("toxa", profile.subject.username)
        assertEquals("123456789", profile.subject.id)
        assertTrue(profile.identifiers.any { it.kind == "Телефон" && it.value == "+79990001122" })
        assertEquals(2, profile.names.size)
        assertEquals("Toxa", profile.names.first().name)
        assertEquals("2026-11-17", profile.names.last().date)
        assertEquals("Москва", profile.summary.geo)
    }

    @Test
    fun profileCountsMessagesAndReputation() {
        val profile = ProfileBuilder.build(entry(), messages = messages())
        assertEquals(3, profile.summary.messages)
        assertTrue(profile.behavior.hasData)
        assertTrue(profile.reputation.toxicity > 0) // «дурак» в словаре токсичности
        assertTrue(profile.reputation.trust <= 100)
        assertTrue(profile.content.words.isNotEmpty())
        assertTrue(profile.content.links.any { it.domain == "jbvpn.ru" })
    }

    @Test
    fun missingBlocksAreReported() {
        val profile = ProfileBuilder.build(entry())
        assertTrue(profile.missing.contains("Поведенческий анализ"))
        assertTrue(profile.missing.contains("Медиа и сообщения"))
        assertTrue(profile.messages.isEmpty())
    }

    @Test
    fun csvImportParsesTelegramExport() {
        val csv = """
            Дата;Время;Отправитель;Текст;Тип;Кому ответ
            17.11.2026;10:15:00;Тоха;привет;Текст;
            17.11.2026;10:16:00;Марина;"ответ, с запятой";Текст;Тоха
            17.11.2026;10:17:00;Тоха;https://jbvpn.ru;Ссылка;
        """.trimIndent()
        val result = ProfileMessages.parseCsv(csv)
        assertNotNull(result.messages)
        assertEquals(3, result.messages.size)
        assertEquals("Марина", result.messages[1].author)
        assertEquals("ответ, с запятой", result.messages[1].text)
        assertEquals("Тоха", result.messages[1].replyTo)
        assertEquals(MsgType.LINK, result.messages[2].type)
    }

    @Test
    fun csvImportRejectsWrongHeader() {
        val result = ProfileMessages.parseCsv("a;b;c\n1;2;3")
        assertEquals(0, result.messages.size)
        assertNotNull(result.error)
    }

    @Test
    fun graphMlIsWellFormed() {
        val profile = ProfileBuilder.build(entry(), messages = messages())
        val xml = String(ProfileExporter.graphMl(profile), Charsets.UTF_8)
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<graphml"))
        assertTrue(xml.contains("<node id=\"toxa\""))
        assertTrue(xml.trimEnd().endsWith("</graphml>"))
    }

    @Test
    fun xlsxHasFiveSheets() {
        val profile = ProfileBuilder.build(entry(), messages = messages())
        val bytes = ProfileExporter.xlsx(profile)
        val entries = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                entry = zip.nextEntry
            }
        }
        assertTrue(entries.contains("xl/workbook.xml"))
        assertTrue(entries.contains("xl/worksheets/sheet1.xml"))
        assertTrue(entries.contains("xl/worksheets/sheet5.xml"))
    }

    @Test
    fun csvExportsContainDisclaimer() {
        val profile = ProfileBuilder.build(entry(), messages = messages())
        val messagesCsv = String(ProfileExporter.messagesCsv(profile), Charsets.UTF_8)
        val contactsCsv = String(ProfileExporter.contactsCsv(profile), Charsets.UTF_8)
        assertTrue(messagesCsv.contains(ProfileExporter.DISCLAIMER))
        assertTrue(contactsCsv.contains(ProfileExporter.DISCLAIMER))
        assertTrue(messagesCsv.contains("Отправитель"))
    }

    @Test
    fun anonymizedExportHidesValues() {
        val profile = ProfileBuilder.build(entry(), messages = messages())
        val csv = String(ProfileExporter.messagesCsv(profile, anonymize = true), Charsets.UTF_8)
        assertTrue(csv.contains("Пользователь 1"))
        assertTrue(!csv.contains("Марина"))
    }

    @Test
    fun diffDetectsChanges() {
        val profile = ProfileBuilder.build(entry(), messages = messages())
        val changed = profile.copy(
            subject = profile.subject.copy(username = "toxa_new"),
            groups = listOf("Новый канал")
        )
        val changes = ProfileDiff.diff(profile, changed)
        assertTrue(changes.any { it.contains("Сменился ник") })
        assertTrue(changes.any { it.contains("Новые группы") })
        assertTrue(ProfileDiff.diff(null, profile).isEmpty())
    }

    @Test
    fun compareFindsCommonContacts() {
        val first = ProfileBuilder.build(entry(), messages = messages())
        val second = first.copy(id = "e2", subject = first.subject.copy(title = "Второй"))
        val text = ProfileCompare.compare(first, second)
        assertTrue(text.contains("Общие знакомые"))
    }

    @Test
    fun dossierHtmlHasTemplateSections() {
        val profile = ProfileBuilder.build(entry(), messages = messages())
        val html = String(
            ProfileExporter.dossierHtml(profile, DossierTemplate.ACTIVITY),
            Charsets.UTF_8
        )
        assertTrue(html.contains("Сводка активности"))
        assertTrue(html.contains(ProfileExporter.DISCLAIMER))
    }

    @Test
    fun demoProfileFillsAllBlocks() {
        val profile = ProfileBuilder.build(
            entry = null,
            messages = ProfileMessages.demo(),
            demo = true,
            query = "@demo_subject"
        )
        assertTrue(profile.summary.messages > 100)
        assertTrue(profile.behavior.hasData)
        assertTrue(profile.content.hasData)
        assertTrue(profile.contacts.isNotEmpty())
        assertTrue(profile.nodes.size > 1)
        assertTrue(profile.reputation.notes.isNotEmpty())
    }
}
