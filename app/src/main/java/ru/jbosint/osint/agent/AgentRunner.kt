package ru.jbosint.osint.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.jbosint.osint.core.aggregate.Aggregator
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.core.report.SourceCatalog
import ru.jbosint.osint.data.AutoCheck
import ru.jbosint.osint.data.Connectors
import ru.jbosint.osint.data.EvidenceLinks
import ru.jbosint.osint.data.KeyRepository
import java.util.Collections

/**
 * Исполнение плана агента.
 *
 * Шаги идут строго по возрастанию цены: сначала бесплатные источники, затем
 * модули по ключу, затем платные запросы. Как только открытые источники дали
 * достаточно данных, платные шаги пропускаются — это и есть экономия токенов.
 * Каждый шаг виден в плане: сколько фактов он дал и почему пропущен.
 */
class AgentRunner(private val keys: KeyRepository) {

    /** Сколько фактов из открытых источников считаем «достаточно», чтобы не тратить платные запросы. */
    private val enoughFacts = 40

    suspend fun run(
        query: String,
        onStage: (String, Int) -> Unit = { _, _ -> }
    ): AgentReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val clean = query.trim()
        val target = AgentPlanner.detect(clean)
        var plan = AgentPlanner.plan(clean, keys)

        val raw = Collections.synchronizedList(ArrayList<RawFact>())
        var responded = 0
        var failed = 0
        var paidRequests = 0
        var skipPaid = false
        val runnable = plan.count { it.state != StepState.SKIPPED }.coerceAtLeast(1)
        var done = 0

        for (step in plan) {
            if (step.state == StepState.SKIPPED) continue
            if (step.tier == CostTier.PAID && skipPaid) {
                plan = plan.map {
                    if (it.id == step.id) it.copy(
                        state = StepState.SKIPPED,
                        note = "Пропущено: открытые источники дали достаточно данных"
                    ) else it
                }
                continue
            }
            if (step.tier == CostTier.PAID && paidRequests >= AgentPlanner.MAX_PAID_STEPS) {
                plan = plan.map {
                    if (it.id == step.id) it.copy(state = StepState.SKIPPED, note = "Пропущено: лимит платных шагов")
                    else it
                }
                continue
            }

            onStage("${step.title}…", done * 100 / runnable)
            plan = plan.map { if (it.id == step.id) it.copy(state = StepState.RUNNING) else it }

            if (step.id == "auto") {
                val compact = clean.filter { it.isLetterOrDigit() }.uppercase()
                val isVin = compact.length == 17
                raw += AutoCheck.decodeVin(if (isVin) compact else "")
                val key = keys.key("tronk")
                if (key.isNotBlank()) {
                    val facts = runCatching {
                        AutoCheck.tronk(
                            key = key,
                            vin = if (isVin) compact else "",
                            plate = if (isVin) "" else clean.uppercase()
                        )
                    }.getOrDefault(emptyList())
                    raw += facts
                    paidRequests++
                }
                done++
                onStage("Проверка авто: готово", done * 100 / runnable)
                plan = plan.map { if (it.id == step.id) it.copy(state = StepState.DONE, facts = raw.size) else it }
                continue
            }

            val modules = step.moduleIds.mapNotNull { Connectors.byId(it) }
            if (modules.isEmpty()) {
                plan = plan.map {
                    if (it.id == step.id) it.copy(
                        state = StepState.SKIPPED,
                        note = if (step.moduleIds.isEmpty()) "Нет доступных модулей — не заданы ключи"
                        else "Модули не найдены"
                    ) else it
                }
                continue
            }
            for (module in modules) {
                val outcome = runCatching {
                    withTimeout(25_000) { module.collect(clean, keys) }
                }
                outcome.onSuccess { items ->
                    raw += items.map { item ->
                        if (item.sourceUrl.isNotBlank()) item
                        else item.copy(sourceUrl = EvidenceLinks.url(module.id, clean, item.value))
                    }
                    responded++
                }.onFailure { failed++ }
                done++
                onStage("${step.title}: $done из $runnable", done * 100 / runnable)
            }
            if (step.tier == CostTier.PAID) paidRequests++
            val collected = raw.count { it.sourceId in step.moduleIds }
            plan = plan.map {
                if (it.id == step.id) it.copy(state = StepState.DONE, facts = collected) else it
            }
            if (step.tier == CostTier.FREE && raw.size >= enoughFacts) skipPaid = true
        }

        onStage("Агрегация и анонимизация", 100)
        val stats = SearchStats(
            sourcesAttempted = plan.sumOf { it.moduleIds.size } + 1,
            sourcesResponded = responded,
            sourcesFailed = failed,
            rawFacts = raw.size,
            duplicatesRemoved = 0,
            durationMs = System.currentTimeMillis() - started
        )
        val sources = plan.flatMap { it.moduleIds }
            .distinct()
            .map { SourceCatalog.ref(it) }
        val data = Aggregator.aggregate(clean, raw.toList(), stats).copy(sources = sources)
        val anonymized = AgentAnonymizer.apply(data)
        val findings = AgentNarrator.findings(anonymized.data)
        val assumptions = AgentNarrator.assumptions(anonymized.data, target)
        val skippedPaid = plan.filter { it.tier == CostTier.PAID && it.state == StepState.SKIPPED }
            .map { it.title }

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
                paidRequests == 0 -> "Данные собраны без использования платных API."
                else -> "Выполнено платных запросов: $paidRequests из ${AgentPlanner.MAX_PAID_STEPS}."
            }
        )
    }
}
