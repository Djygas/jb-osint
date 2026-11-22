package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.data.Remote

/** Сверка фактов: маппинг в запрос и разбор ответа сервера. */
class CrossCheckTest {

    private fun fact(
        kind: String,
        value: String,
        subject: String,
        source: String = "hibp"
    ) = Fact(
        category = FactCategory.OTHER,
        kind = kind,
        value = value,
        subject = subject,
        attributes = emptyMap(),
        sourceIds = setOf(source),
        observations = 1
    )

    @Test
    fun factsAreMapped() {
        val mapped = Remote.analyzeFacts(listOf(fact("Должность", "Директор", "Иванов")))
        assertEquals(1, mapped.size)
        assertEquals("Иванов", mapped.first().subject)
        assertEquals("Должность", mapped.first().field)
        assertEquals("Директор", mapped.first().value)
        assertEquals("hibp", mapped.first().source)
    }

    @Test
    fun emptyValuesAndDuplicatesAreDropped() {
        val mapped = Remote.analyzeFacts(
            listOf(
                fact("Почта", "a@b.ru", "Иванов"),
                fact("Почта", "a@b.ru", "Иванов"),
                fact("Почта", "", "Иванов")
            )
        )
        assertEquals(1, mapped.size)
    }

    @Test
    fun limitIsRespected() {
        val facts = (1..10).map { fact("Поле", "значение $it", "Иванов") }
        assertEquals(4, Remote.analyzeFacts(facts, limit = 4).size)
    }

    @Test
    fun contradictionsAreParsed() {
        val result = Remote.parseAnalyze(
            """{"contradictions":[{"object":"Иванов","field":"Должность","values":["Директор","Стажёр"]}],""" +
                """"notes":["Найдено противоречий: 1"],"provider":"local-rules"}"""
        )
        assertEquals(1, result.contradictions.size)
        assertEquals("Иванов", result.contradictions.first().subject)
        assertEquals("Должность", result.contradictions.first().field)
        assertEquals(listOf("Директор", "Стажёр"), result.contradictions.first().values)
        assertEquals("local-rules", result.provider)
        assertEquals(1, result.notes.size)
    }

    @Test
    fun emptyAnswerIsParsed() {
        val result = Remote.parseAnalyze(
            """{"contradictions":[],"notes":["Противоречий не найдено"],"provider":"local-rules"}"""
        )
        assertTrue(result.contradictions.isEmpty())
        assertEquals(1, result.notes.size)
    }
}
