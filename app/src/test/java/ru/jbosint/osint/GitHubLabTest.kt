package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.GitHubLabConfig
import ru.jbosint.osint.data.GitHubLabSpec
import ru.jbosint.osint.data.LabProjectType

/** Правила GitHub-лаборатории: URL, ветка, хеш, скрипты и запреты. Без Android. */
class GitHubLabTest {

    private val config = GitHubLabConfig()

    @Test
    fun acceptsGithubRepoAndComputesDir() {
        val project = GitHubLabSpec.projectFor(config, "https://github.com/square/okhttp", "main")
        assertNotNull(project)
        assertEquals("https://github.com/square/okhttp", project?.url)
        assertEquals("main", project?.ref)
        assertTrue(project?.dir?.startsWith(config.workDir + "/") == true)
        assertEquals(16, project?.hash?.length)
    }

    @Test
    fun acceptsSchemeLessAndBrowserUrl() {
        assertEquals(
            "https://github.com/a/b",
            GitHubLabSpec.projectFor(config, "github.com/a/b", "")?.url
        )
        assertEquals(
            "https://github.com/a/b",
            GitHubLabSpec.projectFor(config, "http://github.com/a/b", "")?.url
        )
        assertEquals(
            "dev",
            GitHubLabSpec.projectFor(config, "https://github.com/a/b/tree/dev", "")?.ref
        )
        assertEquals(
            "v2",
            GitHubLabSpec.projectFor(config, "https://github.com/a/b/releases/tag/v2", "")?.ref
        )
    }

    @Test
    fun rejectsForeignHostAndBadRef() {
        assertNull(GitHubLabSpec.projectFor(config, "https://gitlab.com/a/b", ""))
        assertNull(GitHubLabSpec.projectFor(config, "https://github.com/a/b", "a..b"))
        assertNull(GitHubLabSpec.projectFor(config, "https://github.com/a/b", "-x"))
        assertNull(GitHubLabSpec.projectFor(config, "https://github.com/a/b", "bad ref"))
    }

    @Test
    fun hashIsStableAndDependsOnRef() {
        val a = GitHubLabSpec.hashFor("https://github.com/a/b", "main")
        val b = GitHubLabSpec.hashFor("https://github.com/a/b", "main")
        val c = GitHubLabSpec.hashFor("https://github.com/a/b", "dev")
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun forbiddenEscalationIsRejected() {
        assertTrue(GitHubLabSpec.isCommandForbidden("sudo apt update"))
        assertTrue(GitHubLabSpec.isCommandForbidden("echo x; doas ls"))
        assertFalse(GitHubLabSpec.isCommandForbidden("./gradlew assembleDebug"))
        assertFalse(GitHubLabSpec.isCommandForbidden("python3 -m venv .venv"))
    }

    @Test
    fun detectsProjectType() {
        assertEquals(
            LabProjectType.ANDROID,
            GitHubLabSpec.detectType(listOf("app/build.gradle.kts", "README.md"))
        )
        assertEquals(
            LabProjectType.PYTHON,
            GitHubLabSpec.detectType(listOf("requirements.txt"))
        )
        assertEquals(
            LabProjectType.NODE,
            GitHubLabSpec.detectType(listOf("package.json"))
        )
        assertEquals(LabProjectType.OTHER, GitHubLabSpec.detectType(listOf("README.md")))
    }

    @Test
    fun setupScriptWritesConfigWithSafePermissions() {
        val script = GitHubLabSpec.setupScript(config)
        assertTrue(script.contains("github_lab.json"))
        assertTrue(script.contains("chmod 600"))
        assertTrue(script.contains("JB_LAB_SETUP_OK"))
        assertTrue(script.contains("\nJBOSINT\n"))
    }

    @Test
    fun cloneScriptIsShallowAndSingleBranch() {
        val project = GitHubLabSpec.projectFor(config, "https://github.com/a/b", "v1.0")!!
        val script = GitHubLabSpec.cloneScript(config, project, verifyGpg = true)
        assertTrue(script.contains("--depth 1"))
        assertTrue(script.contains("--single-branch"))
        assertTrue(script.contains("--branch 'v1.0'"))
        assertTrue(script.contains("chmod 700"))
        assertTrue(script.contains("verify-commit"))
    }

    @Test
    fun runScriptHasTimeoutAndExitMarker() {
        val project = GitHubLabSpec.projectFor(config, "https://github.com/a/b", "")!!
        val script = GitHubLabSpec.runScript(config, project, "ls -la")
        assertTrue(script.contains("timeout 600"))
        assertTrue(script.contains("base64 -d"))
        assertTrue(script.contains("JB_LAB_EXIT="))
    }

    @Test
    fun clearAndExportScriptsUseProjectDir() {
        val project = GitHubLabSpec.projectFor(config, "https://github.com/a/b", "")!!
        assertTrue(GitHubLabSpec.clearScript(config, project).contains("rm -rf"))
        assertTrue(GitHubLabSpec.exportScript(project).contains("JB-Osint/reports"))
    }

    @Test
    fun configDecodeFallsBackOnUnsafePaths() {
        val raw = """{"work_dir":"/etc","timeout_sec":99999,"allowed_domains":["github.com"]}"""
        val decoded = GitHubLabConfig.decode(raw)
        assertEquals(GitHubLabConfig.DEFAULT_WORK_DIR, decoded.workDir)
        assertEquals(3600, decoded.timeoutSec)
        assertEquals(listOf("github.com"), decoded.allowedDomains)
    }
}
