package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.AdminPolicy
import ru.jbosint.osint.data.BackupCodec
import ru.jbosint.osint.data.BackupCustom
import ru.jbosint.osint.data.BackupKey
import ru.jbosint.osint.data.BackupPayload
import ru.jbosint.osint.data.VaultCrypto

/**
 * Шифрованный контейнер резервных копий, формат полезной нагрузки и правила
 * мастер-пароля — без Android: именно эти тесты подтверждают, что файл нельзя
 * открыть без пароля и что ключи возвращаются без потерь.
 */
class VaultBackupTest {

    private val password = "Sib3r-Secret-Pass".toCharArray()

    @Test
    fun `keys envelope roundtrip`() {
        val payload = """{"schema":"jb-osint/backup/1","providers":{"hibp":{"key":"abc"}}}"""
        val bytes = VaultCrypto.encrypt(payload.toByteArray(), password, VaultCrypto.FORMAT_KEYS)
        val opened = VaultCrypto.decrypt(bytes, password)
        assertEquals(VaultCrypto.FORMAT_KEYS, opened.format)
        assertEquals(payload, String(opened.payload, Charsets.UTF_8))
        assertTrue(opened.created > 0)
    }

    @Test
    fun `wrong password and tampering are rejected`() {
        val bytes = VaultCrypto.encrypt("s3cret".toByteArray(), password, VaultCrypto.FORMAT_CONFIG)
        val wrong = runCatching { VaultCrypto.decrypt(bytes, "Another-Password1".toCharArray()) }
        assertTrue(wrong.isFailure)
        assertTrue(wrong.exceptionOrNull() is VaultCrypto.VaultException)

        val text = String(bytes, Charsets.UTF_8)
        val start = text.indexOf("\"data\":\"") + 8
        val broken = text.substring(0, start) + (if (text[start] == 'A') 'B' else 'A') + text.substring(start + 1)
        val tampered = runCatching { VaultCrypto.decrypt(broken.toByteArray(), password) }
        assertTrue(tampered.isFailure)
    }

    @Test
    fun `salt and iv are random for identical payload`() {
        val first = String(VaultCrypto.encrypt("same".toByteArray(), password, VaultCrypto.FORMAT_KEYS), Charsets.UTF_8)
        val second = String(VaultCrypto.encrypt("same".toByteArray(), password, VaultCrypto.FORMAT_KEYS), Charsets.UTF_8)
        assertFalse(first == second)
    }

    @Test
    fun `short password is refused`() {
        val result = runCatching { VaultCrypto.encrypt("x".toByteArray(), "1234567".toCharArray(), VaultCrypto.FORMAT_KEYS) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `peek format reads header without password`() {
        val keys = VaultCrypto.encrypt("a".toByteArray(), password, VaultCrypto.FORMAT_KEYS)
        val config = VaultCrypto.encrypt("b".toByteArray(), password, VaultCrypto.FORMAT_CONFIG)
        assertEquals(VaultCrypto.FORMAT_KEYS, VaultCrypto.peekFormat(keys))
        assertEquals(VaultCrypto.FORMAT_CONFIG, VaultCrypto.peekFormat(config))
        assertNull(VaultCrypto.peekFormat("not a vault".toByteArray()))
    }

    @Test
    fun `backup payload survives codec`() {
        val payload = BackupPayload(
            providers = mapOf(
                "hibp" to BackupKey("key-1", "", true),
                "shodan" to BackupKey("key-2", "second", false)
            ),
            assistant = mapOf("deepseek" to "sk-test"),
            assistantProvider = "deepseek",
            assistantModel = "deepseek-chat",
            custom = listOf(BackupCustom("id-1", "{\"id\":\"id-1\"}", "custom-key")),
            analyst = "А. Смирнов",
            demoMode = true,
            mode = BackupPayload.MODE_USER
        )
        val json = BackupCodec.encode(payload)
        val restored = BackupCodec.decode(json)
        assertNotNull(restored)
        assertEquals(2, restored!!.providers.size)
        assertEquals("key-2", restored.providers["shodan"]?.key)
        assertEquals("second", restored.providers["shodan"]?.second)
        assertEquals(false, restored.providers["shodan"]?.enabled)
        assertEquals("sk-test", restored.assistant["deepseek"])
        assertEquals("deepseek", restored.assistantProvider)
        assertEquals(1, restored.custom.size)
        assertEquals("custom-key", restored.custom[0].key)
        assertEquals("А. Смирнов", restored.analyst)
        assertTrue(restored.demoMode)
        assertEquals(BackupPayload.MODE_USER, restored.mode)
        assertEquals(4, restored.keyCount)
    }

    @Test
    fun `codec rejects foreign schema`() {
        assertNull(BackupCodec.decode("{\"schema\":\"other\",\"providers\":{}}"))
        assertNull(BackupCodec.decode("не json"))
    }

    @Test
    fun `empty backup is detected`() {
        assertTrue(BackupPayload().isEmpty)
        assertFalse(BackupPayload(providers = mapOf("x" to BackupKey("k"))).isEmpty)
    }

    @Test
    fun `password policy`() {
        assertEquals("Пароль короче 8 символов", AdminPolicy.validate("short"))
        assertNull(AdminPolicy.validate("GoodPass1"))
        assertNotNull(AdminPolicy.validate("12345678"))
        assertNotNull(AdminPolicy.validate(" GoodPass1"))
    }

    @Test
    fun `lockout after five failures`() {
        val now = 1_760_000_000_000L
        assertEquals(0L, AdminPolicy.lockUntil(now, 4))
        assertEquals(now + AdminPolicy.LOCK_MS, AdminPolicy.lockUntil(now, 5))
        assertTrue(AdminPolicy.lockText(AdminPolicy.LOCK_MS).contains("мин"))
        assertEquals("", AdminPolicy.lockText(0))
    }

    @Test
    fun `recovery code format and normalisation`() {
        val code = AdminPolicy.newRecoveryCode()
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(code))
        assertEquals(
            AdminPolicy.normaliseRecovery(code),
            AdminPolicy.normaliseRecovery(code.lowercase().replace("-", " "))
        )
    }
}
