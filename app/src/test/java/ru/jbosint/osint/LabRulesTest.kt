package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.lab.server.LabLanguage
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

    @Test
    fun detectsLanguageByExtensionAndShebang() {
        assertEquals(LabLanguage.PYTHON, LabRules.language("tools/a.py"))
        assertEquals(LabLanguage.NODE, LabRules.language("app.mjs"))
        assertEquals(LabLanguage.BASH, LabRules.language("run.sh"))
        assertEquals(LabLanguage.GO, LabRules.language("main.go"))
        assertEquals(LabLanguage.PHP, LabRules.language("index.php"))
        assertEquals(LabLanguage.PERL, LabRules.language("x.pl"))
        assertEquals(LabLanguage.RUBY, LabRules.language("x.rb"))
        assertEquals(LabLanguage.PYTHON, LabRules.language("tool", "#!/usr/bin/env python3"))
        assertEquals(LabLanguage.NODE, LabRules.language("tool", "#!/usr/bin/node"))
        assertEquals(LabLanguage.BASH, LabRules.language("tool", "#!/system/bin/sh"))
        assertEquals(LabLanguage.OTHER, LabRules.language("README.md"))
    }

    @Test
    fun blocksDangerousCommands() {
        assertTrue(LabRules.isDangerous("rm -rf /"))
        assertTrue(LabRules.isDangerous("dd if=/dev/zero of=/dev/block/sda"))
        assertTrue(LabRules.isDangerous("sudo apt update"))
        assertTrue(LabRules.isDangerous("mkfs.ext4 /dev/block/sda"))
        assertFalse(LabRules.isDangerous("sh tools/run.sh | head"))
        assertFalse(LabRules.isDangerous("python3 x.py"))
    }

    @Test
    fun knowsShellRuntimeAndDependencyFiles() {
        assertTrue(LabRules.runtimePaths(LabLanguage.BASH).contains("/system/bin/sh"))
        assertTrue(LabRules.hasDependencyFiles(setOf("requirements.txt")))
        assertTrue(LabRules.hasDependencyFiles(setOf("package.json")))
        assertFalse(LabRules.hasDependencyFiles(setOf("README.md")))
    }
}
