package ru.jbosint.osint.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

enum class ProviderGroup(val title: String) {
    SEARCH("Поисковые системы"),
    CONTACTS("Почта и телефоны"),
    INFRA("Инфраструктура"),
    LEAKS("Утечки и репутация"),
    SOCIAL("Соцсети и мессенджеры"),
    REGISTRY("Реестры")
}

/** Описание поля API-ключа. Значения ключей никогда не попадают в открытый вид. */
data class ProviderSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val hint: String,
    val docsUrl: String,
    val group: ProviderGroup,
    val requiresSecondField: String? = null
)

object ProviderCatalog {
    val all: List<ProviderSpec> = listOf(
        ProviderSpec(
            "github", "GitHub", "Профили и публичная активность",
            "Personal access token (ghp_…)", "https://github.com/settings/tokens", ProviderGroup.SOCIAL
        ),
        ProviderSpec(
            "hibp", "Have I Been Pwned", "Участие адресов в утечках",
            "hibp-api-key", "https://haveibeenpwned.com/API/Key", ProviderGroup.LEAKS
        ),
        ProviderSpec(
            "virustotal", "VirusTotal", "Репутация доменов и IP",
            "x-apikey", "https://www.virustotal.com/gui/my-apikey", ProviderGroup.LEAKS
        ),
        ProviderSpec(
            "shodan", "Shodan", "Открытые сервисы и баннеры",
            "API key", "https://account.shodan.io/", ProviderGroup.INFRA
        ),
        ProviderSpec(
            "numverify", "Numverify", "Валидация телефонных номеров",
            "access_key", "https://numverify.com/product", ProviderGroup.CONTACTS
        ),
        ProviderSpec(
            "hunter", "Hunter.io", "Корпоративные адреса почты",
            "api_key", "https://hunter.io/api-keys", ProviderGroup.CONTACTS
        ),
        ProviderSpec(
            "ipinfo", "IPinfo", "ASN, геолокация, провайдер",
            "token", "https://ipinfo.io/account/token", ProviderGroup.INFRA
        ),
        ProviderSpec(
            "tgstat", "TGStat", "Публичные каналы и упоминания",
            "token", "https://api.tgstat.ru/", ProviderGroup.SOCIAL
        ),
        ProviderSpec(
            "vk", "VK API", "Профили и сообщества",
            "access_token", "https://dev.vk.com/", ProviderGroup.SOCIAL
        ),
        ProviderSpec(
            "opencorporates", "OpenCorporates", "Юридические лица",
            "api_token", "https://opencorporates.com/api_accounts/new", ProviderGroup.REGISTRY
        ),
        ProviderSpec(
            "yandex", "Яндекс Поиск", "Индекс и цитирование",
            "apikey (Yandex XML)", "https://yandex.ru/dev/xml/", ProviderGroup.SEARCH,
            requiresSecondField = "folderid"
        ),
        ProviderSpec(
            "google", "Google Custom Search", "Веб-индекс",
            "API key", "https://developers.google.com/custom-search/v1/introduction", ProviderGroup.SEARCH,
            requiresSecondField = "cx"
        )
    )

    fun byId(id: String): ProviderSpec? = all.firstOrNull { it.id == id }

    /** Публичная витрина: в отчёте и интерфейсе источники не раскрываются. */
    const val DISPLAY_NAME = "Поиск JB OSINT"
}

enum class KeyState(val title: String) {
    EMPTY("Не задан"),
    UNCHECKED("Не проверен"),
    CHECKING("Проверка…"),
    VALID("Активен"),
    INVALID("Неверный ключ"),
    OFFLINE("Нет сети")
}

data class KeyCheck(val state: KeyState, val detail: String)

class KeyRepository(context: Context) {

    private val secure = SecureStore(context)
    private val flags = context.getSharedPreferences("jb_keys", Context.MODE_PRIVATE)

    fun spec(id: String): ProviderSpec? = ProviderCatalog.byId(id)

    fun isEnabled(id: String): Boolean = flags.getBoolean("enabled_$id", false)

    fun setEnabled(id: String, enabled: Boolean) {
        flags.edit().putBoolean("enabled_$id", enabled).apply()
    }

    fun key(id: String): String = secure.get(keyName(id)).orEmpty()

    fun saveKey(id: String, value: String) {
        secure.put(keyName(id), value.trim())
        flags.edit()
            .putLong("checked_$id", 0L)
            .remove("state_$id")
            .apply()
    }

    fun secondField(id: String): String = secure.get(secondName(id)).orEmpty()

    fun saveSecondField(id: String, value: String) {
        secure.put(secondName(id), value.trim())
    }

    fun state(id: String): KeyState {
        val raw = flags.getString("state_$id", null) ?: return KeyState.UNCHECKED
        return runCatching { KeyState.valueOf(raw) }.getOrDefault(KeyState.UNCHECKED)
    }

    fun setState(id: String, state: KeyState) {
        flags.edit().putString("state_$id", state.name).putLong("checked_$id", System.currentTimeMillis()).apply()
    }

    fun checkedAt(id: String): Long = flags.getLong("checked_$id", 0L)

    fun enabledWithKeys(): Map<String, String> =
        ProviderCatalog.all
            .filter { isEnabled(it.id) && key(it.id).isNotBlank() }
            .associate { it.id to key(it.id) }

    fun activeCount(): Int = ProviderCatalog.all.count { isEnabled(it.id) && key(it.id).isNotBlank() }

    fun wipeKeys() {
        ProviderCatalog.all.forEach {
            secure.remove(keyName(it.id))
            secure.remove(secondName(it.id))
            flags.edit().remove("state_${it.id}").remove("checked_${it.id}").apply()
        }
    }

    private fun keyName(id: String) = "key_$id"
    private fun secondName(id: String) = "second_$id"
}

/**
 * Проверка ключей: лёгкий запрос к служебной ручке провайдера.
 * Ключ никуда не логируется, ошибка возвращается без тела ответа.
 */
class KeyValidator(private val repo: KeyRepository) {

    suspend fun check(id: String): KeyCheck = withContext(Dispatchers.IO) {
        val key = repo.key(id)
        if (key.isBlank()) return@withContext KeyCheck(KeyState.EMPTY, "Ключ не задан")
        val spec = repo.spec(id) ?: return@withContext KeyCheck(KeyState.INVALID, "Неизвестный провайдер")
        if (spec.requiresSecondField != null && repo.secondField(id).isBlank()) {
            return@withContext KeyCheck(KeyState.INVALID, "Нужно второе поле: ${spec.requiresSecondField}")
        }
        val probe: suspend () -> KeyCheck = when (id) {
            "github" -> {
                { probeHttp("https://api.github.com/user", mapOf("Authorization" to "token $key")) }
            }
            "hibp" -> {
                {
                    probeHttp(
                        "https://haveibeenpwned.com/api/v3/breachedaccount/test%40example.com?truncateResponse=true",
                        mapOf("hibp-api-key" to key)
                    )
                }
            }
            "virustotal" -> {
                { probeHttp("https://www.virustotal.com/api/v3/domains/example.com", mapOf("x-apikey" to key)) }
            }
            "shodan" -> {
                { probeHttp("https://api.shodan.io/api-info?key=${Net.urlEncode(key)}") }
            }
            "numverify" -> {
                {
                    probeHttp(
                        "https://apilayer.net/api/validate?access_key=${Net.urlEncode(key)}&number=14158586273"
                    )
                }
            }
            "hunter" -> {
                { probeHttp("https://api.hunter.io/v2/account?api_key=${Net.urlEncode(key)}") }
            }
            "ipinfo" -> {
                { probeHttp("https://ipinfo.io/8.8.8.8/json?token=${Net.urlEncode(key)}") }
            }
            "tgstat" -> {
                { probeHttp("https://api.tgstat.ru/channels/search?token=${Net.urlEncode(key)}&q=osint") }
            }
            "vk" -> {
                {
                    probeHttp(
                        "https://api.vk.com/method/users.get?access_token=${Net.urlEncode(key)}&v=5.199"
                    )
                }
            }
            "opencorporates" -> {
                {
                    probeHttp(
                        "https://api.opencorporates.com/v0.4/companies/search?q=test&api_token=${Net.urlEncode(key)}"
                    )
                }
            }
            "google" -> {
                {
                    probeHttp(
                        "https://www.googleapis.com/customsearch/v1?key=${Net.urlEncode(key)}&cx=${Net.urlEncode(repo.secondField(id))}&q=test"
                    )
                }
            }
            "yandex" -> {
                {
                    probeHttp(
                        "https://yandex.ru/search/xml?folderid=${Net.urlEncode(repo.secondField(id))}&apikey=${Net.urlEncode(key)}&query=test"
                    )
                }
            }
            else -> { { KeyCheck(KeyState.UNCHECKED, "Проверка не поддерживается") } }
        }
        val result = runCatching { probe() }.getOrElse {
            KeyCheck(KeyState.OFFLINE, "Сервис недоступен: проверьте соединение")
        }
        repo.setState(id, result.state)
        result
    }

    suspend fun checkAll(): Map<String, KeyCheck> = coroutineScope {
        ProviderCatalog.all
            .filter { repo.key(it.id).isNotBlank() }
            .map { spec -> async { spec.id to check(spec.id) } }
            .awaitAll()
            .toMap()
    }

    private suspend fun probeHttp(url: String, headers: Map<String, String> = emptyMap()): KeyCheck {
        val response = Net.fetch(url, headers)
        return when {
            response.code in 200..299 -> KeyCheck(KeyState.VALID, "Ключ принят сервисом")
            response.code == 401 || response.code == 403 -> KeyCheck(KeyState.INVALID, "Сервис отклонил ключ (${response.code})")
            response.code == 400 || response.code == 404 || response.code == 422 ->
                // Сервис ответил осмысленно — значит авторизация пройдена.
                KeyCheck(KeyState.VALID, "Сервис ответил, ключ принят (${response.code})")
            response.code == 429 -> KeyCheck(KeyState.VALID, "Ключ активен, превышен лимит запросов")
            else -> KeyCheck(KeyState.INVALID, "Ответ сервиса ${response.code}")
        }
    }
}
