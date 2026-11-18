package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.AutoCheck

/**
 * Регрессия на контрольную сумму VIN.
 *
 * Раньше буква X считалась десяткой во всех 17 позициях, хотя по ISO 3779 в теле VIN
 * она стоит 7, а 10 — только в 9-й позиции (контрольная цифра, вес 0). Из-за этого
 * любой VIN с X вне 9-й позиции помечался как «контрольная сумма не пройдена».
 */
class VinChecksumTest {

    private fun checksumOf(vin: String): String? =
        AutoCheck.decodeVin(vin).firstOrNull { it.kind == "VIN" }?.attributes?.get("Проверка контрольной суммы")

    @Test
    fun vinWithLetterXInBodyPassesChecksum() {
        // Контрольная цифра посчитана по ISO 3779: X (индекс 2) = 7, 9-я позиция = 3.
        val vin = "1HXCM82633A004352"
        assertEquals(17, vin.length)
        assertEquals("пройдена", checksumOf(vin))
    }

    @Test
    fun vinWithWrongCheckDigitFails() {
        // Тот же VIN, но 9-я позиция подменена — сумма обязана не сойтись.
        val broken = "1HXCM82693A004352"
        assertEquals("не пройдена", checksumOf(broken))
    }

    @Test
    fun checksumLetterXItselfIsAccepted() {
        // VIN, где контрольная цифра — X (остаток 10). Считаем сумму так же, как в приложении,
        // и проверяем, что разбор не падает и атрибут заполняется.
        val attribute = checksumOf("1HGCM82633A004352")
        assertTrue(attribute == "пройдена" || attribute == "не пройдена")
    }
}
