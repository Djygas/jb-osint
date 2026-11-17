package ru.jbosint.osint

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.TeleSint
import ru.jbosint.osint.data.TgDossier
import ru.jbosint.osint.data.TgExport
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.FactCategory

/**
 * TeleSINT и Telegram-парсер: формат ключа, разбор страницы участников,
 * сборка выгрузок. Сетевые вызовы не выполняются — проверяется только
 * детерминированная логика.
 */
class TeleSintTest {

    @Test
    fun keyFormatIs32UrlSafeChars() {
        assertTrue(TeleSint.looksLikeKey("a".repeat(32)))
        assertTrue(TeleSint.looksLikeKey("AbC123_-xyz".padEnd(32, 'q')))
        assertFalse(TeleSint.looksLikeKey("a".repeat(31)))
        assertFalse(TeleSint.looksLikeKey("a".repeat(33)))
        assertFalse(TeleSint.looksLikeKey("ключ-из-русских-букв".padEnd(32, 'a')))
        assertFalse(TeleSint.looksLikeKey(""))
    }

    @Test
    fun memberPageUsesPaginationEnvelope() {
        val json = JSONObject(
            """
            {"data":{"members":[
              {"id":"1001","username":"alice","first_name":"Алиса","last_name":"Иванова",
               "phone":"+79000000000","premium":true,"bot":false,"last_online":"2026-11-01"},
              {"id":"1002","username":"","first_name":"","last_name":"","last_online":"недавно"}
            ],
            "pagination":{"page":2,"page_size":200,"has_next_page":true,"total":5000}}}
            """.trimIndent()
        )
        val page = TeleSint.memberPageOf(json, 2, 200)
        assertEquals(2, page.members.size)
        assertEquals(2, page.page)
        assertEquals(200, page.pageSize)
        assertEquals(5000, page.total)
        assertTrue(page.hasNext)
        assertEquals("Алиса Иванова", page.members[0].displayName)
        assertEquals("1002", page.members[1].id)
        assertEquals("1002", page.members[1].displayName)
    }

    @Test
    fun memberPageWithoutPaginationFallsBackToPageSize() {
        val json = JSONObject(
            """{"data":[{"id":"1","username":"bob"},{"id":"2","username":"carl"}]}"""
        )
        val page = TeleSint.memberPageOf(json, 1, 2)
        assertEquals(2, page.members.size)
        assertEquals("@bob", "@" + page.members[0].username)
        assertTrue(page.hasNext)
    }

    @Test
    fun emptyMemberListIsNotAnError() {
        val page = TeleSint.memberPageOf(JSONObject("""{"data":{"members":[]}}"""), 1, 100)
        assertTrue(page.members.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test
    fun csvHasBomHeaderAndOneRowPerMember() {
        val dossier = dossier(
            members = listOf(
                TeleSint.Member("1", "alice", "Алиса", "Иванова", "+7900", true, false, "2026-11-01"),
                TeleSint.Member("2", "", "Борис", "", "", false, true, "")
            )
        )
        val csv = TgExport.csv(dossier)
        assertTrue(csv.startsWith("\uFEFF"))
        val lines = csv.removePrefix("\uFEFF").trim().split("\r\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("ID;Ник;Имя"))
        assertTrue(lines[1].contains("alice"))
        assertTrue(lines[1].contains("да"))
        assertTrue(lines[2].startsWith("2;;Борис"))
    }

    @Test
    fun csvQuotesValuesWithDelimiter() {
        val dossier = dossier(
            members = listOf(TeleSint.Member("1", "a", "Имя; с точкой", "", "", false, false, ""))
        )
        val row = TgExport.csv(dossier).trim().split("\r\n")[1]
        assertTrue(row.contains("\"Имя; с точкой\""))
    }

    @Test
    fun dataMergesDuplicatesAndCountsAudience() {
        val raw = listOf(
            raw("Telegram-канал", "OSINT-канал", mapOf("Подписчиков" to "1200")),
            raw("Участник Telegram", "Алиса", mapOf("Ник" to "@alice")),
            raw("Участник Telegram", "Алиса", mapOf("Телефон" to "+7900"))
        )
        val data = TgExport.data(dossier(facts = raw))
        assertEquals(2, data.facts.size)
        val group = data.facts.first { it.kind == "Telegram-канал" }
        assertEquals("1200", group.attributes["Подписчиков"])
        val alice = data.facts.first { it.kind == "Участник Telegram" }
        assertEquals(2, alice.observations)
        assertTrue(alice.attributes.containsKey("Телефон"))
        assertEquals("https://t.me/osint_channel", data.query)
        assertEquals(1, data.sources.size)
    }

    @Test
    fun fileNameIsSafeAndTimestamped() {
        val name = TgExport.fileName(dossier(username = "my/channel name"), "html")
        assertTrue(name.startsWith("tg_my_channel_name_"))
        assertTrue(name.endsWith(".html"))
        assertFalse(name.contains("/"))
    }

    private fun raw(kind: String, value: String, attrs: Map<String, String>): RawFact =
        RawFact(FactCategory.PERSON, kind, value, "OSINT-канал", attrs, "telesint", 1)

    private fun dossier(
        members: List<TeleSint.Member> = emptyList(),
        facts: List<RawFact> = emptyList(),
        username: String = "osint_channel"
    ) = TgDossier(
        channel = username,
        title = "OSINT-канал",
        username = username,
        membersCount = 1200,
        facts = facts,
        members = members,
        pagesLoaded = 1,
        hasMore = false,
        collectedAt = 1_760_000_000_000L
    )
}
