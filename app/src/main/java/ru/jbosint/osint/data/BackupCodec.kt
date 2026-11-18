package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Содержимое резервной копии: ключи встроенных модулей, ключи ассистента,
 * свои модули вместе с их ключами и минимальные настройки.
 *
 * Сам по себе этот объект не шифрует: [BackupCodec] лишь собирает/разбирает JSON,
 * а шифрует его [VaultCrypto] — поэтому формат проверяется unit-тестами без Android.
 */
data class BackupKey(
    val key: String,
    val second: String = "",
    val enabled: Boolean = false
)

data class BackupCustom(
    val id: String,
    /** JSON самого модуля (адрес, авторизация, правила) — как он лежит в хранилище. */
    val json: String,
    val key: String
)

data class BackupPayload(
    val providers: Map<String, BackupKey> = emptyMap(),
    val assistant: Map<String, String> = emptyMap(),
    val assistantProvider: String = "",
    val assistantModel: String = "",
    val custom: List<BackupCustom> = emptyList(),
    val analyst: String = "",
    val demoMode: Boolean = false,
    /** Режим, который нужно включить после восстановления: admin или user. */
    val mode: String = MODE_ADMIN
) {
    val keyCount: Int get() = providers.values.count { it.key.isNotBlank() } + assistant.size +
        custom.count { it.key.isNotBlank() }

    val isEmpty: Boolean get() = providers.values.none { it.key.isNotBlank() } &&
        assistant.isEmpty() && custom.isEmpty()

    companion object {
        const val MODE_ADMIN = "admin"
        const val MODE_USER = "user"
    }
}

object BackupCodec {

    const val SCHEMA = "jb-osint/backup/1"

    fun encode(payload: BackupPayload): String {
        val providers = JSONObject()
        payload.providers.forEach { (id, value) ->
            providers.put(id, JSONObject()
                .put("key", value.key)
                .put("second", value.second)
                .put("enabled", value.enabled))
        }
        val assistant = JSONObject()
        payload.assistant.forEach { (id, value) -> assistant.put(id, value) }
        val custom = JSONArray()
        payload.custom.forEach { module ->
            custom.put(JSONObject()
                .put("id", module.id)
                .put("module", module.json)
                .put("key", module.key))
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("created", System.currentTimeMillis())
            .put("mode", payload.mode)
            .put("providers", providers)
            .put("assistant", assistant)
            .put("assistant_provider", payload.assistantProvider)
            .put("assistant_model", payload.assistantModel)
            .put("custom", custom)
            .put("settings", JSONObject()
                .put("analyst", payload.analyst)
                .put("demo", payload.demoMode))
            .toString(2)
    }

    fun decode(text: String): BackupPayload? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optString("schema") != SCHEMA) return null
        val providers = LinkedHashMap<String, BackupKey>()
        root.optJSONObject("providers")?.let { node ->
            node.keys().forEach { id ->
                val item = node.optJSONObject(id) ?: return@forEach
                val key = item.optString("key")
                val second = item.optString("second")
                if (key.isBlank() && second.isBlank()) return@forEach
                providers[id] = BackupKey(key, second, item.optBoolean("enabled", false))
            }
        }
        val assistant = LinkedHashMap<String, String>()
        root.optJSONObject("assistant")?.let { node ->
            node.keys().forEach { id ->
                val value = node.optString(id)
                if (value.isNotBlank()) assistant[id] = value
            }
        }
        val custom = ArrayList<BackupCustom>()
        root.optJSONArray("custom")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val json = item.optString("module")
                if (json.isBlank()) continue
                custom += BackupCustom(
                    id = item.optString("id"),
                    json = json,
                    key = item.optString("key")
                )
            }
        }
        val settings = root.optJSONObject("settings")
        return BackupPayload(
            providers = providers,
            assistant = assistant,
            assistantProvider = root.optString("assistant_provider"),
            assistantModel = root.optString("assistant_model"),
            custom = custom,
            analyst = settings?.optString("analyst").orEmpty(),
            demoMode = settings?.optBoolean("demo", false) ?: false,
            mode = root.optString("mode", BackupPayload.MODE_ADMIN)
                .takeIf { it == BackupPayload.MODE_USER } ?: BackupPayload.MODE_ADMIN
        )
    }
}
