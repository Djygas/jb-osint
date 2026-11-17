package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.ConnectionUi
import ru.jbosint.osint.data.CustomAuth
import ru.jbosint.osint.data.CustomModule
import ru.jbosint.osint.data.DiagMask
import ru.jbosint.osint.data.KeyIntake
import ru.jbosint.osint.data.KeyState
import ru.jbosint.osint.data.ProviderGuess
import ru.jbosint.osint.data.RepairPlan
import ru.jbosint.osint.data.RepairStep
import ru.jbosint.osint.data.SetupRoute

/** Подключение «по одному ключу» и план починки модулей — чистые функции. */
class KeyIntakeTest {

    @Test
    fun keepsKeyFromBareString() {
        val intake = KeyIntake.parse("  ghp_abcdefghijklmnopqrstuvwxyz012345  ")
        assertEquals("ghp_abcdefghijklmnopqrstuvwxyz012345", intake?.key)
        assertTrue(intake!!.hasKey)
        assertNull(intake.url)
    }

    @Test
    fun keepsAddressNextToKey() {
        val intake = KeyIntake.parse("dyxless api https://api.dyxless.im ключ abc12345xyz")
        assertEquals("https://api.dyxless.im", intake?.url)
        assertEquals("abc12345xyz", intake?.key)
        assertEquals("api.dyxless.im", intake?.host)
        assertTrue(intake!!.hint.contains("dyxless"))
    }

    @Test
    fun dropsPunctuationAroundToken() {
        val intake = KeyIntake.parse("Ключ: ABCDEF123456789.")
        assertEquals("ABCDEF123456789", intake?.key)
    }

    @Test
    fun rejectsBlankAndTooShort() {
        assertNull(KeyIntake.parse("   "))
        assertNull(KeyIntake.parse("ключ"))
        assertNull(KeyIntake.parse("abc12"))
    }

    @Test
    fun connectionStateAccumulatesSteps() {
        val ui = ConnectionUi(running = true).step("первый").step("второй")
        assertEquals(2, ui.lines.size)
        assertTrue(ui.running)
        assertFalse(ui.done)
    }
}

class ProviderGuessTest {

    @Test
    fun knownHostsMapToBuiltInModules() {
        assertEquals("github", ProviderGuess.byHost("https://api.github.com/user"))
        assertEquals("vk", ProviderGuess.byHost("https://api.vk.com/method/users.get"))
        assertEquals("hibp", ProviderGuess.byHost("https://haveibeenpwned.com/api/v3"))
        assertEquals("leakcheck", ProviderGuess.byHost("https://leakcheck.io/api/public"))
        assertEquals("leakcheck", ProviderGuess.byText("LeakCheck — утечки"))
        assertNull(ProviderGuess.byHost("https://example.org/api"))
    }

    @Test
    fun keyPrefixBeatsText() {
        assertEquals("github", ProviderGuess.byKeyPrefix("ghp_1234567890"))
        assertEquals("gitlab", ProviderGuess.byKeyPrefix("glpat-1234567890"))
        assertEquals("google", ProviderGuess.byKeyPrefix("AIzaSyA1234567890"))
        // Ключ проекта Dyxless v3: dyx3_<uuid>.<секрет>
        assertEquals(
            "dyxless",
            ProviderGuess.byKeyPrefix("dyx3_a5f4441d-f354-4260-9991-8a71fb06ac32.secretpart")
        )
        assertNull(ProviderGuess.byKeyPrefix("plainkey123456"))
    }

    @Test
    fun assistantKeysGoToAssistant() {
        assertEquals("hf", ProviderGuess.assistantByKey("hf_abcdefghij"))
        assertEquals("openrouter", ProviderGuess.assistantByKey("sk-or-v1-abcdefgh"))
        assertEquals("groq", ProviderGuess.assistantByKey("gsk_abcdefgh"))
        assertNull(ProviderGuess.assistantByKey("ghp_abcdefgh"))
    }

    @Test
    fun routeUsesPrefixThenHostThenWord() {
        val byPrefix = KeyIntake.parse("glpat-abcdefghijkl")!!
        assertTrue(ProviderGuess.route(byPrefix) is SetupRoute.BuiltIn)
        assertEquals("gitlab", (ProviderGuess.route(byPrefix) as SetupRoute.BuiltIn).id)

        val byHost = KeyIntake.parse("https://shodan.io ключ abcdefghijkl")!!
        assertEquals("shodan", (ProviderGuess.route(byHost) as SetupRoute.BuiltIn).id)

        val byWord = KeyIntake.parse("serpapi key abcdefghijkl")!!
        assertEquals("serpapi", (ProviderGuess.route(byWord) as SetupRoute.BuiltIn).id)

        val assistant = KeyIntake.parse("hf_abcdefghijkl")!!
        assertEquals("hf", (ProviderGuess.route(assistant) as SetupRoute.Assistant).providerId)

        val custom = KeyIntake.parse("https://api.example.org ключ abcdefghijkl")!!
        val route = ProviderGuess.route(custom) as SetupRoute.Custom
        assertEquals("https://api.example.org", route.url)
        assertEquals("Example", route.title)

        val unknown = KeyIntake.parse("abcdefghijklmnop")!!
        assertTrue(ProviderGuess.route(unknown) is SetupRoute.Unknown)
    }

    @Test
    fun titleFromHostIgnoresApiPrefix() {
        assertEquals("Dyxless", ProviderGuess.titleFromHost("api.dyxless.im"))
        assertEquals("Telelog", ProviderGuess.titleFromHost("www.telelog.info"))
    }
}

class RepairPlanTest {

    private fun module(url: String) = CustomModule(
        id = "m1",
        title = "Свой модуль",
        urlTemplate = url,
        auth = CustomAuth.PARAM,
        method = "GET",
        headerName = "X-API-Key",
        paramName = "api_key",
        enabled = true,
        createdAt = 0L,
        hasKey = true
    )

    @Test
    fun notFoundMeansRediscover() {
        val step = RepairPlan.forCustom(module("https://api.example.org/search?q={query}"), 404)
        assertTrue(step is RepairStep.Rediscover)
    }

    @Test
    fun rateLimitMeansWait() {
        assertTrue(RepairPlan.forCustom(module("https://api.example.org/search"), 429) is RepairStep.Manual)
    }

    @Test
    fun builtInHostMigratesInsteadOfRediscovering() {
        val step = RepairPlan.forCustom(module("https://api.dyxless.im/search"), 404)
        assertTrue(step is RepairStep.Migrate)
        assertEquals("dyxless", (step as RepairStep.Migrate).providerId)
    }

    @Test
    fun vkWantsUserToken() {
        val step = RepairPlan.forBuiltIn("vk", KeyState.INVALID, 0)
        assertTrue(step is RepairStep.Manual)
        assertTrue((step as RepairStep.Manual).reason.contains("Implicit Flow"))
    }

    @Test
    fun workingModuleIsNotBroken() {
        assertTrue(RepairPlan.forBuiltIn("github", KeyState.VALID, 0) is RepairStep.Manual)
        assertTrue(RepairPlan.forBuiltIn("github", KeyState.INVALID, 0) is RepairStep.Recheck)
    }
}

class DiagMaskTest {

    @Test
    fun masksSecretsInText() {
        val masked = DiagMask.text(
            "GET https://api.example.org?api_key=SECRET123&user=7 token=abc123def456 " +
                "Authorization: Bearer sk-live-1234567890 eyJhbGciOiJIUzI1NiJ9.eyJ1aWQiOjF9.sig123456"
        )
        assertFalse(masked.contains("SECRET123"))
        assertFalse(masked.contains("abc123def456"))
        assertFalse(masked.contains("sk-live-1234567890"))
        assertFalse(masked.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(masked.contains("api_key=***"))
        assertTrue(masked.contains("user=7"))
    }

    @Test
    fun masksSecretQueryValuesOnly() {
        val masked = DiagMask.url("https://api.telelog.info/search?token=JWT.JWT.JWT&q=79990001122")
        assertEquals("https://api.telelog.info/search?token=***&q=79990001122", masked)
    }
}
