package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.RaeParser

/** Разбор клиента RAE: base_url, заголовки, функции, маскирование секретов. */
class RaeParserTest {

    private val sample = """
        import requests

        BASE_URL = "https://api.example.com"
        DEFAULT_HEADERS = {
            "Accept": "application/json",
            "Authorization": "Bearer sk-abcdefghijklmnopqrstuvwxyz012345",
        }

        class Client:
            def __init__(self, token: str):
                self.base_url = BASE_URL
                self.headers = DEFAULT_HEADERS

            def get_user(self, user_id: int):
                return requests.get(f"${'$'}{self.base_url}/v1/users/{user_id}", headers=self.headers)

            def search(self, query: str, limit: int = 20):
                return requests.post(self.base_url + "/v1/search", json={"q": query})
    """.trimIndent()

    @Test
    fun parsesBaseUrlHeadersAndFunctions() {
        val client = RaeParser.parse(sample)
        assertEquals("https://api.example.com", client?.baseUrl)
        assertEquals("application/json", client?.headers?.get("Accept"))
        assertEquals(2, client?.functions?.size)
        val get = client!!.functions.first { it.name == "get_user" }
        assertEquals("GET", get.method)
        assertEquals("/v1/users/{user_id}", get.path)
        assertEquals(listOf("user_id"), get.params)
        val search = client.functions.first { it.name == "search" }
        assertEquals("POST", search.method)
        assertEquals("/v1/search", search.path)
        assertEquals(listOf("query", "limit"), search.params)
    }

    @Test
    fun masksBearerToken() {
        val client = RaeParser.parse(sample)!!
        val auth = client.headers.getValue("Authorization")
        assertEquals("Bearer ***", auth)
        assertEquals("application/json", RaeParser.maskValue("application/json"))
    }

    @Test
    fun skipsPrivateFunctions() {
        val client = RaeParser.parse(sample)!!
        assertTrue(client.functions.none { it.name.startsWith("_") })
    }

    @Test
    fun buildsTemplateWithQueryPlaceholder() {
        val client = RaeParser.parse(sample)!!
        assertEquals("https://api.example.com/v1/users/{query}", client.urlTemplate())

        val noPlaceholder = client.copy(functions = listOf(client.functions.first { it.name == "search" }))
        assertEquals(
            "https://api.example.com/v1/search?query={query}",
            noPlaceholder.urlTemplate()
        )
    }

    @Test
    fun rejectsFileWithoutBaseUrl() {
        assertNull(RaeParser.parse("print('hello')"))
        assertNull(RaeParser.parse("def get(): pass"))
    }

    @Test
    fun hostIsExtracted() {
        val client = RaeParser.parse(sample)!!
        assertEquals("api.example.com", client.host)
    }
}
