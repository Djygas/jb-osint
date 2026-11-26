package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.lab.server.LabRules

/** Встроенная лаборатория: разбор ссылок, команд и метаданных. Без Android. */
class LabRulesTest {

    @Test
    fun parsesRepoVariants() {
        val plain = LabRules.parseRepo("github.com/Djygas/jb-osint")
        assertEquals("Djygas", plain?.owner)
        assertEquals("jb-osint", plain?.name)
        assertEquals("", plain?.ref)

        val tree = LabRules.parseRepo("https://github.com/a/b/tree/dev")
        assertEquals("dev", tree?.ref)

        val explicit = LabRules.parseRepo("https://github.com/a/b", "v1.0")
        assertEquals("v1.0", explicit?.ref)

        assertNull(LabRules.parseRepo("https://gitlab.com/a/b"))
        assertNull(LabRules.parseRepo("https://github.com/a/b", "bad ref"))
        assertNull(LabRules.parseRepo("https://github.com/a/b", "-x"))
    }

    @Test
    fun splitsQuotedArguments() {
        assertEquals(
            listOf("sh", "a b.sh", "x y"),
            LabRules.splitArgs("sh \"a b.sh\" 'x y'")
        )
        assertEquals(listOf("ls", "-la"), LabRules.splitArgs("  ls   -la "))
    }

    @Test
    fun blocksEscalationBinaries() {
        assertTrue(LabRules.isBlocked("sudo"))
        assertTrue(LabRules.isBlocked("/system/bin/rm"))
        assertTrue(LabRules.isBlocked("su"))
        assertFalse(LabRules.isBlocked("sh"))
        assertFalse(LabRules.isBlocked("python3"))
    }

    @Test
    fun readsButtonMetadata() {
        val meta = LabRules.buttonMeta(
            listOf("#!/system/bin/sh", "# JB_BUTTON: Проверить домен", "# JB_PARAM: домен", "echo ok")
        )
        assertEquals("Проверить домен", meta.title)
        assertEquals("домен", meta.param)
        assertNull(LabRules.buttonMeta(listOf("#!/bin/sh", "echo")).title)
    }

    @Test
    fun detectsScriptAndProjectTypes() {
        assertEquals("bash", LabRules.scriptType("tools/run.sh"))
        assertEquals("python", LabRules.scriptType("scan.py"))
        assertNull(LabRules.scriptType("README.md"))
        assertEquals("android", LabRules.projectType(setOf("build.gradle.kts")))
        assertEquals("python", LabRules.projectType(setOf("requirements.txt")))
        assertEquals("node", LabRules.projectType(setOf("package.json")))
        assertEquals("other", LabRules.projectType(setOf("README.md")))
    }
}
