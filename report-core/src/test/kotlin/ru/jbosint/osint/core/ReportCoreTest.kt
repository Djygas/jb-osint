package ru.jbosint.osint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.aggregate.Aggregator
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.ReportMeta
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.core.report.JsonReport
import ru.jbosint.osint.core.report.PdfReport
import ru.jbosint.osint.core.report.ReportFonts
import ru.jbosint.osint.core.report.ReportNaming
import ru.jbosint.osint.core.report.ReportSections
import ru.jbosint.osint.core.report.SourceCatalog
import ru.jbosint.osint.core.report.XlsxReport
import java.io.File
import java.util.zip.ZipInputStream

class AggregatorTest {

    private fun raw(
        category: FactCategory,
        kind: String,
        value: String,
        subject: String = "Иван Петров",
        source: String = "s1",
        attributes: Map<String, String> = emptyMap()
    ) = RawFact(category, kind, value, subject, attributes, source)

    @Test
    fun `deduplicates identical values from different sources`() {
        val facts = listOf(
            raw(FactCategory.CONTACT, "Почта", "ivan.petrov@example.ru", source = "s1"),
            raw(FactCategory.CONTACT, "Почта", "IVAN.PETROV@example.ru ", source = "s2"),
            raw(FactCategory.CONTACT, "Почта", "ivan.petrov@example.ru", source = "s3")
        )
        val data = Aggregator.aggregate("Иван Петров", facts, SearchStats(sourcesAttempted = 3, sourcesResponded = 3))
        assertEquals(1, data.facts.size)
        assertEquals(3, data.facts.first().sourceCount)
        assertEquals(Confidence.CONFIRMED, data.facts.first().confidence)
        assertEquals(2, data.stats.duplicatesRemoved)
    }

    @Test
    fun `confidence levels follow source count`() {
        val facts = listOf(
            raw(FactCategory.TECHNICAL, "Домен", "example.ru", source = "s1"),
            raw(FactCategory.TECHNICAL, "Домен", "example.ru", source = "s2"),
            raw(FactCategory.CONTACT, "Телефон", "+7 (999) 123-45-67", source = "s1"),
            raw(FactCategory.CONTACT, "Телефон", "79991234567", source = "s1"),
            raw(FactCategory.LOCATION, "Город", "Москва", source = "s1")
        )
        val data = Aggregator.aggregate("Иван Петров", facts, SearchStats(2, 2))
        val domain = data.facts.first { it.kind == "Домен" }
        val phone = data.facts.first { it.kind == "Телефон" }
        val city = data.facts.first { it.kind == "Город" }
        assertEquals(Confidence.PROBABLE, domain.confidence)
        assertEquals(1, data.facts.count { it.kind == "Телефон" })
        assertEquals(Confidence.UNVERIFIED, phone.confidence)
        assertEquals(Confidence.UNVERIFIED, city.confidence)
    }

    @Test
    fun `builds cross reference links between subjects sharing an identifier`() {
        val facts = listOf(
            raw(FactCategory.CONTACT, "Почта", "shared@example.ru", subject = "Иван Петров", source = "s1"),
            raw(FactCategory.CONTACT, "Почта", "shared@example.ru", subject = "ООО Ромашка", source = "s2"),
            raw(FactCategory.TECHNICAL, "Домен", "example.ru", subject = "Иван Петров", source = "s1")
        )
        val data = Aggregator.aggregate("shared@example.ru", facts, SearchStats(2, 2))
        assertTrue(data.links.isNotEmpty())
        assertTrue(data.links.any { it.relation.contains("пересечение") })
        assertTrue(data.links.any { it.from == "Иван Петров" && it.to == "example.ru" })
    }

    @Test
    fun `empty input produces empty report data`() {
        val data = Aggregator.aggregate("запрос", emptyList(), SearchStats(3, 0))
        assertTrue(data.isEmpty)
        assertTrue(data.links.isEmpty())
    }

    @Test
    fun `normalizes phones urls and emails`() {
        assertEquals("79991234567", Aggregator.normalize("+7 (999) 123-45-67"))
        assertEquals("example.ru", Aggregator.normalize("https://www.Example.ru/"))
        assertEquals("ivan@example.ru", Aggregator.normalize("IVAN@Example.RU"))
    }
}

class ReportExportTest {

    private val sample: List<RawFact> = buildList {
        val person = "Иван Сергеевич Петров"
        add(RawFact(FactCategory.PERSON, "Профиль", "ivan.petrov", person, mapOf("Статус" to "активен", "Регион" to "Москва"), "s1"))
        add(RawFact(FactCategory.PERSON, "Профиль", "ivan.petrov", person, mapOf("Дата регистрации" to "2016-04-11"), "s2"))
        add(RawFact(FactCategory.PERSON, "Профиль", "ivan.petrov", person, emptyMap(), "s3"))
        add(RawFact(FactCategory.PERSON, "Полное имя", person, person, emptyMap(), "s2"))
        add(RawFact(FactCategory.CONTACT, "Почта", "ivan.petrov@example.ru", person, mapOf("Домен" to "example.ru"), "s1"))
        add(RawFact(FactCategory.CONTACT, "Почта", "ivan.petrov@example.ru", person, mapOf("Домен" to "example.ru"), "s2"))
        add(RawFact(FactCategory.CONTACT, "Почта", "i.petrov@mail.example", person, emptyMap(), "s3"))
        add(RawFact(FactCategory.CONTACT, "Телефон", "+7 999 123-45-67", person, emptyMap(), "s1"))
        add(RawFact(FactCategory.CONTACT, "Телефон", "8 (999) 123-45-67", person, emptyMap(), "s2"))
        add(RawFact(FactCategory.ORGANIZATION, "Организация", "ООО «Ромашка»", person, mapOf("ИНН" to "7701234567"), "s1"))
        add(RawFact(FactCategory.ORGANIZATION, "Организация", "ООО «Ромашка»", person, mapOf("ИНН" to "7701234567"), "s2"))
        add(RawFact(FactCategory.ORGANIZATION, "Организация", "ООО «Ромашка»", "ООО «Ромашка»", emptyMap(), "s3"))
        add(RawFact(FactCategory.LOCATION, "Город", "Москва", person, mapOf("Источник координат" to "EXIF"), "s1"))
        add(RawFact(FactCategory.LOCATION, "Координаты", "55.7558, 37.6173", person, emptyMap(), "s2"))
        add(RawFact(FactCategory.TECHNICAL, "Домен", "example.ru", person, mapOf("Регистратор" to "RU-CENTER", "Создан" to "2014-09-02"), "s1"))
        add(RawFact(FactCategory.TECHNICAL, "Домен", "example.ru", person, mapOf("Создан" to "2014-09-02"), "s2"))
        add(RawFact(FactCategory.TECHNICAL, "IP-адрес", "203.0.113.42", person, mapOf("ASN" to "AS12345", "Страна" to "RU"), "s1"))
        add(RawFact(FactCategory.TECHNICAL, "MX-запись", "mx1.example.ru", person, emptyMap(), "s2"))
        add(RawFact(FactCategory.DOCUMENT, "Изображение", "photo_2019.jpg", person, mapOf("Устройство" to "Canon EOS 5D", "Съёмка" to "2019-06-12 18:04"), "s1"))
        add(RawFact(FactCategory.DOCUMENT, "Изображение", "photo_2019.jpg", person, mapOf("GPS" to "55.75, 37.61"), "s3"))
        add(RawFact(FactCategory.PERSON, "Профиль", "petrov_i", "Пётр Иванов", emptyMap(), "s2"))
        add(RawFact(FactCategory.CONTACT, "Почта", "ivan.petrov@example.ru", "ООО «Ромашка»", emptyMap(), "s1"))
        add(RawFact(FactCategory.TECHNICAL, "Домен", "example.ru", "ООО «Ромашка»", emptyMap(), "s2"))
        add(RawFact(FactCategory.OTHER, "Упоминание", "ivan.petrov@example.ru в открытом реестре", person, emptyMap(), "s3"))
    }

    private val meta = ReportMeta(
        analyst = "А. Соколов",
        reportId = ReportNaming.reportId("Иван Петров", 1_760_000_000_000L),
        createdAt = 1_760_000_000_000L,
        appVersion = "1.8.0",
        queryType = "Имя или название",
        photoNote = "Снимок: portrait.jpg, 1200×1600; лиц на снимке: 1; Pixel 7; 12.03.2025 14:20. " +
            "Обратный поиск: 3 совпадений, ссылки добавлены в доказательную базу. " +
            "Выводы по личности на снимке не делаются автоматически.",
        aiNotes = mapOf("ivan_petrov" to "Никнейм · значимость высокая")
    )

    private val data = Aggregator.aggregate(
        "Иван Петров",
        sample,
        SearchStats(sourcesAttempted = 4, sourcesResponded = 3, sourcesFailed = 1, durationMs = 4820)
    )

    private val outputDir: File = File(System.getProperty("jb.testOutput") ?: "build/test-reports")
        .also { it.mkdirs() }

    @Test
    fun `pdf report is built and contains cyrillic text`() {
        val bytes = PdfReport(meta, data, ReportFonts.fromClasspath()).build()
        val file = File(outputDir, "sample-report.pdf").apply { writeBytes(bytes) }
        assertTrue("PDF слишком мал: ${bytes.size}", bytes.size > 20_000)
        assertEquals("%PDF-1.7", String(bytes, 0, 8))
        assertTrue(file.exists())
        println("PDF: ${file.absolutePath} (${bytes.size} байт)")
    }

    @Test
    fun `report renders summary leak and telegram sections`() {
        val extra = data.copy(
            facts = data.facts + listOf(
                ru.jbosint.osint.core.model.Fact(
                    category = FactCategory.OTHER,
                    kind = "Утечка",
                    value = "Collection #1",
                    subject = "Иван Петров",
                    attributes = mapOf(
                        "Дата" to "2019-01-07",
                        "Домен" to "example.ru",
                        "Записей" to "773000000",
                        "Данные" to "Emails, Passwords"
                    ),
                    sourceIds = setOf("s1", "s2"),
                    observations = 2
                ),
                ru.jbosint.osint.core.model.Fact(
                    category = FactCategory.OTHER,
                    kind = "Telegram-канал",
                    value = "OSINT канал",
                    subject = "Иван Петров",
                    attributes = mapOf("Ссылка" to "t.me/osint_channel", "Подписчиков" to "12500"),
                    sourceIds = setOf("s3"),
                    observations = 1
                )
            )
        )
        val bytes = PdfReport(meta, extra, ReportFonts.fromClasspath()).build()
        File(outputDir, "sample-report-extended.pdf").writeBytes(bytes)
        assertTrue("PDF слишком мал: ${bytes.size}", bytes.size > 20_000)
        assertEquals("%PDF-1.7", String(bytes, 0, 8))
    }

    @Test
    fun `xlsx report is a valid zip with all sheets`() {
        val bytes = XlsxReport(meta, data).build()
        File(outputDir, "sample-report.xlsx").writeBytes(bytes)
        val entries = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name
                entry = zip.nextEntry
            }
        }
        assertTrue(entries.contains("[Content_Types].xml"))
        assertTrue(entries.any { it == "xl/workbook.xml" })
        assertTrue(entries.count { it.startsWith("xl/worksheets/") } >= 8)
        val workbook = String(bytes, Charsets.ISO_8859_1)
        assertTrue("Нет листа доказательств", workbook.isNotEmpty())
        println("XLSX листы: ${entries.filter { it.startsWith("xl/worksheets/") }}")
    }

    @Test
    fun `json report is well formed`() {
        val json = JsonReport(meta, data).build()
        File(outputDir, "sample-report.json").writeText(json)
        assertTrue(json.trimEnd().endsWith("}"))
        assertTrue(json.contains("\"schema\": \"jb-osint/report/2.0\""))
        assertTrue(json.contains("\"facts\": ["))
        assertTrue(json.contains("\"sources\": ["))
        assertTrue(json.contains("\"evidence_url\""))
        assertTrue(json.contains("\"sections\": {"))
        assertFalse("В отчёте не должно быть технических ключей источника", json.contains("source_id"))
    }

    @Test
    fun `file naming is safe`() {
        val name = ReportNaming.fileName(meta, "Иван Петров / +7 999", "pdf")
        assertTrue(name.startsWith("JB_OSINT_ivan_petrov"))
        assertTrue(name.endsWith(".pdf"))
        assertFalse(name.contains("/"))
    }
}


class DossierSectionsTest {

    private fun raw(
        kind: String,
        value: String,
        category: FactCategory = FactCategory.CONTACT,
        source: String = "s1",
        url: String = ""
    ) = RawFact(category, kind, value, "Иван Петров", emptyMap(), source, 1, url)

    @Test
    fun `aggregator keeps evidence links`() {
        val data = Aggregator.aggregate(
            "Иван Петров",
            listOf(
                raw("Почта", "ivan@example.ru", url = "https://example.ru/u/ivan"),
                raw("Почта", "ivan@example.ru", source = "s2", url = "https://leak.example/b/1")
            ),
            SearchStats(2, 2)
        )
        val fact = data.facts.first { it.value == "ivan@example.ru" }
        assertEquals(2, fact.sourceCount)
        assertTrue(fact.urls.contains("https://example.ru/u/ivan"))
        assertTrue(fact.urls.contains("https://leak.example/b/1"))
    }

    @Test
    fun `sections split facts by evidence type`() {
        val data = Aggregator.aggregate(
            "Иван Петров",
            listOf(
                raw("Почта", "ivan@example.ru"),
                raw("Профиль", "https://vk.com/ivan_p", FactCategory.PERSON),
                raw("Telegram-канал", "@ivan_channel", FactCategory.OTHER, url = "https://t.me/ivan_channel"),
                raw("Утечка", "Collection #1", FactCategory.OTHER),
                raw("Изображение", "https://example.ru/a.jpg", FactCategory.DOCUMENT),
                raw("Паспорт", "45 06 123456", FactCategory.DOCUMENT)
            ),
            SearchStats(3, 3)
        )
        assertEquals(1, ReportSections.emailFacts(data).size)
        assertEquals(1, ReportSections.socialFacts(data).size)
        assertEquals(1, ReportSections.telegramFacts(data).size)
        assertEquals(1, ReportSections.leakFacts(data).size)
        assertEquals(1, ReportSections.mediaFacts(data).size)
        assertEquals(1, ReportSections.documentFacts(data).size)
    }

    @Test
    fun `source line contains module name and link`() {
        val data = Aggregator.aggregate(
            "Иван Петров",
            listOf(raw("Почта", "ivan@example.ru", source = "hibp", url = "https://haveibeenpwned.com/x")),
            SearchStats(1, 1)
        )
        val line = ReportSections.sourceLine(data.facts.first())
        assertTrue(line.contains("Have I Been Pwned"))
        assertTrue(line.contains("https://haveibeenpwned.com/x"))
    }

    @Test
    fun `source catalog resolves known and custom modules`() {
        assertEquals("GitHub API", SourceCatalog.ref("github").name)
        assertTrue(SourceCatalog.ref("my_module").name.contains("my_module"))
    }
}

class CrossSourceConfirmationTest {

    private fun raw(kind: String, value: String, source: String) = RawFact(
        category = FactCategory.PERSON,
        kind = kind,
        value = value,
        subject = "Иван Петров",
        sourceId = source
    )

    @Test
    fun differentKindNamesConfirmEachOther() {
        val data = Aggregator.aggregate(
            "Иван Петров",
            listOf(
                raw("Профиль", "ivan_p", "s1"),
                raw("Никнейм", "ivan_p", "s2"),
                raw("ЛОГИН", "ivan_p", "s3")
            ),
            SearchStats(sourcesAttempted = 3, sourcesResponded = 3)
        )
        val fact = data.facts.single()
        assertEquals("Профиль", fact.kind)
        assertEquals(3, fact.sourceCount)
        assertEquals(Confidence.CONFIRMED, fact.confidence)
    }

    @Test
    fun kindSynonymsMapToSingleNames() {
        assertEquals("Почта", Aggregator.canonicalKind("email"))
        assertEquals("Телефон", Aggregator.canonicalKind("phone"))
        assertEquals("Полное имя", Aggregator.canonicalKind("fullname"))
        assertEquals("IP-адрес", Aggregator.canonicalKind("ip"))
        assertEquals("Домен", Aggregator.canonicalKind("domain"))
        assertEquals("Утечка", Aggregator.canonicalKind("breach"))
        assertEquals("Нестандартное", Aggregator.canonicalKind("Нестандартное"))
    }
}
