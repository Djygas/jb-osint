package ru.jbosint.osint.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.jbosint.osint.core.aggregate.Aggregator
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.core.report.SourceCatalog
import ru.jbosint.osint.data.AutoCheck
import ru.jbosint.osint.data.DeepScan
import ru.jbosint.osint.data.KeyRepository
import ru.jbosint.osint.data.VinDecoder

/**
 * Исполнение плана агента.
 *
 * Первый проход идёт по ступеням плана (бесплатные → по ключу → платные),
 * второй и третий — по найденным зацепкам: почта, телефон, ник, домен.
 * Страницы из выдачи читаются и разбираются, поэтому поиск получается
 * развёрнутым, а не списком ссылок. Платные модули вызываются только на первом
 * проходе с исходным запросом — углубление не умножает счёт.
 */
class AgentRunner(private val keys: KeyRepository) {

    suspend fun run(
        query: String,
        depth: Int = 2,
        onStage: (String, Int) -> Unit = { _, _ -> }
    ): AgentReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val clean = query.trim()
        val target = AgentPlanner.detect(clean)
        var plan = AgentPlanner.plan(clean, keys)

        val modules = plan
            .filter { it.id != "auto" && it.state != StepState.SKIPPED }
            .flatMap { it.moduleIds }
            .distinct()
        val hops = depth.coerceIn(1, 3)

        val scan = DeepScan(keys).scan(
            seed = clean,
            moduleIds = modules,
            maxHops = hops,
            fetchPages = true,
            pageLimit = if (hops > 1) 4 else 2,
            maxPivotsPerHop = if (hops >= 3) 3 else 2,
            onStage = onStage
        )
        val raw = ArrayList<RawFact>(scan.facts)

        onStage("Проверка авто", 80)
        if (plan.any { it.id == "auto" }) {
            val compact = clean.filter { it.isLetterOrDigit() }.uppercase()
            val isVin = compact.length == 17
            if (isVin) {
                raw += AutoCheck.decodeVin(compact)
                raw += runCatching { VinDecoder.decode(compact) }.getOrDefault(emptyList())
            }
            val key = keys.key("tronk")
            if (key.isNotBlank()) {
                raw += runCatching {
                    AutoCheck.tronk(
                        key = key,
                        vin = if (isVin) compact else "",
                        plate = if (isVin) "" else clean.uppercase()
                    )
                }.getOrDefault(emptyList())
            }
        }

        plan = plan.map { step ->
            if (step.state == StepState.SKIPPED) step
            else {
                val ids = if (step.id == "auto") setOf("nhtsa", "vin", "tronk")
                else step.moduleIds.toSet()
                val count = raw.count { it.sourceId in ids }
                step.copy(
                    state = if (count > 0) StepState.DONE else StepState.SKIPPED,
                    facts = count,
                    note = if (count > 0) step.note else "Модули не вернули данных"
                )
            }
        }
        // Платным считаем только шаг, который реально дал факты.
        val paidRequests = plan.count { it.tier == CostTier.PAID && it.state == StepState.DONE }
        onStage("Агрегация и анонимизация", 95)

        val stats = SearchStats(
            sourcesAttempted = plan.sumOf { it.moduleIds.size } + 1,
            sourcesResponded = scan.responded,
            sourcesFailed = scan.failed,
            rawFacts = raw.size,
            duplicatesRemoved = 0,
            durationMs = System.currentTimeMillis() - started
        )
        val sources = (plan.flatMap { it.moduleIds }.distinct().map { SourceCatalog.ref(it) } +
            SourceCatalog.ref("page")).distinctBy { it.id }
        val data = Aggregator.aggregate(clean, raw.toList(), stats).copy(sources = sources)
        val anonymized = AgentAnonymizer.apply(data)
        val findings = AgentNarrator.findings(anonymized.data)
        val assumptions = AgentNarrator.assumptions(anonymized.data, target)
        val skippedPaid = plan.filter { it.tier == CostTier.PAID && it.state == StepState.SKIPPED }
            .map { it.title }

        val hopNote = "Проходов: ${scan.hops.size}, прочитано страниц: ${scan.pages.size}."
        AgentReport(
            query = clean,
            target = target,
            plan = plan,
            findings = findings,
            assumptions = assumptions,
            modules = anonymized.modules,
            data = data,
            anonymousData = anonymized.data,
            freeOnly = paidRequests == 0,
            paidRequests = paidRequests,
            skippedPaid = skippedPaid,
            durationMs = System.currentTimeMillis() - started,
            note = when {
                paidRequests == 0 -> "$hopNote Данные собраны без использования платных API."
                else -> "$hopNote Выполнено платных модулей: $paidRequests из ${AgentPlanner.MAX_PAID_STEPS}."
            }
        )
    }
}
