package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.DocRecord
import ru.jbosint.osint.data.DocSearch
import ru.jbosint.osint.data.DocumentText
import ru.jbosint.osint.data.Remote
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Документы досье: извлечение текста, поиск, разбор серверного ответа. */
class DocumentsTest {

    private fun docx(text: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val xml = "<w:document><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>Второй абзац</w:t></w:r></w:p></w:body></w:document>"
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }

    private fun record(id: String, title: String) = DocRecord(
        id = id, title = title, fileName = "$title.txt", bytes = 100, chars = 900,
        addedAt = 1L, sha256 = "x", provider = "local"
    )

    @Test
    fun docxTextIsExtracted() {
        val text = DocumentText.fromDocx(docx("Реквизиты компании"))
        assertTrue(text.contains("Реквизиты компании"))
        assertTrue(text.contains("Второй абзац"))
        assertTrue(text.contains("\n"))
    }

    @Test
    fun cp1251IsDecoded() {
        val bytes = "Протокол собрания".toByteArray(charset("windows-1251"))
        assertEquals("Протокол собрания", DocumentText.decode(bytes))
    }

    @Test
    fun htmlIsFlattened() {
        val html = "<html><style>b{color:red}</style><body><h1>ООО Ромашка</h1>" +
            "<script>secret=1</script><p>ИНН 7700000000</p></body></html>"
        val text = DocumentText.extract("page.html", html.toByteArray()) ?: ""
        assertTrue(text.contains("ООО Ромашка"))
        assertTrue(text.contains("7700000000"))
        assertTrue(!text.contains("secret=1"))
        assertTrue(!text.contains("color:red"))
    }

    @Test
    fun pdfGoesToServerMode() {
        assertNull(DocumentText.extract("scan.pdf", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun searchRequiresAllTerms() {
        val docs = listOf(
            record("a", "Договор") to
                "Договор аренды помещения. Арендодатель: ООО Ромашка. Срок 12 месяцев.",
            record("b", "Протокол") to
                "Протокол собрания. Присутствовали: Иванов, Петров."
        )
        val hits = DocSearch.search(docs, "договор ромашка")
        assertEquals(1, hits.size)
        assertEquals("a", hits.first().doc.id)
        assertTrue(hits.first().snippet.contains("Договор", ignoreCase = true))
        assertTrue(hits.first().score >= 2)
    }

    @Test
    fun shortWordsAreIgnored() {
        val docs = listOf(record("a", "Договор") to "ДОГОВОР аренды")
        assertEquals(1, DocSearch.search(docs, "ДОГОВОР").size)
        assertEquals(listOf("он"), DocSearch.terms("я и он"))
        assertTrue(DocSearch.search(docs, "я").isEmpty())
    }

    @Test
    fun snippetKeepsMatchVisible() {
        val text = "а".repeat(400) + " договор " + "б".repeat(400)
        val snippet = DocSearch.snippet(text, "договор", 80)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("договор"))
    }

    @Test
    fun providerTagsAreReadable() {
        assertEquals("локально", DocumentText.providerTag("local"))
        assertEquals("сервер", DocumentText.providerTag("server"))
        assertEquals("OCR", DocumentText.providerTag("ocr"))
    }

    @Test
    fun serverAnswerIsParsed() {
        val doc = Remote.parseExtract(
            """{"filename":"a.pdf","chars":5,"provider":"pypdf","text":"Текст"}"""
        )
        assertEquals("Текст", doc.text)
        assertEquals(5, doc.chars)
        assertEquals("pypdf", doc.provider)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyServerAnswerFails() {
        Remote.parseExtract("""{"text":""}""")
    }
}
