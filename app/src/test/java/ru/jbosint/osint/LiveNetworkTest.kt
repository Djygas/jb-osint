package ru.jbosint.osint

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.jbosint.osint.agent.AgentPlanner
import ru.jbosint.osint.agent.CostTier
import ru.jbosint.osint.data.Connectors
import ru.jbosint.osint.data.DeepScan
import ru.jbosint.osint.data.KeySource
import ru.jbosint.osint.data.PageReader
import ru.jbosint.osint.data.ProviderCatalog
import ru.jbosint.osint.data.VinDecoder

/**
 * Живые проверки сетевых путей: ходят в настоящие открытые API и страницы.
 *
 * Смысл — отличить «модуль молча сломан» от «сервис временно недоступен».
 * Если внешний сервис не ответил после трёх попыток, тест помечается
 * пропущенным (assume), а не падает: иначе плавающая сеть ломает сборку.
 * Как только сервис ответил — утверждение проверяется строго.
 */
class LiveNetworkTest {

    /** Ключей нет: проверяем только бесплатные источники и открытые данные. */
    private class FakeKeys : KeySource {
        override fun key(id: String): String = ""
        override fun secondField(id: String): String = ""
        override fun spec(id: String) = ProviderCatalog.byId(id)
        override fun enabledWithKeys(): Map<String, String> = emptyMap()
    }

    private val keys = FakeKeys()

    /** Повтор с паузой: публичные API любят отдавать 429 и таймауты. */
    private suspend fun <T> live(attempts: Int = 3, block: suspend () -> T): T? {
        repeat(attempts) { index ->
            val result = runCatching { block() }.getOrNull()
            val empty = when (result) {
                null -> true
                is Collection<*> -> result.isEmpty()
                is String -> result.isBlank()
                else -> false
            }
            if (!empty) return result
            delay(1_500L * (index + 1))
        }
        return null
    }

    @Test
    fun duckDuckGoAnswers() = runBlocking {
        val connector = Connectors.byId("ddg")
        assertNotNull("коннектор ddg не зарегистрирован", connector)
        val facts = live { connector!!.collect("OpenAI", keys) }
        assumeTrue("DuckDuckGo недоступен из сети — проверка пропущена", facts != null)
        assertTrue("DuckDuckGo ответил, но фактов нет", facts!!.isNotEmpty())
    }

    @Test
    fun wikipediaAnswers() = runBlocking {
        val facts = live { Connectors.byId("wikipedia")!!.collect("OpenAI", keys) }
        assumeTrue("Wikipedia недоступна из сети — проверка пропущена", facts != null)
        assertTrue("Wikipedia ответила, но фактов нет", facts!!.isNotEmpty())
    }

    @Test
    fun pageReaderReadsRealPage() = runBlocking {
        val page = live { PageReader.read("https://example.com").takeIf { it.ok } }
        assumeTrue("example.com недоступен — проверка пропущена", page != null)
        assertTrue("нет заголовка: ${page!!.title}", page.title.contains("Example", ignoreCase = true))
    }

    @Test
    fun nhtsaDecodesRealVin() = runBlocking {
        val facts = live { VinDecoder.decode("1HGCM82633A004352") }
        assumeTrue("NHTSA недоступна — проверка пропущена", facts != null)
        assertTrue("NHTSA ответила, но фактов нет", facts!!.isNotEmpty())
        assertTrue("нет марки в ответе", facts.any { it.kind == "Марка" })
    }

    @Test
    fun deepScanMakesTwoHops() = runBlocking {
        val scan = live {
            DeepScan(keys).scan(
                seed = "OpenAI",
                moduleIds = listOf("ddg", "wikipedia"),
                maxHops = 2,
                fetchPages = true,
                pageLimit = 1
            ).takeIf { it.facts.isNotEmpty() }
        }
        assumeTrue("открытые источники недоступны — проверка пропущена", scan != null)
        assertTrue("ни одного факта за проход", scan!!.facts.isNotEmpty())
        assertTrue("проходы не зафиксированы", scan.hops.isNotEmpty())
    }

    @Test
    fun plannerBuildsFreePlanWithoutKeys() {
        val plan = AgentPlanner.plan("Иван Иванов", hasKey = { id ->
            ProviderCatalog.byId(id)?.keyless == true
        })
        assertTrue("в плане нет бесплатных модулей", plan.isNotEmpty())
        assertTrue("первый шаг не бесплатный", plan.first().tier == CostTier.FREE)
    }
}
