package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import ru.jbosint.osint.data.AutoSetup
import ru.jbosint.osint.data.CustomAuth
import ru.jbosint.osint.data.DocHints
import ru.jbosint.osint.data.SourceLink

class AutoSetupTest {

    @Test
    fun sourceLinkParsesSiteBotAndRejectsGarbage() {
        val site = SourceLink.parse("https://Example.COM/api/v1?x=1")
        assertNotNull(site)
        assertEquals(SourceLink.Kind.SITE, site!!.kind)
        assertEquals("example.com", site.host)

        val bare = SourceLink.parse("service.ru")
        assertEquals("service.ru", bare?.host)

        val at = SourceLink.parse("@telesint_bot")
        assertEquals(SourceLink.Kind.TELEGRAM, at?.kind)
        assertEquals("telesint_bot", at?.telegram)

        val link = SourceLink.parse("https://t.me/s/osint_channel")
        assertEquals("osint_channel", link?.telegram)

        assertNull(SourceLink.parse(""))
        assertNull(SourceLink.parse("localhost"))
        assertNull(SourceLink.parse("@ab"))
    }

    @Test
    fun detectReportsExhaustedWithoutNetwork() = runBlocking {
        val link = SourceLink.parse("api.example.com")
        assertNotNull(link)
        val outcome = AutoSetup.detect(link!!, "secret", DocHints(), skip = 999)
        assertTrue(outcome.exhausted)
        assertTrue(!outcome.fixed)
        assertEquals(0, outcome.code)
        assertEquals(AutoSetup.MAX_ATTEMPTS, outcome.attempts)
    }

    @Test
    fun planTriesGetAndPostWithinLimit() {
        val plan = AutoSetup.plan(AutoSetup.templates("api.example.com")).take(AutoSetup.MAX_ATTEMPTS)
        assertEquals(AutoSetup.MAX_ATTEMPTS, plan.size)
        assertTrue(plan.any { it.second.method == "GET" && it.second.auth == CustomAuth.PARAM })
        assertTrue(plan.any { it.second.method == "POST" && it.second.auth == CustomAuth.JSON_BODY })
        assertTrue(plan.any { it.second.method == "POST" && it.second.auth == CustomAuth.BEARER })
        assertEquals(plan.size, plan.distinct().size)
    }

    @Test
    fun templatesUseHttpsAndQueryPlaceholder() {
        val list = AutoSetup.templates("api.example.com")
        assertTrue(list.isNotEmpty())
        assertTrue(list.size <= 8)
        list.forEach {
            assertTrue(it, it.startsWith("https://"))
            assertTrue(it, it.contains("{query}"))
        }
    }

    @Test
    fun hintTemplatesComeFirstAndAreDeduplicated() {
        val hints = DocHints(templates = listOf("https://api.example.com/v2/find?term={query}"))
        val list = AutoSetup.templates("api.example.com", hints)
        assertEquals("https://api.example.com/v2/find?term={query}", list.first())
        assertEquals(list.distinct(), list)
    }

    @Test
    fun variantsCoverGetAndPostAuthStyles() {
        val get = AutoSetup.getVariants()
        val post = AutoSetup.postVariants()
        assertEquals(setOf("GET"), get.map { it.method }.toSet())
        assertEquals(setOf("POST"), post.map { it.method }.toSet())

        val getAuths = get.map { it.auth }.toSet()
        assertTrue(getAuths.contains(CustomAuth.PARAM))
        assertTrue(getAuths.contains(CustomAuth.BEARER))
        assertTrue(getAuths.contains(CustomAuth.HEADER))
        assertTrue(post.map { it.auth }.toSet().contains(CustomAuth.JSON_BODY))
    }

    @Test
    fun preferredAuthIsTriedFirst() {
        val headers = AutoSetup.getVariants(listOf(CustomAuth.HEADER))
        assertEquals(CustomAuth.HEADER, headers.first().auth)
        val params = AutoSetup.postVariants(listOf(CustomAuth.PARAM))
        assertEquals(CustomAuth.JSON_BODY, params.first().auth)
    }

    @Test
    fun authTitleIsHumanReadable() {
        val json = AutoSetup.postVariants().first { it.auth == CustomAuth.JSON_BODY }
        assertTrue(AutoSetup.authTitle(json).contains("JSON"))
        val header = AutoSetup.getVariants().first { it.auth == CustomAuth.HEADER }
        assertTrue(AutoSetup.authTitle(header).contains("X-Api-Key"))
    }

    @Test
    fun probeQueryIsNeutral() {
        assertEquals("osint", AutoSetup.TEST_QUERY)
        assertTrue(AutoSetup.MAX_ATTEMPTS in 8..64)
    }
}
