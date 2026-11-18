package ru.jbosint.osint.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN-код доступа к разделу анализа.
 *
 * Хранится не как SHA-256 (4 цифры перебираются мгновенно), а как PBKDF2-HMAC-SHA256
 * с индивидуальной солью и большим числом итераций: подбор даже 10 000 вариантов
 * становится дорогим. Формат строки: `pbkdf2$<итерации>$<соль base64>$<хеш base64>`.
 *
 * Старые PIN-коды, сохранённые как hex-SHA-256, поддерживаются для чтения:
 * после удачного ввода [needsRehash] вернёт true, и вызывающая сторона перезапишет
 * значение в новом формате.
 */
object PinHash {

    private const val PREFIX = "pbkdf2"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    private val random = SecureRandom()

    fun hash(pin: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = derive(pin, salt, iterations)
        return "$PREFIX\$$iterations\$${b64(salt)}\$${b64(key)}"
    }

    /** Проверка значения в любом из поддерживаемых форматов. */
    fun verify(pin: String, stored: String): Boolean {
        if (stored.isBlank()) return false
        if (!stored.startsWith("$PREFIX\$")) return constantTimeEquals(legacySha256(pin), stored)
        val parts = stored.split('$')
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { unb64(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { unb64(parts[3]) }.getOrNull() ?: return false
        return constantTimeEquals(b64(derive(pin, salt, iterations)), b64(expected))
    }

    /** true — значение хранится в старом формате и должно быть перезаписано после удачного ввода. */
    fun needsRehash(stored: String): Boolean = stored.isNotBlank() && !stored.startsWith("$PREFIX\$")

    /** Есть ли в хранилище хоть что-то похожее на корректное значение. */
    fun looksValid(stored: String): Boolean =
        stored.startsWith("$PREFIX\$") && stored.split('$').size == 4

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun legacySha256(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun unb64(value: String): ByteArray =
        java.util.Base64.getDecoder().decode(value)
}
