package ru.jbosint.osint

import org.junit.Assert.*
import org.junit.Test
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.data.TeleSint
import ru.jbosint.osint.data.TelegramRules
import ru.jbosint.osint.data.TgDossier
import ru.jbosint.osint.data.TgExport

class TelegramParserTest {

    @Test
    fun parsesModesAndHints() {
        assertEquals(TelegramRules.Kind.USERNAME, TelegramRules.parse("@alice").kind)
        assertEquals(TelegramRules.Kind.USER_ID, TelegramRules.parse("123456789").kind)
        assertEquals(TelegramRules.Kind.MESSAGE_LINK, TelegramRules.parse("https://t.me/somechat/123").kind)
        assertEquals(TelegramRules.Kind.GROUP_STATS, TelegramRules.parse("@mychat", TelegramRules.Kind.GROUP_STATS).kind)
        assertEquals(TelegramRules.Kind.CONTACT_OR_RECENT, TelegramRules.parse("+79001234567\n@alice", TelegramRules.Kind.CONTACT_OR_RECENT).kind)
        assertEquals(TelegramRules.Kind.STICKER, TelegramRules.parse("CAACAgIAAxkBAA...", TelegramRules.Kind.STICKER).kind)
        assertEquals(TelegramRules.Kind.CHANNEL_FORWARD_POST, TelegramRules.parse("https://t.me/c/123/456", TelegramRules.Kind.CHANNEL_FORWARD_POST).kind)
    }

    @Test
    fun exportIncludesFactsWhenNoMembers() {
        val dossier = TgDossier(
            input = "@alice",
            kind = TelegramRules.Kind.USERNAME,
            title = "Alice",
            username = "alice",
            facts = listOf(RawFact(FactCategory.PERSON, "Telegram-профиль", "@alice", "Alice", sourceId = "telesint", apiConfirmed = true)),
            members = emptyList(),
            pagesLoaded = 0,
            hasMore = false,
            backends = listOf("TeleSINT"),
            warnings = listOf("Telelog: endpoint не найден")
        )
        val csv = TgExport.csv(dossier)
        assertTrue(csv.contains("Тип;Значение;Источник;Атрибуты"))
        assertTrue(csv.contains("Telegram-профиль"))
        val data = TgExport.data(dossier)
        assertEquals(1, data.facts.size)
        assertEquals(1, data.stats.sourcesFailed) // warnings mapped
        assertTrue(data.facts.first().apiConfirmed)
    }

    @Test
    fun exportPreservesProvenance() {
        val raw = RawFact(FactCategory.OTHER, "Статистика чата", "Chat", "chat", sourceId = "telelog", apiConfirmed = false, note = "нужна спецификация")
        val dossier = TgDossier("chat", TelegramRules.Kind.GROUP_STATS, "Chat", "chat", listOf(raw), emptyList(), 0, false, listOf("Telelog"))
        val fact = TgExport.data(dossier).facts.first()
        assertFalse(fact.apiConfirmed)
        assertTrue(fact.notes.any { it.contains("спецификация") })
    }
}
