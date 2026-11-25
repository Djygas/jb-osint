package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.PluginInput
import ru.jbosint.osint.data.PluginParser
import ru.jbosint.osint.data.PluginRules
import java.io.File

/**
 * Маркетплейс: разбор ссылки, безопасные пути архива, шаблоны автонастройки
 * и чтение вывода скриптов. Код скриптов здесь не исполняется.
 */
class PluginRulesTest {

    @Test
    fun acceptsOnlyGithubHttps() {
        assertEquals("krokykode", PluginRules.parseRepo("https://github.com/krokykode/WEREWIKS")!!.owner)
        assertEquals("WEREWIKS", PluginRules.parseRepo("https://github.com/krokykode/WEREWIKS.git")!!.name)
        assertEquals("krokykode-werewiks", PluginRules.parseRepo("https://github.com/krokykode/WEREWIKS")!!.slug)
        assertNull(PluginRules.parseRepo("http://github.com/o/r"))
        assertNull(PluginRules.parseRepo("git@github.com:o/r.git"))
        assertNull(PluginRules.parseRepo("https://gitlab.com/o/r"))
        assertNull(PluginRules.parseRepo("https://github.com/o/r/tree/main"))
        assertNull(PluginRules.parseRepo("https://github.com/o/../../etc"))
        assertNull(PluginRules.parseRepo("просто текст"))
    }

    @Test
    fun zipUrlGoesToCodeload() {
        val repo = PluginRules.parseRepo("https://github.com/krokykode/WEREWIKS")!!
        assertEquals("https://codeload.github.com/krokykode/WEREWIKS/zip/HEAD", PluginRules.zipUrl(repo))
    }

    @Test
    fun zipSlipPathsAreRejected() {
        assertEquals("sherlock.py", PluginRules.safeRelative("sherlock.py"))
        assertEquals("tools/sub.py", PluginRules.safeRelative("tools/sub.py"))
        assertNull(PluginRules.safeRelative("../evil.py"))
        assertNull(PluginRules.safeRelative("/etc/passwd"))
        assertNull(PluginRules.safeRelative("a/../../b.py"))
        assertNull(PluginRules.safeRelative("a/b/c/d/e.py"))
        assertNull(PluginRules.safeRelative("bad name.py"))
    }

    @Test
    fun onlyScriptsAreTaken() {
        assertTrue(PluginRules.isScript("sherlock.py"))
        assertTrue(PluginRules.isScript("run.sh"))
        assertTrue(PluginRules.isScript("index.js"))
        assertFalse(PluginRules.isScript("photo.png"))
        assertFalse(PluginRules.isScript("binary.exe"))
        assertFalse(PluginRules.isScript("test_sherlock.py"))
    }

    @Test
    fun knownToolsGetTheirOwnCommandAndInput() {
        val (sherlock, known) = PluginRules.commandFor("sherlock.py")
        assertTrue(known)
        assertEquals("sherlock {query}", sherlock)
        assertEquals(PluginInput.USERNAME, PluginRules.inputOf("sherlock.py"))
        assertEquals(PluginParser.SHERLOCK, PluginRules.parserOf("sherlock.py"))

        val (harvester, harvesterKnown) = PluginRules.commandFor("theHarvester.py")
        assertTrue(harvesterKnown)
        assertEquals("theHarvester -d {query} -b duckduckgo", harvester)
        assertEquals(PluginInput.DOMAIN, PluginRules.inputOf("theHarvester.py"))
    }

    @Test
    fun unknownScriptIsMarkedForManualCheck() {
        val (command, known) = PluginRules.commandFor("my_search.py")
        assertFalse(known)
        assertEquals("python3 my_search.py {query}", command)
        assertEquals(PluginInput.ANY, PluginRules.inputOf("my_search.py"))
    }

    @Test
    fun scanBuildsModulesFromScriptsOnly() {
        val dir = File.createTempFile("plugin", "dir").let { file ->
            file.delete(); file.mkdirs(); file
        }
        try {
            File(dir, "sherlock.py").writeText("print('x')")
            File(dir, "helper.txt").writeText("ignore")
            File(dir, "tools").mkdirs()
            File(dir, "tools/sublist3r.py").writeText("print('x')")
            val modules = PluginRules.scanDir(dir)
            assertEquals(2, modules.size)
            assertTrue(modules.all { !it.enabled })
            assertEquals("Sherlock", modules.first { it.file == "sherlock.py" }.title)
            assertEquals("tools/sublist3r.py", modules.first { it.title == "Sublist3r" }.file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sherlockOutputBecomesProfiles() {
        val stdout = """
            [*] Checking username user123
            [+] Instagram: https://instagram.com/user123
            [+] GitHub: https://github.com/user123
            [-] Twitter: Not Found
        """.trimIndent()
        val hits = PluginRules.parseOutput(PluginParser.SHERLOCK, stdout)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.kind == "Профиль" })
        assertEquals("https://instagram.com/user123", hits.first().value)
    }

    @Test
    fun harvesterOutputGivesEmailsAndHosts() {
        val stdout = "info@example.com\n[+] www.example.com\nother@mail.ru"
        val emails = PluginRules.parseOutput(PluginParser.EMAILS, stdout)
        assertTrue(emails.any { it.value == "info@example.com" })
        assertTrue(emails.any { it.value == "other@mail.ru" })
        val hosts = PluginRules.parseOutput(PluginParser.HOSTS, "[+] www.example.com")
        assertTrue(hosts.any { it.value == "www.example.com" })
    }

    @Test
    fun emptyOutputGivesNothing() {
        assertTrue(PluginRules.parseOutput(PluginParser.SHERLOCK, "").isEmpty())
        assertTrue(PluginRules.parseOutput(PluginParser.EMAILS, "no addresses here").isEmpty())
        assertNotNull(PluginRules.parseRepo("https://github.com/a/b"))
    }
}
