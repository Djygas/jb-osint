package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.CustomAuth
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
    fun headersDependOnAuthType() {
        assertEquals(mapOf("Authorization" to "Bearer abc"), CustomModuleSpec.headers(module(CustomAuth.BEARER), "abc"))
        assertEquals(mapOf("X-API-Key" to "abc"), CustomModuleSpec.headers(module(CustomAuth.HEADER), "abc"))
        assertTrue(CustomModuleSpec.headers(module(CustomAuth.PARAM), "abc").isEmpty())
        assertTrue(CustomModuleSpec.headers(module(CustomAuth.NONE), "").isEmpty())
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
