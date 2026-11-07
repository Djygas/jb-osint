package ru.jbosint.osint.data

/**
 * План починки модуля по итогам диагностики. Решение принимается по коду
 * ответа и адресу — без сети, поэтому его можно проверить unit-тестами.
 */
sealed class RepairStep {

    /** Свой модуль дублирует встроенный: перенести ключ и включить встроенный. */
    data class Migrate(val providerId: String, val reason: String) : RepairStep()

    /** Адрес/способ передачи ключа неверны: заново подобрать запрос. */
    data class Rediscover(val reason: String) : RepairStep()

    /** Ключ мог истечь или быть скопирован не полностью: проверить заново. */
    data class Recheck(val reason: String) : RepairStep()

    /** Автоматически не исправить: нужны данные от пользователя. */
    data class Manual(val reason: String) : RepairStep()
}

object RepairPlan {

    /**
     * Свой модуль. 404/405 обычно значит неверный путь или метод, 401/403 —
     * неверный способ передачи ключа; и то и другое лечится повторным
     * автоподбором. Если адрес принадлежит встроенному сервису, лучше
     * перейти на встроенный модуль: он проверен и не требует правки.
     */
    fun forCustom(module: CustomModule, code: Int): RepairStep {
        val host = CustomModuleSpec.hostOf(module.urlTemplate)
        val builtIn = ProviderGuess.byHost("https://$host")
        if (builtIn != null && builtIn != "ddg" && builtIn != "wikipedia") {
            val title = ProviderCatalog.byId(builtIn)?.title ?: builtIn
            return RepairStep.Migrate(
                builtIn,
                "адрес $host обслуживается встроенным модулем «$title» — перенесу ключ туда"
            )
        }
        return when (code) {
            0, 401, 403, 404, 405, 415, 422 ->
                RepairStep.Rediscover("эндпоинт ответил ${if (code == 0) "ошибкой сети" else "кодом $code"} — подберу адрес и способ передачи ключа заново")
            429 -> RepairStep.Manual("сервис ограничил частоту запросов — повторите позже")
            else -> RepairStep.Manual("ответ $code: нужна настройка правил разбора или другой адрес API")
        }
    }

    /**
     * Встроенный модуль. Ключ не принят — его нужно проверить заново
     * (часто он скопирован с обрезкой или истёк). Для VK отдельная причина:
     * users.search не работает с сервисным токеном приложения.
     */
    fun forBuiltIn(id: String, state: KeyState, facts: Int): RepairStep = when {
        id == "vk" && state != KeyState.VALID ->
            RepairStep.Manual("VK принимает только пользовательский токен (не сервисный): получите его через Implicit Flow на dev.vk.com и вставьте целиком")
        state == KeyState.VALID && facts == 0 ->
            RepairStep.Manual("ключ рабочий, но по тестовому запросу ничего не найдено — это не поломка")
        state == KeyState.VALID ->
            RepairStep.Manual("модуль отвечает — повторная проверка не нужна")
        else ->
            RepairStep.Recheck("ключ не принят (${state.title}) — проверю заново и уточню причину")
    }
}
