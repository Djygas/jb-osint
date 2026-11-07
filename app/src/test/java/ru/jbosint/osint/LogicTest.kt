package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.Connectors
import ru.jbosint.osint.data.ProviderCatalog
import ru.jbosint.osint.data.QueryDetector
import ru.jbosint.osint.data.QueryKind

class QueryDetectorTest {

    @Test
    fun detectsEmail() {
        assertEquals(QueryKind.EMAIL, QueryDetector.detect("ivan.petrov@example.com"))
        assertEquals(QueryKind.EMAIL, QueryDetector.detect("  a_b+tag@sub.domain.ru  "))
    }

    @Test
    fun detectsPhone() {
        assertEquals(QueryKind.PHONE, QueryDetector.detect("+7 (999) 123-45-67"))
        assertEquals(QueryKind.PHONE, QueryDetector.detect("89991234567"))
    }

    @Test
    fun detectsIp() {
        assertEquals(QueryKind.IP, QueryDetector.detect("8.8.8.8"))
        assertEquals(QueryKind.IP, QueryDetector.detect("192.168.0.1"))
    }

    @Test
    fun detectsDomain() {
        assertEquals(QueryKind.DOMAIN, QueryDetector.detect("example.com"))
        assertEquals(QueryKind.DOMAIN, QueryDetector.detect("mail.example.co.uk"))
    }

    @Test
    fun detectsUsername() {
        assertEquals(QueryKind.USERNAME, QueryDetector.detect("@durov"))
        assertEquals(QueryKind.USERNAME, QueryDetector.detect("durov"))
    }

    @Test
    fun fallsBackToName() {
        assertEquals(QueryKind.NAME, QueryDetector.detect("Иван Петров"))
        assertEquals(QueryKind.NAME, QueryDetector.detect(""))
    }
}

class ProviderCatalogTest {

    @Test
    fun hasAtLeastTenProviders() {
        assertTrue("ожидалось >= 10 провайдеров", ProviderCatalog.all.size >= 10)
    }

    @Test
    fun idsAreUniqueAndFilled() {
        val ids = ProviderCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun everyProviderHasConnector() {
        ProviderCatalog.all.filter { !it.photoOnly }.forEach { spec ->
            assertNotNull("нет коннектора для ${spec.id}", Connectors.byId(spec.id))
        }
    }

    @Test
    fun keylessProvidersAreMarked() {
        val keyless = ProviderCatalog.all.filter { it.keyless }.map { it.id }
        assertTrue(keyless.containsAll(listOf("ddg", "wikipedia", "urlscan", "gitlab")))
    }

    @Test
    fun yandexDocsPointToLiveRegistrationPage() {
        val yandex = ProviderCatalog.byId("yandex")
        assertNotNull(yandex)
        assertTrue(yandex!!.docsUrl.endsWith("/concepts/registration"))
    }

    @Test
    fun everyProviderHasUsableMetadata() {
        ProviderCatalog.all.filter { !it.photoOnly }.forEach { spec ->
            assertTrue("${spec.id}: пустой title", spec.title.isNotBlank())
            assertTrue("${spec.id}: пустой hint", spec.hint.isNotBlank())
            assertTrue("${spec.id}: docsUrl не https", spec.docsUrl.startsWith("https://"))
        }
    }
}

class PhotoSearchTest {

    @org.junit.Test
    fun handlesExtractedFromSourceLinks() {
        val handles = ru.jbosint.osint.data.PhotoAnalyzer.handlesFrom(
            listOf(
                "https://x.com/ivan_petrov/status/12345",
                "https://vk.com/id777888",
                "https://github.com/ivan-petrov/repo",
                "https://example.org/status/1",
                "https://t.me/ivan_channel"
            )
        )
        org.junit.Assert.assertEquals(listOf("ivan_petrov", "id777888", "ivan-petrov", "ivan_channel"), handles)
    }

    @org.junit.Test
    fun photoFactsCarryHashAndFaces() {
        val photo = ru.jbosint.osint.data.PhotoAnalysis(
            fileName = "scan.jpg",
            mime = "image/jpeg",
            sha256 = "a".repeat(64),
            pHash = "0123456789abcdef",
            width = 1200,
            height = 800,
            faces = 2,
            exif = mapOf("Модель" to "Pixel 7", "Координаты" to "55.75, 37.61"),
            bytes = ByteArray(4)
        )
        val facts = ru.jbosint.osint.data.PhotoAnalyzer.facts(photo)
        org.junit.Assert.assertTrue(facts.any { it.kind == "Файл" && it.value == "scan.jpg" })
        org.junit.Assert.assertTrue(facts.any { it.kind == "Лица на снимке" && it.value == "2" })
        org.junit.Assert.assertTrue(facts.any { it.kind == "Координаты" })
        org.junit.Assert.assertTrue(facts.all { it.sourceId == "photo" })
    }
}
