package ru.jbosint.osint.agent

import ru.jbosint.osint.data.KeyRepository

/**
 * Планировщик каскада.
 *
 * Определяет тип запроса и раскладывает модули по трём ступеням стоимости:
 * бесплатные (без ключей) → по ключу → платные. Платных шагов в одном прогоне
 * не больше [MAX_PAID_STEPS], остальные помечаются как пропущенные — это и есть
 * минимизация токенов: сначала бесплатные данные, платные только если нужно.
 */
object AgentPlanner {

    /** Сколько платных ступеней разрешено за один прогон. */
    const val MAX_PAID_STEPS = 3

    private val vin = Regex("^[A-HJ-NPR-Z0-9]{17}$", RegexOption.IGNORE_CASE)
    private val plate = Regex("^[АВЕКМНОРСТУХA-Z]{1}\\d{3}[АВЕКМНОРСТУХA-Z]{2}\\d{2,3}$", RegexOption.IGNORE_CASE)
    private val email = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
    private val phone = Regex("^\\+?[\\d][\\d\\s()\\-]{8,18}$")
    private val domain = Regex("^(([A-Za-z0-9]([A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?)\\.)+[A-Za-z]{2,}$")
    private val ip = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
    private val username = Regex("^@?[A-Za-z0-9_.\\-]{2,32}$")

    /** Тип запроса: VIN и госномер проверяются раньше остальных шаблонов. */
    fun detect(raw: String): AgentTarget {
        val query = raw.trim()
        return when {
            vin.matches(query) -> AgentTarget.VIN
            plate.matches(query) -> AgentTarget.VIN
            email.matches(query) -> AgentTarget.EMAIL
            ip.matches(query) -> AgentTarget.IP
            phone.matches(query) && query.count { it.isDigit() } >= 9 -> AgentTarget.PHONE
            domain.matches(query) -> AgentTarget.DOMAIN
            username.matches(query) && !query.contains(' ') -> AgentTarget.USERNAME
            query.count { it == ' ' } >= 1 -> AgentTarget.PERSON
            else -> AgentTarget.OTHER
        }
    }

    /** Бесплатные модули: работают без ключей, всегда доступны. */
    private val freeModules = listOf("ddg", "wikipedia", "github", "gitlab", "reddit")

    private val infraFree = listOf("urlscan", "ipinfo")

    /** Модули по ключу, но без отдельной оплаты за запрос. */
    private val keyedModules = listOf(
        "tgstat", "vk", "numverify", "hunter", "opencorporates",
        "securitytrails", "abuseipdb", "yandex", "brave", "google"
    )

    /** Платные запросы: каждый ответ стоит денег владельца ключа. */
    private val paidModules = listOf("dyxless", "leakcheck", "hibp", "virustotal", "shodan", "serpapi", "telesint", "tronk")

    fun plan(query: String, keys: KeyRepository): List<AgentStep> = plan(query) { id ->
        val spec = keys.spec(id)
        spec?.keyless == true || keys.key(id).isNotBlank()
    }

    /** Тот же план, но доступность ключей задаётся предикатом — так проще тестировать. */
    fun plan(query: String, hasKey: (String) -> Boolean): List<AgentStep> {
        val target = detect(query)
        val steps = ArrayList<AgentStep>()

        // 1. Бесплатные веб-источники — во всех случаях.
        val free = freeModules.filter(hasKey)
        steps += AgentStep(
            id = "free_web",
            title = "Открытые веб-источники",
            tier = CostTier.FREE,
            moduleIds = free,
            note = "DuckDuckGo, Wikipedia, GitHub, GitLab — без ключей и без оплаты"
        )

        // 2. Инфраструктура — только когда запрос про домен/IP.
        if (target == AgentTarget.DOMAIN || target == AgentTarget.IP) {
            steps += AgentStep(
                id = "infra",
                title = "Инфраструктура",
                tier = CostTier.FREE,
                moduleIds = infraFree.filter(hasKey),
                note = "RDAP, urlscan, IPinfo"
            )
        }

        // 3. Модули по ключу: соцсети, поиск, реестры.
        val keyed = keyedModules.filter { hasKey(it) }
        if (keyed.isNotEmpty()) {
            steps += AgentStep(
                id = "keyed",
                title = "Источники по ключу",
                tier = CostTier.KEYED,
                moduleIds = keyed,
                note = "Работают по вашим ключам, без отдельной оплаты за запрос"
            )
        }

        // 4. Проверка авто — отдельная ветка каскада.
        if (target == AgentTarget.VIN) {
            steps += AgentStep(
                id = "auto",
                title = "Проверка авто",
                tier = if (hasKey("tronk")) CostTier.PAID else CostTier.FREE,
                moduleIds = listOf("tronk"),
                note = if (hasKey("tronk")) "TRONK: ДТП, залоги, пробег, владельцы"
                else "Без ключа — только открытые источники и ручные ссылки на агрегаторы"
            )
        }

        // 5. Платные запросы: только если есть ключи, не больше лимита.
        val paid = paidModules.filter { hasKey(it) && (target != AgentTarget.VIN || it != "tronk") }
        if (paid.isNotEmpty()) {
            paid.forEachIndexed { index, id ->
                steps += AgentStep(
                    id = "paid_$id",
                    title = paidTitle(id),
                    tier = CostTier.PAID,
                    moduleIds = listOf(id),
                    state = if (index < MAX_PAID_STEPS) StepState.PENDING else StepState.SKIPPED,
                    note = if (index < MAX_PAID_STEPS) "Платный запрос — запуск после бесплатных шагов"
                    else "Пропущено: лимит платных шагов на прогон"
                )
            }
        }

        return steps
    }

    private fun paidTitle(id: String): String = when (id) {
        "dyxless" -> "Dyxless: записи и Telegram"
        "leakcheck" -> "LeakCheck: утечки"
        "hibp" -> "Have I Been Pwned"
        "virustotal" -> "VirusTotal"
        "shodan" -> "Shodan"
        "serpapi" -> "SerpAPI: расширенный веб-поиск"
        "telesint" -> "TeleSINT: профили Telegram"
        "tronk" -> "TRONK: проверка авто"
        else -> "Модуль $id"
    }

    /** Сколько платных шагов реально пойдёт в работу. */
    fun paidSteps(plan: List<AgentStep>): List<AgentStep> =
        plan.filter { it.tier == CostTier.PAID && it.state != StepState.SKIPPED }

    /** Режим «только открытые данные»: нет ни одного ключа. */
    fun freeOnly(keys: KeyRepository): Boolean =
        AgentPlanner.plan("test", keys).none { it.tier == CostTier.PAID || it.tier == CostTier.KEYED }
}
