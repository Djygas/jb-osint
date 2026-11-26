package ru.jbosint.lab

import android.content.Context
import ru.jbosint.osint.data.SecureStore
import java.security.SecureRandom

/**
 * Внутренний токен встроенного сервера. Генерируется автоматически при первом
 * запуске и хранится в SecureStore; пользователь ничего не вводит.
 */
object LabToken {

    const val KEY = "lab_server_token"

    fun read(context: Context): String =
        SecureStore(context.applicationContext).get(KEY).orEmpty()

    fun ensure(context: Context): String {
        val store = SecureStore(context.applicationContext)
        val existing = store.get(KEY).orEmpty()
        if (existing.isNotBlank()) return existing
        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = random.joinToString("") { "%02x".format(it) }
        store.put(KEY, token)
        return token
    }
}
