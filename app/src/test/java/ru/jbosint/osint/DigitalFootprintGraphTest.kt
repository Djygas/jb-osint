package ru.jbosint.osint

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.data.Connector
import ru.jbosint.osint.data.DigitalFootprintGraph
import ru.jbosint.osint.data.KeySource
import ru.jbosint.osint.data.ProviderCatalog
import ru.jbosint.osint.data.ProviderSpec

class DigitalFootprintGraphTest {

    private class MapKeys(private val map: Map<String, String>) : KeySource {
        override fun key(id: String): String = map[id].orEmpty()
        override fun secondField(id: String): String = ""
        override fun spec(id: String): ProviderSpec? = ProviderCatalog.byId(id)
        override fun enabledWithKeys(): Map<String, String> = map
    }

    private class FakeModule(
        override val id: String,
        private val body: (String) -> List<RawFact>
    ) : Connector {
        val queries = ArrayList<String>()
        override suspend fun collect(query: String, key: KeySource): List<RawFact> {
            queries += query
            return body(query)
        }
    }

    private fun emailFact(value: String) = RawFact(
        category = FactCategory.CONTACT,
        kind = "Почта",
        value = value,
        subject = "Иван",
        sourceId = "vk"
    )

    private fun leakFact(query: String, password: String = "Love12345") = RawFact(
        category = FactCategory.OTHER,
        kind = "Утечка",
        value = "Collection#1",
        subject = query,
        attributes = mapOf(
            "Дата" to "2019-01-07",
            "Данные" to "почта, пароль",
            "Пароль" to password
        ),
        sourceId = "leakcheck"
    )

    @Test
    fun `phone seed expands to linked email and finds leak with masked password`() = runBlocking {
        val vk = FakeModule("vk") { listOf(emailFact("work.ivan@mail.ru")) }
        val leakcheck = FakeModule("leakcheck") { query ->
            if (query == "work.ivan@mail.ru") listOf(leakFact(query)) else emptyList()
        }
        val graph = DigitalFootprintGraph(
            keys = MapKeys(mapOf("vk" to "k-vk", "leakcheck" to "k-lc")),
            modules = listOf(vk, leakcheck)
        )

        val footprint = graph.build("+7 950 007-39-00")

        assertTrue("почта связана с телефоном", footprint.nodes.any { it.key == "email:work.ivan@mail.ru" })
        assertEquals(1, footprint.edges.size)
        assertEquals(1, footprint.leaks.size)
        assertEquals("LeakCheck", footprint.leaks.first().source)
        assertEquals("Collection#1", footprint.leaks.first().resource)
        assertTrue("пароль присутствует", footprint.leaks.first().hasPassword)
        assertEquals("Love12345", footprint.leaks.first().password)
        assertTrue(footprint.risks.any { it.level == DigitalFootprintGraph.Risk.HIGH })
        assertTrue(footprint.recommendations.isNotEmpty())

        val json = footprint.toJson()
        assertEquals("jb-osint/footprint/1", json.getString("schema"))
        assertEquals(1, json.getJSONArray("leaks").length())
        assertEquals("Love12345", json.getJSONArray("leaks").getJSONObject(0).getString("password"))
        assertEquals("HIGH", json.getJSONArray("risks").getJSONObject(0).getString("level"))
    }

    @Test
    fun `without keys no module is called and graph stays honest`() = runBlocking {
        val vk = FakeModule("vk") { listOf(emailFact("work.ivan@mail.ru")) }
        val leakcheck = FakeModule("leakcheck") { query -> listOf(leakFact(query)) }
        val graph = DigitalFootprintGraph(keys = MapKeys(emptyMap()), modules = listOf(vk, leakcheck))

        val footprint = graph.build("+79500073900")

        assertTrue(vk.queries.isEmpty())
        assertTrue(leakcheck.queries.isEmpty())
        assertEquals(1, footprint.nodes.size)
        assertTrue(footprint.leaks.isEmpty())
        assertTrue(footprint.risks.any { it.level == DigitalFootprintGraph.Risk.INFO })
    }

    @Test
    fun `expansion is capped by maxPivots`() = runBlocking {
        val emails = (1..5).map { "user$it@mail.ru" }
        val vk = FakeModule("vk") { emails.map { emailFact(it) } }
        val leakcheck = FakeModule("leakcheck") { emptyList() }
        val graph = DigitalFootprintGraph(
            keys = MapKeys(mapOf("vk" to "k", "leakcheck" to "k")),
            modules = listOf(vk, leakcheck),
            limits = DigitalFootprintGraph.Limits(maxPivots = 3)
        )

        graph.build("+79500073900")

        // Ликчек вызывается и на самом номере (первый проход), и на связанных почтах (второй).
        val emailQueries = leakcheck.queries.filter { it.contains("@") }
        assertEquals(3, emailQueries.size)
        assertTrue(emailQueries.all { it.endsWith("@mail.ru") })
    }

    @Test
    fun `expand works on an existing search result`() = runBlocking {
        val leakcheck = FakeModule("leakcheck") { query -> listOf(leakFact(query)) }
        val graph = DigitalFootprintGraph(
            keys = MapKeys(mapOf("leakcheck" to "k")),
            modules = listOf(leakcheck)
        )
        val base = AggregatedData(
            query = "+79500073900",
            facts = listOf(
                Fact(
                    category = FactCategory.CONTACT,
                    kind = "Почта",
                    value = "work.ivan@mail.ru",
                    subject = "Иван",
                    attributes = emptyMap(),
                    sourceIds = setOf("vk"),
                    observations = 1
                )
            )
        )

        val extra = graph.expand(base)

        assertEquals(listOf("work.ivan@mail.ru"), leakcheck.queries)
        assertEquals(1, extra.size)
        assertNotNull(extra.first().value)
        assertEquals("Collection#1", extra.first().value)
    }
    @Test
    fun `imported report contacts are checked in leak modules`() = runBlocking {
        val leakcheck = FakeModule("leakcheck") { query ->
            if (query == "work.ivan@mail.ru") listOf(leakFact(query)) else emptyList()
        }
        val graph = DigitalFootprintGraph(
            keys = MapKeys(mapOf("leakcheck" to "k")),
            modules = listOf(leakcheck)
        )

        val footprint = graph.runFromText("ФИО: Иванов Иван\nПочта: work.ivan@mail.ru")

        assertEquals("IMPORT", footprint.seedKind)
        assertEquals(1, footprint.leaks.size)
        assertEquals("Love12345", footprint.leaks.first().password)
        assertTrue(footprint.nodes.any { it.key == "email:work.ivan@mail.ru" })
    }
}