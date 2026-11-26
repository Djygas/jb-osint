package ru.jbosint.osint

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.data.LeakReportCodec
import ru.jbosint.osint.data.LeakReportRow
import ru.jbosint.osint.data.LeakReportSource
import ru.jbosint.osint.data.LeakRows
import ru.jbosint.osint.data.LeakRules
import ru.jbosint.osint.data.source.LeakOrigin
import ru.jbosint.osint.data.source.LeakRecord

/**
 * Схема `jb-osint/leak-scan/2.0`: пароль только из локального индекса,
 * происхождение записи, режим показа и обратная совместимость с 1.0.
 */
class LeakReportTest {

    @Test
    fun passwordParsedFromEmailPasswordLine() {
        assertEquals(
            "qwerty123",
            LeakRules.passwordIn("evgenberzinsh@gmail.com:qwerty123", "evgenberzinsh@gmail.com")
        )
        assertEquals(
            "P@ssw0rd!",
            LeakRules.passwordIn("a@x.ru;P@ssw0rd!", "a@x.ru")
        )
        assertEquals(
            "secret",
            LeakRules.passwordIn("a@x.ru,secret", "a@x.ru")
        )
    }

    @Test
    fun passwordRejectsPhoneAndDuplicateEmail() {
        assertNull(LeakRules.passwordIn("1;Ivan;ivan@mail.ru;ivan@mail.ru;+79991234567", "ivan@mail.ru"))
        assertNull(LeakRules.passwordIn("ivan@mail.ru;89991234567", "ivan@mail.ru"))
        assertNull(LeakRules.passwordIn("ivan@mail.ru", "ivan@mail.ru"))
    }

    @Test
    fun indexRowKeepsPasswordAndOrigin() {
        val fact = RawFact(
            category = FactCategory.OTHER,
            kind = "Утечка: почта",
            value = "user@x.ru",
            subject = "user@x.ru",
            attributes = mapOf(
                "where" to "Collection #1 (2019)",
                "password" to "secret1",
                "hadPassword" to "true"
            ),
            sourceId = "leak_index"
        )
        val row = LeakRows.fromFact(fact)
        assertEquals("user@x.ru", row?.value)
        assertEquals("secret1", row?.password)
        assertEquals("Collection #1 (2019)", row?.where)
        assertEquals("leak_index", row?.source)
        assertTrue(row?.hadPassword == true)
    }

    @Test
    fun apiRowNeverFillsPasswordButKeepsOrigin() {
        val fact = RawFact(
            category = FactCategory.OTHER,
            kind = "Утечка",
            value = "LinkedIn",
            subject = "user@x.ru",
            attributes = mapOf("Дата" to "2012-05-05"),
            sourceId = "hibp"
        )
        val row = LeakRows.fromFact(fact)
        assertNull(row?.password)
        assertEquals("LinkedIn", row?.where)
        assertEquals("hibp", row?.source)
        assertEquals("2012-05-05", row?.date)
        assertFalse(row?.hadPassword == true)
    }

    @Test
    fun apiRowMarksPasswordPresenceWithoutValue() {
        val fact = RawFact(
            category = FactCategory.OTHER,
            kind = "Утечка",
            value = "SomeBreach",
            subject = "user@x.ru",
            attributes = mapOf("Пароль" to "Qwerty123"),
            sourceId = "leakcheck"
        )
        val row = LeakRows.fromFact(fact)
        assertNull(row?.password)
        assertTrue(row?.hadPassword == true)
    }

    @Test
    fun exportHidesPasswordWhenDisabled() {
        val sources = listOf(
            LeakReportSource(
                id = "leak_index",
                title = "Локальный индекс",
                hits = 1,
                rows = listOf(
                    LeakReportRow(
                        value = "a@x.ru",
                        password = "topsecret",
                        where = "dump.txt",
                        source = "leak_index",
                        hadPassword = true
                    )
                )
            )
        )
        val json = JSONObject(LeakReportCodec.encode("a@x.ru", 1L, false, false, "", sources, false))
        assertEquals("jb-osint/leak-scan/2.0", json.getString("schema"))
        val row = json.getJSONArray("sources").getJSONObject(0)
            .getJSONArray("rows").getJSONObject(0)
        assertTrue(row.isNull("password"))
        assertTrue(row.getBoolean("hadPassword"))
        assertFalse(json.toString().contains("topsecret"))
    }

    @Test
    fun exportIncludesPasswordWhenEnabled() {
        val sources = listOf(
            LeakReportSource(
                id = "leak_index",
                title = "Локальный индекс",
                hits = 1,
                rows = listOf(
                    LeakReportRow(
                        value = "a@x.ru",
                        password = "topsecret",
                        where = "dump.txt",
                        source = "leak_index",
                        hadPassword = true
                    )
                )
            )
        )
        val json = JSONObject(LeakReportCodec.encode("a@x.ru", 1L, false, false, "", sources, true))
        val row = json.getJSONArray("sources").getJSONObject(0)
            .getJSONArray("rows").getJSONObject(0)
        assertEquals("topsecret", row.getString("password"))
        assertTrue(json.getBoolean("passwordMode"))
    }

    @Test
    fun parserReadsVersionOne() {
        val v1 = """
            {"schema":"jb-osint/leak-scan/1.0","query":"a@x.ru","createdAt":1,
             "sources":[{"id":"hibp","title":"HIBP","hits":1,
               "rows":[{"value":"a@x.ru","where":"LinkedIn","hadPassword":true}]}]}
        """.trimIndent()
        val report = LeakReportCodec.decode(v1)
        assertEquals("jb-osint/leak-scan/1.0", report?.schema)
        val row = report?.sources?.first()?.rows?.first()
        assertEquals("hibp", row?.source)
        assertNull(row?.password)
        assertEquals("LinkedIn", row?.where)
        assertTrue(row?.hadPassword == true)
    }

    @Test
    fun csvAndHtmlNeverContainPassword() {
        val sources = listOf(
            LeakReportSource(
                id = "leak_index",
                title = "Локальный индекс",
                hits = 1,
                rows = listOf(
                    LeakReportRow(
                        value = "a@x.ru",
                        password = "TOP_SECRET_VALUE",
                        where = "dump.txt",
                        source = "leak_index",
                        hadPassword = true
                    )
                )
            )
        )
        val csv = LeakReportCodec.toCsv(sources)
        assertFalse(csv.contains("TOP_SECRET_VALUE"))
        assertTrue(csv.contains("true"))
        val html = LeakReportCodec.toHtml("a@x.ru", 1L, sources)
        assertFalse(html.contains("TOP_SECRET_VALUE"))
    }

    @Test
    fun originPrefersWhereThenDatasets() {
        assertEquals(
            "Collection #1",
            LeakOrigin.from(LeakRecord("v", "s", mapOf("where" to "Collection #1")))
        )
        assertEquals(
            "Set",
            LeakOrigin.from(LeakRecord("v", "s", mapOf("Наборы данных" to "Set")))
        )
        assertEquals("", LeakOrigin.from(LeakRecord("v", "s")))
    }
}
