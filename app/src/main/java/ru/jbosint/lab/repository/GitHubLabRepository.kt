package ru.jbosint.lab.repository

import android.content.Context
import ru.jbosint.lab.model.LabApiConfig
import ru.jbosint.lab.model.LabProject
import ru.jbosint.lab.model.LabRunResult
import ru.jbosint.lab.network.TermuxApiClient
import ru.jbosint.osint.data.SecureStore

/**
 * Хранилище настроек лаборатории и фасад над локальным API Termux.
 *
 * Токен лежит в зашифрованном хранилище (AES/GCM + Android Keystore), адрес —
 * в обычных prefs. К серверу обращаемся только по loopback.
 */
class GitHubLabRepository(context: Context) {

    private val appContext = context.applicationContext
    private val secure = SecureStore(appContext)
    private val prefs = appContext.getSharedPreferences("jb_lab_api", Context.MODE_PRIVATE)
    private val api = TermuxApiClient()

    fun config(): LabApiConfig {
        val base = prefs.getString(KEY_BASE_URL, null).orEmpty()
            .ifBlank { LabApiConfig.DEFAULT_BASE_URL }
        return LabApiConfig(baseUrl = base, token = secure.get(KEY_TOKEN).orEmpty())
    }

    fun saveBaseUrl(value: String) {
        prefs.edit().putString(KEY_BASE_URL, value.trim().take(200)).apply()
    }

    fun saveToken(value: String) {
        secure.put(KEY_TOKEN, value.trim())
    }

    fun hasToken(): Boolean = secure.get(KEY_TOKEN).orEmpty().isNotBlank()

    suspend fun health(): Boolean = api.health(config())

    suspend fun clone(url: String, ref: String): LabProject = api.clone(config(), url, ref)

    suspend fun scan(dir: String): LabProject = api.scan(config(), dir)

    suspend fun run(
        dir: String,
        command: String,
        timeoutSec: Int = LabApiConfig.DEFAULT_TIMEOUT_SEC
    ): LabRunResult = api.run(config(), dir, command, timeoutSec)

    suspend fun clear(dir: String) = api.clear(config(), dir)

    suspend fun export(dir: String): String = api.export(config(), dir)

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "lab_server_token"
    }
}
