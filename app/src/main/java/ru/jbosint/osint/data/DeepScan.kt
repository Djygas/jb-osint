package ru.jbosint.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.jbosint.osint.core.model.RawFact

/**
 * Многошаговый поиск («пивот»).
 *
 * Один запрос редко даёт полную картину: находятся профили, из них — почта,
 * из почты — телефон, из телефона — объявления. Здесь это делается
 * автоматически: после каждого прохода из найденных значений выбираются
 * точные зацепки (почта, телефон, ник, домен), по ним запускается следующий
 * проход, страницы из выдачи читаются и разбираются.
 *
 * Ограничения заложены в саму конструкцию: не больше [maxHops] проходов,
 * [maxPivotsPerHop] зацепок на проход и [pageLimit] страниц за раз, поэтому
 * расход запросов предсказуем, а платные модули вызываются только на первом
 * проходе с исходным запросом.
 */
class DeepScan(
    private val keys: KeyRepository,
    private val moduleResolver: (String) -> Connector? = { Connectors.byId(it) }
) {

    data class Hop(
        val index: Int,
        val queries: List<String>,
        val facts: Int,
        val pages: Int,
        val pivots: Int
    )

    data class Result(
        val facts: List<RawFact>,
        val hops: List<Hop>,
        val queries: List<String>,
        val pages: List<PageContent>,
        val responded: Int,
        val failed: Int
    )

    suspend fun scan(
        seed: String,
        moduleIds: List<String>,
        maxHops: Int = 2,
        fetchPages: Boolean = true,
        pageLimit: Int = 3,
        maxPivotsPerHop: Int = 3,
        onStage: (String, Int) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        val modules = moduleIds.mapNotNull(moduleResolver).distinctBy { it.id }
        val allFacts = java.util.Collections.synchronizedList(ArrayList<RawFact>())
        val pages = java.util.Collections.synchronizedList(ArrayList<PageContent>())
        var responded = 0
        var failed = 0

        val cleanSeed = seed.trim()
        val seenQueries = LinkedHashSet<String>()
        val seenPivots = LinkedHashSet<String>()
        // Исходный запрос сразу считаем пройденным во всех написаниях: иначе
        // «toxa» и «@toxa» пойдут как два разных прохода.
        normalizeQuery(cleanSeed).forEach { seenQueries += it }
        var queue = listOf(cleanSeed)
        val readUrls = LinkedHashSet<String>()
        val hops = ArrayList<Hop>()
        val hopsCount = maxHops.coerceIn(1, 3)

        for (hop in 1..hopsCount) {
            if (queue.isEmpty()) break
            val before = allFacts.size
            onStage("Проход $hop из $hopsCount: ${queue.first()}", (hop - 1) * 100 / hopsCount)

            for (query in queue) {
                if (modules.isEmpty()) break
                val modulesThisHop = if (hop == 1) modules else modules.filter { it.isFreeModule() }
                val result = coroutineScope {
                    modulesThisHop.map { module ->
                        async {
                            val outcome = runCatching { withTimeout(20_000) { module.collect(query, keys) } }
                            outcome.onSuccess { items ->
                                allFacts += items.map { item ->
                                    if (item.sourceUrl.isNotBlank()) item
                                    else item.copy(sourceUrl = EvidenceLinks.url(module.id, query, item.value))
                                }
                                synchronized(this@DeepScan) { responded++ }
                            }.onFailure {
                                synchronized(this@DeepScan) { failed++ }
                            }
                        }
                    }.awaitAll()
                }
            }

            // Страницы из выдачи: читаем и разбираем только на первом и втором проходе.
            if (fetchPages && hop <= 2) {
                val urls = allFacts.mapNotNull { fact ->
                    fact.value.takeIf { it.startsWith("http") } ?: fact.sourceUrl.takeIf { it.isNotBlank() }
                }
                val picked = PageReader.pickUrls(urls, pageLimit).filter { it !in readUrls }
                readUrls += picked
                coroutineScope {
                    picked.map { url ->
                        async {
                            val page = PageReader.read(url)
                            synchronized(pages) { pages += page }
                            val extra = PageReader.facts(page)
                            if (extra.isNotEmpty()) synchronized(allFacts) { allFacts += extra }
                        }
                    }.awaitAll()
                }
            }

            val pivots = EntityResolver.pivots(
                facts = allFacts.map { raw -> raw.toFact() },
                exclude = seenPivots,
                limit = maxPivotsPerHop
            )
            val next = pivots.filter { pivot ->
                val variants = normalizeQuery(pivot.query) + normalizeQuery(pivot.value)
                variants.none { it in seenQueries }
            }
            hops += Hop(hop, queue, allFacts.size - before, pages.size, next.size)
            next.forEach {
                normalizeQuery(it.query).forEach { value -> seenQueries += value }
                seenPivots += it.key
            }
            queue = next.map { it.query }
            if (hop == 1 && queue.isNotEmpty()) onStage("Найдены зацепки: ${queue.joinToString(", ")}", 55)
        }

        Result(
            facts = allFacts.toList(),
            hops = hops,
            queries = seenQueries.toList(),
            pages = pages.toList(),
            responded = responded,
            failed = failed
        )
    }

    /** Написания одного и того же запроса: с @, без @, в нижнем регистре. */
    private fun normalizeQuery(raw: String): List<String> {
        val clean = raw.trim().lowercase()
        val bare = clean.trimStart('@')
        return listOf(clean, bare, "@$bare").distinct()
    }

    /**
     * Бесплатные модули: без ключей и без оплаты за запрос. На втором и третьем
     * проходе гоняем только их — иначе углубление умножает платные вызовы.
     */
    private fun Connector.isFreeModule(): Boolean {
        val spec = ProviderCatalog.byId(id) ?: return false
        return spec.keyless
    }
}

/** Служебное преобразование RawFact в Fact для выбора зацепок. */
private fun RawFact.toFact() = ru.jbosint.osint.core.model.Fact(
    category = category,
    kind = kind,
    value = value,
    subject = subject,
    attributes = attributes,
    sourceIds = setOf(sourceId),
    observations = 1,
    firstSeen = 0L,
    urls = setOfNotNull(sourceUrl.takeIf { it.isNotBlank() })
)
