package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.TermuxSpec

/** Проверки Termux-слоя без Android: имена, пути, белый список, скрипт и разбор диагностики. */
class TermuxTest {

    @Test
    fun scriptNamesAreSanitized() {
        assertEquals("rae_manual.sh", TermuxSpec.sanitizeScriptName("rae_manual.sh"))
        assertEquals("wrapper.py", TermuxSpec.sanitizeScriptName(" wrapper.py "))
        assertNull(TermuxSpec.sanitizeScriptName("../../etc/passwd"))
        assertNull(TermuxSpec.sanitizeScriptName("../x.sh"))
        assertNull(TermuxSpec.sanitizeScriptName("evil"))
        assertNull(TermuxSpec.sanitizeScriptName(".hidden.sh"))
        assertNull(TermuxSpec.sanitizeScriptName("a b.sh"))
        assertNull(TermuxSpec.sanitizeScriptName("rm -rf.sh"))
        assertNull(TermuxSpec.sanitizeScriptName("x".repeat(65) + ".sh"))
    }

    @Test
    fun homeIsRestrictedToTermux() {
        assertEquals(TermuxSpec.DEFAULT_HOME, TermuxSpec.normalizeHome(""))
        assertEquals(TermuxSpec.DEFAULT_HOME, TermuxSpec.normalizeHome("   "))
        assertEquals(TermuxSpec.DEFAULT_HOME, TermuxSpec.normalizeHome("/sdcard"))
        assertEquals(TermuxSpec.DEFAULT_HOME, TermuxSpec.normalizeHome("/data/data/com.termux/files/usr"))
        assertEquals(TermuxSpec.DEFAULT_HOME, TermuxSpec.normalizeHome("/data/data/com.termux/files/home/../etc"))
        assertEquals(
            "${TermuxSpec.DEFAULT_HOME}/work",
            TermuxSpec.normalizeHome("${TermuxSpec.DEFAULT_HOME}/work/")
        )
    }

    @Test
    fun scriptPathStaysInsideScriptsDir() {
        val path = TermuxSpec.scriptPath(TermuxSpec.DEFAULT_HOME, "rae_manual.sh")
        assertEquals("${TermuxSpec.DEFAULT_HOME}/JB_OSINT_Scripts/rae_manual.sh", path)
        assertNull(TermuxSpec.scriptPath(TermuxSpec.DEFAULT_HOME, "../x.sh"))
        assertNull(TermuxSpec.scriptPath("/sdcard", "rae_manual.sh"))
    }

    @Test
    fun trustListMatchesExactNames() {
        assertTrue(TermuxSpec.isTrusted(TermuxSpec.DEFAULT_TRUSTED, "rae_manual.sh"))
        assertFalse(TermuxSpec.isTrusted(TermuxSpec.DEFAULT_TRUSTED, "other.sh"))
        assertFalse(TermuxSpec.isTrusted(TermuxSpec.DEFAULT_TRUSTED, "../rae_manual.sh"))
        assertFalse(TermuxSpec.isTrusted(emptyList(), "rae_manual.sh"))
    }

    @Test
    fun setupScriptHasRequiredSteps() {
        val script = TermuxSpec.setupScript()
        assertTrue(script.contains("pkg update"))
        assertTrue(script.contains("pkg install -y python"))
        assertTrue(script.contains("termux-setup-storage"))
        assertTrue(script.contains("allow-external-apps=true"))
        assertTrue(script.contains("crawl4ai"))
        assertTrue(script.contains("markitdown[all]"))
        assertTrue(script.contains("reverse-api-engineer[manual]"))
        assertTrue(script.contains("playwright install chromium"))
        assertTrue(script.contains("rae_manual.sh"))
        assertTrue(script.contains("api_client.py"))
        assertTrue(script.contains("JB_OSINT_Scripts"))
    }

    @Test
    fun setupScriptDoesNotEscapeTheApp() {
        val script = TermuxSpec.setupScript()
        // Приложение никогда не формирует команды с внешним вводом.
        assertFalse(script.contains("rm -rf /"))
        assertFalse(script.contains("curl | sh"))
    }

    @Test
    fun diagnosticsParsed() {
        val out = """
            JB_TERMUX_OK
            JB_PYTHON=Python 3.12.6
            JB_STORAGE_OK
            JB_SCRIPTS_OK
            JB_SHARED_OK
            JB_CRAWL4AI_OK
            JB_NO_MARKITDOWN
            JB_NO_RAE
            JB_NODE=v22.0.0
        """.trimIndent()
        val checks = TermuxSpec.parseDiagnostics(out).associateBy { it.id }
        assertTrue(checks.getValue("python").ok)
        assertEquals("Python 3.12.6", checks.getValue("python").detail)
        assertTrue(checks.getValue("storage").ok)
        assertTrue(checks.getValue("crawl4ai").ok)
        assertFalse(checks.getValue("markitdown").ok)
        assertFalse(checks.getValue("rae").ok)
        assertTrue(checks.getValue("node").ok)
    }

    @Test
    fun diagnosticsWithoutTermuxAreAllFailed() {
        val checks = TermuxSpec.parseDiagnostics("")
        assertTrue(checks.isNotEmpty())
        assertTrue(checks.all { !it.ok })
    }

    @Test
    fun diagnosticsCommandPrintsMarkers() {
        val cmd = TermuxSpec.diagnosticsCommand()
        assertTrue(cmd.contains("JB_TERMUX_OK"))
        assertTrue(cmd.contains("JB_NO_PYTHON"))
        assertTrue(cmd.contains("JB_RAE_OK"))
        assertTrue(cmd.contains("\$HOME"))
    }
}
