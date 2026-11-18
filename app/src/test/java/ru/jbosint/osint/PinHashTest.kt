package ru.jbosint.osint

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.PinHash
import java.security.MessageDigest

/** PIN-код: PBKDF2 вместо «голого» SHA-256 и совместимость со старым форматом. */
class PinHashTest {

    private fun legacySha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun hashVerifiesAndRejectsWrongPin() {
        val stored = PinHash.hash("4821")
        assertTrue(PinHash.verify("4821", stored))
        assertFalse(PinHash.verify("4822", stored))
        assertFalse(PinHash.verify("", stored))
    }

    @Test
    fun samePinGivesDifferentHashes() {
        val first = PinHash.hash("1111")
        val second = PinHash.hash("1111")
        assertNotEquals(first, second) // разные соли
        assertTrue(PinHash.verify("1111", first))
        assertTrue(PinHash.verify("1111", second))
        assertTrue(PinHash.looksValid(first))
    }

    @Test
    fun legacySha256StillUnlocksAndIsMarkedForRehash() {
        val legacy = legacySha256("7777")
        assertFalse(PinHash.looksValid(legacy))
        assertTrue(PinHash.needsRehash(legacy))
        assertTrue(PinHash.verify("7777", legacy))
        assertFalse(PinHash.verify("7778", legacy))
    }

    @Test
    fun newFormatDoesNotNeedRehash() {
        val stored = PinHash.hash("1234")
        assertFalse(PinHash.needsRehash(stored))
        assertTrue(PinHash.looksValid(stored))
    }

    @Test
    fun brokenStoredValueNeverPasses() {
        assertFalse(PinHash.verify("1234", ""))
        assertFalse(PinHash.verify("1234", "pbkdf2\$abc"))
        assertFalse(PinHash.verify("1234", "pbkdf2\$1000\$!!!\$???"))
    }
}
