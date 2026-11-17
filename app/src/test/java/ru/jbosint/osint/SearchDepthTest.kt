package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.data.EntityResolver
import ru.jbosint.osint.data.PageReader
import ru.jbosint.osint.data.PhotoSearch
import ru.jbosint.osint.data.VinDecoder

/**
 * Тесты ядра развёрнутого поиска: нормализация сущностей и зацепки,
 * чтение найденных страниц, расшифровка VIN через NHTSA, обратный поиск по
 * ссылке (разбор ответа SerpAPI).
 */
class SearchDepthTest {

    // ------------------------------------------------------------ нормализация

    @Test
    fun phonesNormalizedToSingleFormat() {
        assertEquals("+79990001122", EntityResolver.phone("8 (999) 000-11-22"))
        assertEquals("+79990001122", EntityResolver.phone("+7 999 000 11 22"))
        assertEquals("+79990001122", EntityResolver.phone("9990001122"))
        assertNull(EntityResolver.phone("123"))
    }

    @Test
    fun emailsAndHandlesNormalized() {
        assertEquals("user@example.com", EntityResolver.email(" User@Example.COM "))
        assertNull(EntityResolver.email("не почта"))
        assertEquals("toxa", EntityResolver.handle("https://t.me/toxa?start=1"))
        assertEquals("toxa", EntityResolver.handle("@ToXa"))
        assertNull(EntityResolver.handle("id"))
    }

    @Test
    fun namesMatchThroughTransliteration() {
        assertTrue(EntityResolver.sameName("Балахтина Юлия", "Юлия Балахтина"))
        assertTrue(EntityResolver.sameName("Балахтина Юлия Вадимовна", "Yulia Balakhtina"))
        assertFalse(EntityResolver.sameName("Иванов Иван", "Петров Пётр"))
    }

    @Test
    fun pivotsSkipDuplicatesAndJunk() {
        val facts = listOf(
            fact(FactCategory.CONTACT, "Телефон", "8 999 000-11-22"),
            fact(FactCategory.CONTACT, "Почта", "User@Example.com"),
            fact(FactCategory.PERSON, "Никнейм", "@toxa"),
            fact(FactCategory.OTHER, "Упоминание", "https://example.com/page", FactCategory.OTHER)
        )
        val pivots = EntityResolver.pivots(facts, limit = 5)
        assertEquals(3, pivots.size)
        assertTrue(pivots.any { it.kind == "PHONE" && it.value == "+79990001122" })
        assertTrue(pivots.any { it.kind == "EMAIL" && it.value == "user@example.com" })
        assertTrue(pivots.any { it.kind == "USERNAME" && it.query == "@toxa" })
        assertTrue(pivots.none { it.query.contains("example.com/page") })
    }

    @Test
    fun pivotsRespectExclusions() {
        val facts = listOf(fact(FactCategory.CONTACT, "Почта", "a@b.ru"))
        val pivots = EntityResolver.pivots(facts, exclude = setOf("email:a@b.ru"))
        assertTrue(pivots.isEmpty())
    }

    // ------------------------------------------------------------ чтение страниц

    @Test
    fun pageParsedIntoTitleDescriptionAndText() {
        val html = """
            <html><head><title>Иванов Иван — контакты</title>
            <meta name="description" content="Директор, Москва">
            <meta property="og:site_name" content="ООО Пример">
            <style>body{color:red}</style>
            <script>var a = 1;</script></head>
            <body><h1>Иванов Иван</h1><p>Почта: ivan.petrov@mail.ru, тел. 8 (999) 000-11-22</p>
            <a href="https://t.me/ivanov">Telegram</a></body></html>
        """.trimIndent()
        val page = PageReader.parse(html, "https://example.com/about")
        assertEquals("Иванов Иван — контакты", page.title)
        assertEquals("Директор, Москва", page.description)
        assertEquals("ООО Пример", page.siteName)
        assertTrue(page.ok)
        assertFalse(page.text.contains("var a"))

        val facts = PageReader.facts(page)
        assertTrue(facts.any { it.kind == "Упоминание на странице" })
        assertTrue(facts.any { it.kind == "Почта" && it.value == "ivan.petrov@mail.ru" })
        assertTrue(facts.any { it.kind == "Телефон" && it.value == "+79990001122" })
        assertTrue(facts.any { it.kind == "Организация" && it.value == "ООО Пример" })
    }

    @Test
    fun unreadableHostsAreSkipped() {
        assertFalse(PageReader.readable("https://vk.com/wall1_2"))
        assertFalse(PageReader.readable("https://example.com/file.pdf"))
        assertTrue(PageReader.readable("https://example.com/about"))
        assertTrue(PageReader.pickUrls(listOf("https://vk.com/x", "https://example.com/a"), 3).size == 1)
    }

    @Test
    fun emailsAndPhonesFromText() {
        assertEquals(listOf("a@b.ru"), PageReader.emails("пишите: a@b.ru спасибо"))
        assertEquals(listOf("+79990001122"), PageReader.phones("звонить +7 999 000-11-22"))
    }

    // ------------------------------------------------------------ VIN

    @Test
    fun nhtsaResponseParsed() {
        val json = """
            {"Count":1,"Message":"Results returned successfully","Results":[{
            "Make":"LADA","Model":"Vesta","ModelYear":"2019","PlantCountry":"RUSSIA",
            "BodyClass":"Sedan/Saloon","DisplacementCC":"1596.0","EngineCylinders":"4",
            "FuelTypePrimary":"Gasoline","VehicleType":"PASSENGER CAR","Manufacturer":"AVTOVAZ"}]}
        """.trimIndent()
        val facts = VinDecoder.parse(json, "XTA21150053912345")
        assertTrue(facts.any { it.kind == "Марка" && it.value == "LADA" })
        assertTrue(facts.any { it.kind == "Модель" && it.value == "Vesta" })
        assertTrue(facts.any { it.kind == "Год выпуска" && it.value == "2019" })
        assertTrue(facts.any { it.kind == "Страна сборки" && it.value == "RUSSIA" })
        assertTrue(facts.any { it.kind == "VIN подтверждён" })
        assertTrue(facts.all { it.sourceId == "nhtsa" })
    }

    @Test
    fun nhtsaEmptyResponseGivesNoFacts() {
        assertTrue(VinDecoder.parse("""{"Results":[]}""", "XTA21150053912345").isEmpty())
        assertTrue(VinDecoder.parse("не json", "XTA21150053912345").isEmpty())
    }

    // ------------------------------------------------------------ фото по ссылке

    @Test
    fun serpVisualMatchesParsed() {
        val json = """
            {"visual_matches":[
              {"title":"Профиль ВКонтакте","link":"https://vk.com/id123","source":"VK"},
              {"title":"Фото с форума","link":"https://forum.example.com/topic/1"},
              {"title":"дубль","link":"https://vk.com/id123?utm_source=x"}
            ],"search_metadata":{"status":"Success"}}
        """.trimIndent()
        val matches = PhotoSearch.parseSerp(json, "google_lens")
        assertEquals(2, matches.size)
        assertTrue(matches.any { it.platform == "VK" })
        assertEquals("Google Lens", matches.first().provider)
        assertTrue(matches.all { it.url.startsWith("https://") })
    }

    @Test
    fun serpErrorsReturnEmpty() {
        assertTrue(PhotoSearch.parseSerp("""{"error":"Invalid API key"}""").isEmpty())
        assertTrue(PhotoSearch.parseSerp("не json").isEmpty())
    }

    private fun fact(
        category: FactCategory,
        kind: String,
        value: String,
        categoryOverride: FactCategory? = null
    ) = Fact(
        category = categoryOverride ?: category,
        kind = kind,
        value = value,
        subject = "Тест",
        attributes = emptyMap(),
        sourceIds = setOf("test"),
        observations = 1,
        firstSeen = 0L,
        urls = emptySet()
    )

    @Test
    fun linksFromHrefGiveProfileHandles() {
        // Регресс: раньше ссылки искались в тексте, а после вырезания тегов их там нет.
        val html = """
            <html><head><title>Команда</title></head><body>
            <p>Наши соцсети:</p>
            <a href="https://t.me/jb_team">Telegram</a>
            <a href="https://github.com/jb-osint/app">GitHub</a>
            <a href="/local/page">Локальная</a>
            </body></html>
        """.trimIndent()
        val page = PageReader.parse(html, "https://example.org/team")
        assertTrue(page.links.contains("https://t.me/jb_team"))
        assertTrue(page.links.contains("https://github.com/jb-osint/app"))
        assertFalse(page.links.any { it.startsWith("/") })

        val facts = PageReader.facts(page)
        assertTrue(facts.any { it.kind == "Никнейм" && it.value == "@jb_team" })
        assertTrue(facts.any { it.kind == "Никнейм" && it.value == "@jb-osint" })
    }

    @Test
    fun metaParsedWithContentBeforeName() {
        val html = """
            <html><head><title>Страница</title>
            <meta content="Описание с обратным порядком" name="description">
            </head><body>Текст страницы, достаточный для того, чтобы страница считалась прочитанной.</body></html>
        """.trimIndent()
        val page = PageReader.parse(html, "https://example.org/a")
        assertEquals("Описание с обратным порядком", page.description)
    }

    @Test
    fun datesAreNotMistakenForPhones() {
        assertNull(EntityResolver.phone("17.11.2026"))
        assertNull(EntityResolver.phone("202611170341"))
        assertNull(EntityResolver.phone("171120261015"))
        assertTrue(PageReader.phones("обновлено 17.11.2026 10:15, заказ 123456789012").isEmpty())
        assertTrue(PageReader.phones("тел. 8 (999) 000-11-22").isNotEmpty())
    }

    @Test
    fun pageHandlesBecomePivots() {
        val html = """<html><head><title>Профиль</title></head><body>
            <p>Свяжитесь со мной в телеграме.</p>
            <a href="https://t.me/toxa">Telegram</a></body></html>"""
        val page = PageReader.parse(html, "https://example.org/p")
        val facts = PageReader.facts(page).map {
            Fact(
                category = it.category, kind = it.kind, value = it.value, subject = it.subject,
                attributes = it.attributes, sourceIds = setOf(it.sourceId), observations = 1,
                firstSeen = 0L, urls = setOfNotNull(it.sourceUrl.takeIf { v -> v.isNotBlank() })
            )
        }
        val pivots = EntityResolver.pivots(facts)
        assertTrue(pivots.any { it.kind == "USERNAME" && it.value == "toxa" })
    }
}
