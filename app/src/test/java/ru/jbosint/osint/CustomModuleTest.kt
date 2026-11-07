package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.CustomAuth
import ru.jbosint.osint.data.CustomConnectionProbe
import ru.jbosint.osint.data.CustomModule
import ru.jbosint.osint.data.CustomModuleSpec
import ru.jbosint.osint.data.FactField
import ru.jbosint.osint.data.FieldRule
import ru.jbosint.osint.data.JsonPath
import ru.jbosint.osint.data.Normalizer

class CustomModuleSpecTest {

    private fun module(auth: CustomAuth) = CustomModule(
        id = "m1",
        title = "Мой сервис",
        urlTemplate = "https://api.example.org/v1/search?q={query}",
        auth = auth
    )

    @Test
    fun validTemplatePasses() {
        assertNull(CustomModuleSpec.validate("Мой сервис", "https://api.example.org/v1/search?q={query}"))
    }

    @Test
    fun rejectsInsecureAndIncompleteTemplates() {
        assertNotNull(CustomModuleSpec.validate("", "https://a.org/{query}"))
        assertNotNull(CustomModuleSpec.validate("X", "http://a.org/{query}"))
        assertNotNull(CustomModuleSpec.validate("X", "https://a.org/search"))
        assertNotNull(CustomModuleSpec.validate("X", "https://a.org/{quer y}"))
    }

    @Test
    fun queryIsEncodedIntoUrl() {
        val url = CustomModuleSpec.buildUrl(module(CustomAuth.NONE), "", "ivan petrov&co")
        assertEquals("https://api.example.org/v1/search?q=ivan+petrov%26co", url)
    }

    @Test
    fun paramAuthAppendsKeyWithAmpersand() {
        val url = CustomModuleSpec.buildUrl(module(CustomAuth.PARAM), "k-1", "ivan")
        assertEquals("https://api.example.org/v1/search?q=ivan&api_key=k-1", url)
    }

    @Test
    fun jsonBodyAuthDoesNotLeakKeyIntoUrl() {
        val url = CustomModuleSpec.buildUrl(module(CustomAuth.JSON_BODY), "secret", "ivan")
        assertEquals("https://api.example.org/v1/search?q=ivan", url)
    }

    @Test
    fun headersDependOnAuthType() {
        assertEquals(mapOf("Authorization" to "Bearer abc"), CustomModuleSpec.headers(module(CustomAuth.BEARER), "abc"))
        assertEquals(mapOf("X-API-Key" to "abc"), CustomModuleSpec.headers(module(CustomAuth.HEADER), "abc"))
        assertTrue(CustomModuleSpec.headers(module(CustomAuth.PARAM), "abc").isEmpty())
        assertTrue(CustomModuleSpec.headers(module(CustomAuth.JSON_BODY), "abc").isEmpty())
        assertTrue(CustomModuleSpec.headers(module(CustomAuth.NONE), "").isEmpty())
    }

    @Test
    fun jsonBodyCarriesKeyAndOtherAuthsDoNot() {
        val json = CustomModuleSpec.body(module(CustomAuth.JSON_BODY), "secret")
        assertNotNull(json)
        assertEquals("secret", org.json.JSONObject(json!!).getString("api_key"))

        val custom = module(CustomAuth.JSON_BODY).copy(paramName = "token")
        assertEquals("secret", org.json.JSONObject(CustomModuleSpec.body(custom, "secret")!!).getString("token"))

        assertNull(CustomModuleSpec.body(module(CustomAuth.JSON_BODY), ""))
        assertNull(CustomModuleSpec.body(module(CustomAuth.PARAM), "secret"))
        assertNull(CustomModuleSpec.body(module(CustomAuth.BEARER), "secret"))
        assertNull(CustomModuleSpec.body(module(CustomAuth.NONE), ""))
    }

    @Test
    fun hostIsExtractedForModuleCard() {
        assertEquals("api.example.org", CustomModuleSpec.hostOf("https://api.example.org/v1/search?q={query}"))
    }

    @Test
    fun sourceIdIsNeutral() {
        assertEquals("custom", CustomModuleSpec.SOURCE_ID)
    }

    @Test
    fun rulesAreValidated() {
        assertNull(CustomModuleSpec.validateRules(listOf(FieldRule("data.email", FactField.EMAIL))))
        assertNotNull(CustomModuleSpec.validateRules(listOf(FieldRule("", FactField.EMAIL))))
        assertNotNull(CustomModuleSpec.validateRules(listOf(FieldRule("data.email", FactField.EMAIL, "("))))
    }

    @Test
    fun jsonPathReadsNestedObjectsAndArrays() {
        val root = mapOf(
            "data" to mapOf(
                "username" to "ivan_p",
                "items" to listOf(
                    mapOf("email" to "Ivan@Mail.RU"),
                    mapOf("email" to "second@mail.ru")
                )
            )
        )
        assertEquals(listOf<Any?>("ivan_p"), JsonPath.select(root, ".data.username"))
        assertEquals(listOf("Ivan@Mail.RU", "second@mail.ru"), JsonPath.strings(root, "data.items[].email"))
        assertEquals(listOf("second@mail.ru"), JsonPath.strings(root, "data.items[1].email"))
        assertTrue(JsonPath.strings(root, "data.missing").isEmpty())
    }

    @Test
    fun valuesAreBroughtToUnifiedForm() {
        assertEquals("ivan@mail.ru", Normalizer.canonical(FactField.EMAIL, " Ivan@Mail.RU "))
        assertEquals("+79161234567", Normalizer.canonical(FactField.PHONE, "8 (916) 123-45-67"))
        assertEquals("ivan_p", Normalizer.canonical(FactField.USERNAME, "@Ivan_P"))
        assertEquals("example.org", Normalizer.canonical(FactField.DOMAIN, "https://www.Example.org/path"))
        assertEquals("Иван Петров", Normalizer.canonical(FactField.FULLNAME, "  Иван   Петров "))
    }
}

class ConnectionProbeTest {

    @Test
    fun describeMapsHttpCodesToHumanText() {
        val probe = CustomConnectionProbe()
        assertTrue(probe.describe(403).contains("403"))
        assertTrue(probe.describe(403).contains("доступ запрещён"))
        assertTrue(probe.describe(401).contains("ключ отклонён"))
        assertTrue(probe.describe(404).contains("адрес не найден"))
        assertTrue(probe.describe(200).contains("Модуль отвечает"))
        assertTrue(probe.describe(503).contains("сервер недоступен"))
    }

    @Test
    fun autoAuthDoesNotLeakKeyIntoUrlOrHeaders() {
        val module = CustomModule(
            id = "auto1",
            title = "Мой сервис",
            urlTemplate = "https://api.example.com/search?q={query}",
            auth = CustomAuth.AUTO
        )
        val url = CustomModuleSpec.buildUrl(module, "SECRET", "test")
        assertEquals("https://api.example.com/search?q=test", url)
        assertTrue(CustomModuleSpec.headers(module, "SECRET").isEmpty())
    }

    @Test
    fun templateRequiresQueryMarkerAndHttps() {
        assertNull(CustomModuleSpec.validate("M", "https://api.example.com/s?q={query}"))
        assertNotNull(CustomModuleSpec.validate("M", "http://api.example.com/s?q={query}"))
        assertNotNull(CustomModuleSpec.validate("M", "https://api.example.com/s"))
    }
}
