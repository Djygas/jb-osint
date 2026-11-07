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
        ProviderCatalog.all.forEach { spec ->
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
        ProviderCatalog.all.forEach { spec ->
            assertTrue("${spec.id}: пустой title", spec.title.isNotBlank())
            assertTrue("${spec.id}: пустой hint", spec.hint.isNotBlank())
            assertTrue("${spec.id}: docsUrl не https", spec.docsUrl.startsWith("https://"))
        }
    }
}
