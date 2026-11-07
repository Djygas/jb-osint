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
    val requiresSecondField: String? = null,
    /** Публичный API: ключ не требуется, модуль включается тумблером. */
    val keyless: Boolean = false
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
            "dyxless", "Dyxless", "Записи по email, телефону, IP и ФИО",
            "Ключ проекта (Bearer-токен)", "https://api.dyxless.im/docs/", ProviderGroup.LEAKS,
            requiresSecondField = "Максимум ₽ на запрос"
        ),
        ProviderSpec(
            "opencorporates", "OpenCorporates", "Юридические лица",
            "api_token", "https://opencorporates.com/api_accounts/new", ProviderGroup.REGISTRY
        ),
        ProviderSpec(
            "yandex", "Яндекс Поиск", "Индекс и цитирование",
            "apikey (Yandex XML)", "https://yandex.ru/dev/xml/doc/ru/concepts/registration", ProviderGroup.SEARCH,
            requiresSecondField = "folderid"
        ),
        ProviderSpec(
            "brave", "Brave Search", "Независимый веб-индекс",
            "X-Subscription-Token", "https://api.search.brave.com/app/keys", ProviderGroup.SEARCH
        ),
        ProviderSpec(
            "serpapi", "SerpAPI", "Google, Bing, Яндекс через один шлюз",
            "api_key", "https://serpapi.com/manage-api-key", ProviderGroup.SEARCH
        ),
        ProviderSpec(
            "ddg", "DuckDuckGo", "Мгновенные ответы и связанные темы",
            "Ключ не требуется", "https://duckduckgo.com/api", ProviderGroup.SEARCH, keyless = true
        ),
        ProviderSpec(
            "wikipedia", "Wikipedia", "Поиск по статьям и упоминаниям",
            "Ключ не требуется", "https://www.mediawiki.org/wiki/API:Search", ProviderGroup.SEARCH, keyless = true
        ),
        ProviderSpec(
            "urlscan", "urlscan.io", "Сканы сайтов и связанные домены",
            "Ключ не требуется", "https://urlscan.io/docs/api/", ProviderGroup.INFRA, keyless = true
        ),
        ProviderSpec(
            "securitytrails", "SecurityTrails", "Поддомены и DNS-история",
            "APIKEY", "https://securitytrails.com/app/account/credentials", ProviderGroup.INFRA
        ),
        ProviderSpec(
            "abuseipdb", "AbuseIPDB", "Репутация IP-адресов",
            "Key", "https://www.abuseipdb.com/api", ProviderGroup.LEAKS
        ),
        ProviderSpec(
            "gitlab", "GitLab", "Профили и публичные проекты",
            "Ключ не требуется", "https://docs.gitlab.com/ee/api/users.html", ProviderGroup.SOCIAL, keyless = true
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

    fun isReady(spec: ProviderSpec): Boolean = spec.keyless || key(spec.id).isNotBlank()

    fun enabledWithKeys(): Map<String, String> =
        ProviderCatalog.all
            .filter { isEnabled(it.id) && isReady(it) }
            .associate { it.id to key(it.id) }

    fun activeCount(): Int = ProviderCatalog.all.count { isEnabled(it.id) && isReady(it) }

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
        val spec = repo.spec(id) ?: return@withContext KeyCheck(KeyState.INVALID, "Неизвестный провайдер")
        if (key.isBlank() && !spec.keyless) return@withContext KeyCheck(KeyState.EMPTY, "Ключ не задан")
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
                { probeBodyAware("https://api.tgstat.ru/channels/search?token=${Net.urlEncode(key)}&q=osint") }
            }
            "vk" -> { { probeVk(key) } }
            "dyxless" -> { { probeDyxless(key) } }
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
            "brave" -> {
                {
                    probeHttp(
                        "https://api.search.brave.com/res/v1/web/search?q=test&count=1",
                        mapOf("X-Subscription-Token" to key),
                        treat422AsValid = false
                    )
                }
            }
            "serpapi" -> {
                { probeHttp("https://serpapi.com/account.json?api_key=${Net.urlEncode(key)}") }
            }
            "securitytrails" -> {
                {
                    probeHttp(
                        "https://api.securitytrails.com/v1/ping",
                        mapOf("APIKEY" to key, "Accept" to "application/json")
                    )
                }
            }
            "abuseipdb" -> {
                {
                    probeHttp(
                        "https://api.abuseipdb.com/api/v2/check?ipAddress=8.8.8.8&maxAgeInDays=1",
                        mapOf("Key" to key, "Accept" to "application/json")
                    )
                }
            }
            "ddg" -> { { probeHttp("https://api.duckduckgo.com/?q=osint&format=json&no_html=1") } }
            "wikipedia" -> {
                {
                    probeHttp(
                        "https://ru.wikipedia.org/w/api.php?action=query&list=search&srsearch=osint&format=json&srlimit=1"
                    )
                }
            }
            "urlscan" -> { { probeHttp("https://urlscan.io/api/v1/search/?q=example.com&size=1") } }
            "gitlab" -> { { probeHttp("https://gitlab.com/api/v4/users?username=root") } }
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

    /**
     * Часть сервисов отвечает 200 и кладёт ошибку авторизации в тело —
     * такие ответы нельзя считать успешными.
     */
    private suspend fun probeBodyAware(url: String): KeyCheck {
        val response = Net.fetch(url, attempts = 1)
        val redirect = response.crossHostRedirect
        if (redirect != null) {
            return KeyCheck(KeyState.INVALID, "Сервис перенаправляет на $redirect — ключ не отправлен")
        }
        if (response.code in 200..299) {
            val errorInBody = runCatching { org.json.JSONObject(response.body) }.getOrNull()?.let { json ->
                json.has("error") || json.optString("status") == "error"
            } ?: false
            if (errorInBody) return KeyCheck(KeyState.INVALID, "Сервис отклонил ключ (ошибка в ответе)")
            return KeyCheck(KeyState.VALID, "Ключ принят сервисом")
        }
        return probeHttp(url)
    }

    /**
     * VK отвечает 200 и кладёт ошибку в `error`, поэтому коды надо разбирать
     * по смыслу: неверный токен (5/15/18/27) — это «ключ неверный»,
     * лимиты и недоступный для типа токена метод — не повод объявлять ключ битым.
     */
    private suspend fun probeVk(key: String): KeyCheck {
        val url = "https://api.vk.com/method/users.get?user_ids=1&fields=city" +
            "&access_token=${Net.urlEncode(key)}&v=5.199"
        val response = Net.fetch(url, attempts = 1)
        response.crossHostRedirect?.let {
            return KeyCheck(KeyState.INVALID, "Сервис перенаправляет на $it — ключ не отправлен")
        }
        val json = runCatching { org.json.JSONObject(response.body) }.getOrNull()
            ?: return when (response.code) {
                401, 403 -> KeyCheck(KeyState.INVALID, "Сервис отклонил ключ (${response.code})")
                429 -> KeyCheck(KeyState.VALID, "Ключ активен, превышен лимит запросов")
                else -> KeyCheck(KeyState.INVALID, "Ответ сервиса ${response.code}")
            }
        val error = json.optJSONObject("error")
        if (error == null) {
            return if (json.has("response")) KeyCheck(KeyState.VALID, "Ключ принят сервисом")
            else probeHttp(url)
        }
        val code = error.optInt("error_code", 0)
        return when (code) {
            5 -> KeyCheck(KeyState.INVALID, "VK отклонил ключ (код 5): токен недействителен или не тот тип токена")
            15, 18, 27 -> KeyCheck(KeyState.INVALID, "VK отклонил ключ (код $code): нет доступа к методу")
            29 -> KeyCheck(KeyState.VALID, "Ключ активен, превышен лимит метода VK (код 29)")
            6, 9, 10 -> KeyCheck(KeyState.VALID, "Ключ активен, превышен лимит запросов (код $code)")
            100, 113 -> KeyCheck(KeyState.VALID, "Ключ принят; служебный запрос не поддержан (код $code)")
            else -> KeyCheck(KeyState.VALID, "Ключ принят; VK вернул код $code на служебный запрос")
        }
    }

    /**
     * Dyxless — платный API записей: служебная ручка `/v3/capabilities` отвечает
     * 200 только с валидным Bearer-токеном. Второе поле — лимит расхода в рублях.
     */
    private suspend fun probeDyxless(key: String): KeyCheck {
        val cap = repo.secondField("dyxless").replace(',', '.').trim().toDoubleOrNull()
        if (cap == null || cap <= 0) {
            return KeyCheck(KeyState.INVALID, "Укажите лимит расхода в рублях, например 10")
        }
        val response = Net.fetch(
            "https://api.dyxless.im/v3/capabilities",
            mapOf("Authorization" to "Bearer $key"),
            attempts = 1
        )
        response.crossHostRedirect?.let {
            return KeyCheck(KeyState.INVALID, "Сервис перенаправляет на $it — ключ не отправлен")
        }
        return when {
            response.code in 200..299 -> KeyCheck(KeyState.VALID, "Ключ принят сервисом")
            response.code == 401 || response.code == 403 ->
                KeyCheck(KeyState.INVALID, "Сервис отклонил ключ (${response.code})")
            response.code == 429 -> KeyCheck(KeyState.VALID, "Ключ активен, превышен лимит запросов")
            else -> KeyCheck(KeyState.INVALID, "Ответ сервиса ${response.code}")
        }
    }

    private suspend fun probeHttp(
        url: String,
        headers: Map<String, String> = emptyMap(),
        treat422AsValid: Boolean = true
    ): KeyCheck {
        val response = Net.fetch(url, headers)
        val redirect = response.crossHostRedirect
        if (redirect != null) {
            return KeyCheck(KeyState.INVALID, "Сервис перенаправляет на $redirect — ключ не отправлен")
        }
        return when {
            response.code in 200..299 -> KeyCheck(KeyState.VALID, "Ключ принят сервисом")
            response.code == 401 || response.code == 403 -> KeyCheck(KeyState.INVALID, "Сервис отклонил ключ (${response.code})")
            response.code == 422 && !treat422AsValid -> KeyCheck(KeyState.INVALID, "Сервис отклонил ключ (422)")
            response.code == 400 || response.code == 404 || response.code == 422 ->
                // Сервис ответил осмысленно — значит авторизация пройдена.
                KeyCheck(KeyState.VALID, "Сервис ответил, ключ принят (${response.code})")
            response.code == 429 -> KeyCheck(KeyState.VALID, "Ключ активен, превышен лимит запросов")
            else -> KeyCheck(KeyState.INVALID, "Ответ сервиса ${response.code}")
        }
    }
}
