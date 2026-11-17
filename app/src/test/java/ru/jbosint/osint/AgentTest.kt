package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.agent.AgentAnonymizer
import ru.jbosint.osint.agent.AgentNarrator
import ru.jbosint.osint.agent.AgentPlanner
import ru.jbosint.osint.agent.AgentTarget
import ru.jbosint.osint.agent.CostTier
import ru.jbosint.osint.agent.AgentFinding
import ru.jbosint.osint.agent.AgentReport
import ru.jbosint.osint.agent.AgentTemplate
import ru.jbosint.osint.agent.AgentTemplates
import ru.jbosint.osint.agent.ModuleLabel
import ru.jbosint.osint.agent.StepState
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.core.model.SourceRef
import ru.jbosint.osint.data.AutoCheck
import ru.jbosint.osint.data.PhotoSearch
import ru.jbosint.osint.data.PhotoMatch
import ru.jbosint.osint.core.model.Confidence

/**
 * Тесты автономного агента, проверки авто и поиска по фото.
 * Проверяется логика: определение типа запроса, каскад по стоимости,
 * анонимизация модулей, утвердительные формулировки и разбор совпадений.
 */
class AgentTest {

    private fun data(facts: List<Fact>): AggregatedData = AggregatedData(
        query = "Балахтина Юлия Вадимовна",
        facts = facts,
        links = emptyList(),
        stats = SearchStats(),
        sources = listOf(
            SourceRef("telesint", "TeleSINT", "https://telesint.dev"),
            SourceRef("ddg", "DuckDuckGo", "https://duckduckgo.com")
        )
    )

    private fun fact(
        kind: String,
        value: String,
        subject: String = "Балахтина Юлия",
        category: FactCategory = FactCategory.PERSON,
        sources: Set<String> = setOf("telesint", "ddg")
    ) = Fact(
        category = category,
        kind = kind,
        value = value,
        subject = subject,
        attributes = emptyMap(),
        sourceIds = sources,
        observations = sources.size,
        firstSeen = 0L,
        urls = setOf("https://t.me/example")
    )

    // ------------------------------------------------------------ планировщик

    @Test
    fun detectsTargetTypes() {
        assertEquals(AgentTarget.VIN, AgentPlanner.detect("XTA21150053912345"))
        assertEquals(AgentTarget.PHONE, AgentPlanner.detect("+7 999 000-11-22"))
        assertEquals(AgentTarget.EMAIL, AgentPlanner.detect("user@example.com"))
        assertEquals(AgentTarget.DOMAIN, AgentPlanner.detect("jbvpn.ru"))
        assertEquals(AgentTarget.USERNAME, AgentPlanner.detect("@toxa"))
        assertEquals(AgentTarget.PERSON, AgentPlanner.detect("Балахтина Юлия Вадимовна"))
    }

    @Test
    fun vinParsedLocallyWithRegionAndYear() {
        val facts = AutoCheck.decodeVin("XTA21150053912345")
        assertTrue(facts.any { it.kind == "VIN" && it.value == "XTA21150053912345" })
        val vin = facts.first { it.kind == "VIN" }
        assertEquals("Россия (АвтоВАЗ)", vin.attributes["Регион сборки"])
        assertEquals("2005", vin.attributes["Год выпуска"])
    }

    @Test
    fun tronkResponseParsedToFacts() {
        val json = """
            {"data":{"vin":"XTA21150053912345","model":"Lada Vesta","year":2019,
            "mileage":87000,"owners_count":2,"accidents_count":1,"pledges":0,
            "average_price":950000,"parking_sessions":14,
            "photos":["https://cdn.tronk.pro/1.jpg"]}}
        """.trimIndent()
        val facts = AutoCheck.parseAuto(json, "XTA21150053912345")
        assertTrue(facts.any { it.kind == "Марка и модель" && it.value == "Lada Vesta" })
        assertTrue(facts.any { it.kind == "Пробег" && it.value.contains("87000") })
        assertTrue(facts.any { it.kind == "ДТП в истории" && it.value == "1" })
        assertTrue(facts.any { it.kind == "Залоги" && it.value == "не найдены" })
        assertTrue(facts.any { it.kind == "Фотография объявления" })
        assertTrue(facts.all { it.sourceId == "tronk" })
    }

    // ------------------------------------------------------------ анонимизация

    @Test
    fun anonymizerNumbersModules() {
        val source = data(
            listOf(
                fact("Профиль Telegram", "@toxa"),
                fact("Упоминание", "https://example.com/a", sources = setOf("ddg"))
            )
        )
        val result = AgentAnonymizer.apply(source)
        assertEquals(2, result.modules.size)
        assertEquals("Модуль 1", result.modules.first().label)
        assertEquals("Модуль 2", result.modules.last().label)
        assertEquals("TeleSINT", result.modules.first().realName)
        val ids = result.data.facts.flatMap { it.sourceIds }.toSet()
        assertEquals(setOf("1", "2"), ids)
        // Реальные названия остались только в приложении B.
        assertFalse(result.data.facts.any { fact -> fact.sourceIds.any { it.contains("telesint") } })
        assertEquals("https://telesint.dev", result.modules.first().url)
    }

    @Test
    fun findingsAreAssertiveAndCarryStatus() {
        val source = data(listOf(fact("Телефон", "+79990001122", category = FactCategory.CONTACT)))
        val anonymized = AgentAnonymizer.apply(source)
        val findings = AgentNarrator.findings(anonymized.data)
        assertEquals(1, findings.size)
        assertTrue(findings.first().text.startsWith("У объекта «Балахтина Юлия»"))
        assertTrue(findings.first().text.endsWith("."))
        assertEquals(Confidence.PROBABLE, findings.first().status)
        assertTrue(findings.first().moduleLabel.startsWith("Модуль"))
    }

    @Test
    fun assumptionsSeparatedFromFacts() {
        val source = data(
            listOf(
                fact("Телефон", "+79990001122", category = FactCategory.CONTACT, sources = setOf("telesint")),
                fact("Телефон", "+79990001122", subject = "Иванов Иван", category = FactCategory.CONTACT, sources = setOf("ddg"))
            )
        )
        val assumptions = AgentNarrator.assumptions(source, AgentTarget.PERSON)
        assertTrue(assumptions.isNotEmpty())
        assertTrue(assumptions.first().text.contains("связь", true))
        assertTrue(assumptions.all { it.basis.isNotBlank() })
    }

    @Test
    fun planStartsWithFreeTierAndLimitsPaidSteps() {
        // Без ключей репозитория в этом тесте есть только бесплатные модули по умолчанию.
        val available = setOf("ddg", "wikipedia", "github", "gitlab", "dyxless")
        val plan = AgentPlanner.plan("Балахтина Юлия Вадимовна", hasKey = { id -> id in available })
        assertTrue(plan.isNotEmpty())
        assertEquals(CostTier.FREE, plan.first().tier)
        val paid = plan.filter { it.tier == CostTier.PAID }
        assertTrue(paid.size <= AgentPlanner.MAX_PAID_STEPS)
        assertTrue(plan.none { it.state == StepState.SKIPPED && it.tier != CostTier.PAID })
    }


    // ------------------------------------------------------------ шаблоны отчёта

    private fun report(): AgentReport {
        val data = data(listOf(fact("Телефон", "+79990001122", category = FactCategory.CONTACT)))
        val anonymized = AgentAnonymizer.apply(data)
        return AgentReport(
            query = "Балахтина Юлия Вадимовна",
            target = AgentTarget.PERSON,
            plan = AgentPlanner.plan("Балахтина Юлия Вадимовна", hasKey = { false }),
            findings = AgentNarrator.findings(anonymized.data),
            assumptions = emptyList(),
            modules = anonymized.modules,
            data = data,
            anonymousData = anonymized.data,
            freeOnly = true,
            paidRequests = 0,
            skippedPaid = emptyList(),
            durationMs = 1200
        )
    }

    @Test
    fun quickTemplateIsShortAndHasNoAppendix() {
        val html = String(AgentTemplates.build(report(), AgentTemplate.QUICK), Charsets.UTF_8)
        assertTrue(html.contains("Быстрый чек"))
        assertTrue(html.contains("Балахтина Юлия Вадимовна"))
        assertFalse(html.contains("Приложение B"))
        assertTrue(html.contains("Данные собраны из открытых источников"))
    }

    @Test
    fun deepTemplateHasAppendixWithRealSources() {
        val html = String(AgentTemplates.build(report(), AgentTemplate.DEEP), Charsets.UTF_8)
        assertTrue(html.contains("Приложение B"))
        assertTrue(html.contains("TeleSINT"))
        assertTrue(html.contains("Модуль 1"))
    }

    @Test
    fun autoTemplateHasDealChecklist() {
        val r = report().copy(
            query = "XTA21150053912345",
            target = AgentTarget.VIN,
            findings = listOf(
                AgentFinding(
                    text = "У объекта «Автомобиль» выявлен Пробег: 87000 км.",
                    kind = "Пробег",
                    value = "87000 км",
                    status = Confidence.PROBABLE,
                    moduleLabel = "Модуль 1"
                )
            )
        )
        val html = String(AgentTemplates.build(r, AgentTemplate.AUTO), Charsets.UTF_8)
        assertTrue(html.contains("Что проверить перед сделкой"))
        assertTrue(html.contains("87000"))
        assertTrue(html.contains("Приложение B"))
    }

    @Test
    fun templateFileNamesAreSafe() {
        val name = AgentTemplates.fileName(report(), AgentTemplate.QUICK)
        assertTrue(name.startsWith("agent_"))
        assertTrue(name.contains("_quick_"))
        assertTrue(name.endsWith(".html"))
        assertFalse(name.contains(" "))
    }

    // ------------------------------------------------------------ фото

    @Test
    fun photoPlatformDetection() {
        assertEquals("VK", PhotoSearch.platformOf("https://vk.com/id1"))
        assertEquals("Telegram", PhotoSearch.platformOf("https://t.me/toxa"))
        assertEquals("Авито", PhotoSearch.platformOf("https://www.avito.ru/moskva/avtomobili"))
        assertEquals("Сайт", PhotoSearch.platformOf("https://example.org/page"))
    }

    @Test
    fun photoMatchesDeduplicatedAndMerged() {
        val matches = listOf(
            PhotoMatch("https://vk.com/id1?utm_source=x", "VK", "Профиль", 70, Confidence.PROBABLE, "SauceNAO"),
            PhotoMatch("https://vk.com/id1", "VK", "Профиль", 93, Confidence.CONFIRMED, "Поиск по лицу")
        )
        val merged = PhotoSearch.dedupe(matches)
        assertEquals(1, merged.size)
        assertEquals(93, merged.first().similarity)
        assertEquals(Confidence.CONFIRMED, merged.first().status)
        assertTrue(merged.first().provider.contains("SauceNAO"))
        assertTrue(merged.first().provider.contains("Поиск по лицу"))
    }

    @Test
    fun photoStatusThresholds() {
        assertEquals(Confidence.CONFIRMED, PhotoSearch.statusFor(95, confirmed = true))
        assertEquals(Confidence.PROBABLE, PhotoSearch.statusFor(70, confirmed = false))
        assertEquals(Confidence.UNVERIFIED, PhotoSearch.statusFor(55, confirmed = false))
    }

    @Test
    fun photoManualLinksCoverPlatforms() {
        val links = PhotoSearch.manualLinks("toxa")
        val titles = links.map { it.first }.joinToString(" ")
        assertTrue(titles.contains("Яндекс"))
        assertTrue(titles.contains("TinEye"))
        assertTrue(titles.contains("Telegram"))
        assertTrue(links.all { it.second.startsWith("https://") })
    }

    @Test
    fun normalizeStripsTracking() {
        assertEquals(
            "https://vk.com/id1",
            PhotoSearch.normalize("https://m.vk.com/id1/?utm_source=tg&utm_medium=x#top")
        )
    }
}
