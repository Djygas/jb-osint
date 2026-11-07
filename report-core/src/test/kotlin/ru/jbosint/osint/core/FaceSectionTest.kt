package ru.jbosint.osint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.aggregate.Aggregator
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.ReportMeta
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.core.report.JsonReport
import ru.jbosint.osint.core.report.ReportSections

class FaceSectionTest {

    private fun raw(
        kind: String,
        value: String,
        source: String = "faceapi",
        attributes: Map<String, String> = emptyMap()
    ) = RawFact(FactCategory.DOCUMENT, kind, value, "photo.jpg", attributes, source)

    private val meta = ReportMeta(
        reportId = "R-1", analyst = "Тест", createdAt = 1_700_000_000_000L,
        appVersion = "1.12.0", queryType = "Фотография"
    )

    @Test
    fun `face matches are separated from photo metadata`() {
        val facts = listOf(
            raw("Файл", "photo.jpg", source = "photo"),
            raw("Лица на снимке", "1", source = "photo"),
            raw("Совпадение изображения", "https://vk.com/photo1", source = "photo",
                attributes = mapOf("совпадение" to "88%")),
            raw("Совпадение по фото", "https://example.org/page", attributes = mapOf("схожесть" to "94%"))
        )
        val data = Aggregator.aggregate("photo.jpg", facts, SearchStats(1, 1))
        val faces = ReportSections.faceFacts(data)
        assertEquals(2, faces.size)
        assertTrue(faces.any { it.value == "https://example.org/page" })
        assertFalse(faces.any { it.value == "photo.jpg" })
    }

    @Test
    fun `similarity parses percent and fraction formats`() {
        val percent = Aggregator.aggregate(
            "q", listOf(raw("Совпадение по фото", "https://a.ru/1", attributes = mapOf("схожесть" to "87%"))),
            SearchStats(1, 1)
        ).facts.first()
        assertEquals(87, ReportSections.faceSimilarity(percent))

        val fraction = Aggregator.aggregate(
            "q", listOf(raw("Совпадение по фото", "https://a.ru/2", attributes = mapOf("score" to "0.93"))),
            SearchStats(1, 1)
        ).facts.first()
        assertEquals(93, ReportSections.faceSimilarity(fraction))

        val missing = Aggregator.aggregate(
            "q", listOf(raw("Совпадение по фото", "https://a.ru/3")), SearchStats(1, 1)
        ).facts.first()
        assertNull(ReportSections.faceSimilarity(missing))
    }

    @Test
    fun `status honours similarity thresholds and cross confirmation`() {
        val facts = listOf(
            raw("Совпадение по фото", "https://vk.com/id1", attributes = mapOf("схожесть" to "95%")),
            raw("Совпадение изображения", "https://vk.com/photo", source = "photo",
                attributes = mapOf("совпадение" to "95%")),
            raw("Совпадение по фото", "https://ok.ru/x", attributes = mapOf("схожесть" to "72%")),
            raw("Совпадение по фото", "https://site.ru/y", attributes = mapOf("схожесть" to "41%"))
        )
        val data = Aggregator.aggregate("photo.jpg", facts, SearchStats(2, 2))
        val matches = data.facts.filter { it.kind == "Совпадение по фото" }
        assertEquals("Подтверждено", ReportSections.faceStatus(matches.first { it.value.contains("vk.com") }, data))
        assertEquals("Требует проверки", ReportSections.faceStatus(matches.first { it.value.contains("ok.ru") }, data))
        assertEquals("Не подтверждено", ReportSections.faceStatus(matches.first { it.value.contains("site.ru") }, data))
    }

    @Test
    fun `note always keeps the identity caveat`() {
        val data = Aggregator.aggregate(
            "photo.jpg",
            listOf(raw("Совпадение по фото", "https://example.org/p",
                attributes = mapOf("схожесть" to "88%", "ресурс" to "ProFaceFinder"))),
            SearchStats(1, 1)
        )
        val note = ReportSections.faceNote(data.facts.first(), data)
        assertTrue(note.contains("ProFaceFinder"))
        assertTrue(note.contains("88%"))
        assertTrue(note.contains("не подтверждает тождество"))
    }

    @Test
    fun `json keeps face counter`() {
        val data = Aggregator.aggregate(
            "photo.jpg",
            listOf(raw("Совпадение по фото", "https://example.org/p", attributes = mapOf("схожесть" to "90%"))),
            SearchStats(1, 1)
        )
        val json = JsonReport(meta, data).build()
        assertTrue(json.contains("\"face_matches\": 1"))
    }
}
