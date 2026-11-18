package ru.jbosint.osint.data

import android.content.Context
import android.os.Environment
import java.io.File

/** Готовый файл резервной копии: содержимое и имя для сохранения/отправки. */
data class BackupFile(val bytes: ByteArray, val name: String, val savedCopy: String? = null) {
    override fun equals(other: Any?): Boolean =
        other is BackupFile && name == other.name && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}

/** Итог восстановления: что именно вернулось в хранилище. */
data class RestoreResult(
    val payload: BackupPayload,
    val providers: Int,
    val assistant: Int,
    val custom: Int,
    val userBuild: Boolean
) {
    val totalKeys: Int get() = providers + assistant + custom
}

/**
 * Экспорт и импорт ключей, а также «пользовательская сборка» — конфигурация
 * активных модулей вместе с их ключами.
 *
 * Файл всегда шифруется паролем пользователя ([VaultCrypto]); в открытом виде
 * ключи не покидают устройство. Копия файла дополнительно кладётся во внутреннюю
 * папку приложения: она переживает обновление версии, поэтому после переустановки
 * поверх старой сборки приложение само предложит восстановление.
 */
class KeyBackupService(
    private val context: Context,
    private val keys: KeyRepository,
    private val custom: CustomModuleStore,
    private val assistant: AssistantStore,
    private val settings: SettingsStore
) {

    /** Что попадёт в файл. Для пользовательской сборки — только включённые модули. */
    fun payloadForBackup(onlyEnabled: Boolean, mode: String): BackupPayload {
        val providers = LinkedHashMap<String, BackupKey>()
        ProviderCatalog.all.forEach { spec ->
            val key = keys.key(spec.id)
            val second = keys.secondField(spec.id)
            if (key.isBlank() && second.isBlank()) return@forEach
            if (onlyEnabled && !keys.isEnabled(spec.id)) return@forEach
            providers[spec.id] = BackupKey(key, second, keys.isEnabled(spec.id))
        }
        val assistantKeys = LinkedHashMap<String, String>()
        AssistantCatalog.providers.forEach { provider ->
            val value = assistant.key(provider.id)
            if (value.isNotBlank()) assistantKeys[provider.id] = value
        }
        val modules = ArrayList<BackupCustom>()
        custom.list().forEach { module ->
            if (onlyEnabled && !module.enabled) return@forEach
            modules += BackupCustom(
                id = module.id,
                json = custom.encodeForBackup(module),
                key = custom.key(module.id)
            )
        }
        return BackupPayload(
            providers = providers,
            assistant = assistantKeys,
            assistantProvider = assistant.provider,
            assistantModel = assistant.model,
            custom = modules,
            analyst = settings.analyst,
            demoMode = settings.demoMode,
            mode = mode
        )
    }

    /** Резервная копия всех ключей (файл jb_osint_keys.json). */
    fun exportKeys(password: CharArray): BackupFile = export(password, BackupPayload.MODE_ADMIN, KEYS_FILE)

    /** Пользовательская сборка: только включённые модули, после импорта — режим «Пользователь». */
    fun createUserBuild(password: CharArray): BackupFile = export(password, BackupPayload.MODE_USER, CONFIG_FILE)

    private fun export(password: CharArray, mode: String, name: String): BackupFile {
        val payload = payloadForBackup(onlyEnabled = mode == BackupPayload.MODE_USER, mode = mode)
        val plain = BackupCodec.encode(payload).toByteArray(Charsets.UTF_8)
        val format = if (name == CONFIG_FILE) VaultCrypto.FORMAT_CONFIG else VaultCrypto.FORMAT_KEYS
        val bytes = VaultCrypto.encrypt(plain, password, format)
        val saved = runCatching {
            val dir = File(context.filesDir, PRIVATE_DIR).apply { mkdirs() }
            val file = File(dir, name)
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
        return BackupFile(bytes, name, saved)
    }

    /** Расшифровать и применить файл. Возвращает отчёт или бросает [VaultCrypto.VaultException]. */
    fun restore(bytes: ByteArray, password: CharArray): RestoreResult {
        val opened = VaultCrypto.decrypt(bytes, password)
        val payload = BackupCodec.decode(String(opened.payload, Charsets.UTF_8))
            ?: throw VaultCrypto.VaultException("Файл не похож на резервную копию JB OSINT")
        var providers = 0
        payload.providers.forEach { (id, value) ->
            if (value.key.isNotBlank()) {
                keys.saveKey(id, value.key)
                providers++
            }
            if (value.second.isNotBlank()) keys.saveSecondField(id, value.second)
            keys.setEnabled(id, value.enabled)
        }
        var assistantCount = 0
        payload.assistant.forEach { (id, value) ->
            if (AssistantCatalog.providers.none { it.id == id }) return@forEach
            assistant.saveKey(value, id)
            assistantCount++
        }
        if (payload.assistantProvider.isNotBlank() &&
            AssistantCatalog.providers.any { it.id == payload.assistantProvider }
        ) {
            assistant.provider = payload.assistantProvider
        }
        if (payload.assistantModel.isNotBlank()) assistant.model = payload.assistantModel
        var customCount = 0
        payload.custom.forEach { entry ->
            val module = custom.decodeFromBackup(entry.json) ?: return@forEach
            custom.save(module, entry.key.takeIf { it.isNotBlank() })
            customCount++
        }
        if (payload.analyst.isNotBlank()) settings.analyst = payload.analyst
        settings.demoMode = payload.demoMode
        return RestoreResult(
            payload = payload,
            providers = providers,
            assistant = assistantCount,
            custom = customCount,
            userBuild = payload.mode == BackupPayload.MODE_USER ||
                opened.format == VaultCrypto.FORMAT_CONFIG
        )
    }

    /** Копии, которые приложение может проверить само: внутренняя папка и внешняя папка приложения. */
    fun findBackups(): List<File> {
        val dirs = ArrayList<File>()
        dirs += File(context.filesDir, PRIVATE_DIR)
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { dirs += it }
        context.getExternalFilesDir(null)?.let { dirs += it }
        val names = setOf(KEYS_FILE, CONFIG_FILE)
        val found = ArrayList<File>()
        dirs.distinct().forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name in names && file.length() in 16..4_000_000) found += file
            }
        }
        return found.sortedByDescending { it.lastModified() }
    }

    /** Самая свежая найденная копия. */
    fun latestBackup(): File? = findBackups().firstOrNull()

    fun clearPrivateCopies() {
        runCatching { File(context.filesDir, PRIVATE_DIR).listFiles()?.forEach { it.delete() } }
    }

    companion object {
        const val KEYS_FILE = "jb_osint_keys.json"
        const val CONFIG_FILE = "jb_osint_config.bin"
        const val PRIVATE_DIR = "backups"
        const val MIME = "application/octet-stream"
    }
}
