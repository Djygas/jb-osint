package ru.jbosint.osint.data

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрованный контейнер для резервных копий ключей и конфигурации.
 *
 * Пароль пользователя не хранится: из него через PBKDF2-HMAC-SHA256 выводится
 * ключ AES-256, которым шифруется тело (AES/GCM — с проверкой целостности).
 * Соль и IV случайные для каждого файла, поэтому два экспорта одного и того же
 * набора ключей выглядят по-разному.
 *
 * Формат файла — JSON-конверт:
 * ```
 * {"format":"jb-osint/keys/1.0","created":1763000000000,
 *  "kdf":{"algo":"PBKDF2WithHmacSHA256","iterations":210000,"salt":"…"},
 *  "cipher":{"algo":"AES-256-GCM","iv":"…"},
 *  "data":"<base64 шифртекста>"}
 * ```
 * Разбор и криптография не зависят от Android — покрыты unit-тестами.
 */
object VaultCrypto {

    const val FORMAT_KEYS = "jb-osint/keys/1.0"
    const val FORMAT_CONFIG = "jb-osint/config/1.0"
    const val DEFAULT_ITERATIONS = 210_000
    const val MIN_PASSWORD = 8

    private const val SALT_BYTES = 16
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES-256-GCM"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    class VaultException(message: String) : Exception(message)

    data class Opened(val format: String, val created: Long, val payload: ByteArray)

    fun encrypt(
        plain: ByteArray,
        password: CharArray,
        format: String,
        iterations: Int = DEFAULT_ITERATIONS,
        created: Long = System.currentTimeMillis()
    ): ByteArray {
        if (password.size < MIN_PASSWORD) throw VaultException("Пароль короче $MIN_PASSWORD символов")
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = derive(password, salt, iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val body = cipher.doFinal(plain)
        val envelope = JSONObject()
            .put("format", format)
            .put("created", created)
            .put("kdf", JSONObject()
                .put("algo", KDF)
                .put("iterations", iterations)
                .put("salt", encoder.encodeToString(salt)))
            .put("cipher", JSONObject()
                .put("algo", CIPHER)
                .put("iv", encoder.encodeToString(cipher.iv)))
            .put("data", encoder.encodeToString(body))
        return envelope.toString().toByteArray(Charsets.UTF_8)
    }

    fun decrypt(envelope: ByteArray, password: CharArray): Opened {
        val root = runCatching { JSONObject(String(envelope, Charsets.UTF_8)) }.getOrNull()
            ?: throw VaultException("Файл повреждён: это не контейнер JB OSINT")
        val format = root.optString("format", "")
        if (!format.startsWith("jb-osint/")) throw VaultException("Неизвестный формат файла")
        val kdf = root.optJSONObject("kdf") ?: throw VaultException("Файл повреждён: нет параметров KDF")
        val cipherNode = root.optJSONObject("cipher") ?: throw VaultException("Файл повреждён: нет параметров шифра")
        if (kdf.optString("algo") != KDF) throw VaultException("Неподдерживаемый алгоритм вывода ключа")
        val iterations = kdf.optInt("iterations", 0)
        if (iterations !in 10_000..2_000_000) throw VaultException("Некорректное число итераций")
        val salt = decode(kdf.optString("salt"))
        val iv = decode(cipherNode.optString("iv"))
        val data = decode(root.optString("data"))
        if (salt.size < 8 || iv.size < 12 || data.isEmpty()) throw VaultException("Файл повреждён или обрезан")
        val key = derive(password, salt, iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val plain = try {
            cipher.doFinal(data)
        } catch (t: Throwable) {
            throw VaultException("Неверный пароль или файл изменён")
        }
        return Opened(format, root.optLong("created", 0L), plain)
    }

    /** Похож ли файл на контейнер JB OSINT (без пароля и без расшифровки). */
    fun peekFormat(bytes: ByteArray): String? {
        val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        return root.optString("format", "").takeIf { it.startsWith("jb-osint/") }
    }

    fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun decode(value: String): ByteArray =
        runCatching { decoder.decode(value) }.getOrElse { throw VaultException("Повреждённая кодировка файла") }
}
