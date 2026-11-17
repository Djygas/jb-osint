package ru.jbosint.osint.agent

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence

/**
 * Модели автономного AI-агента.
 *
 * Агент получает запрос, сам определяет его тип, строит каскад модулей
 * «бесплатные → по ключу → платные», собирает данные и возвращает отчёт
 * в утвердительной форме с анонимизированными номерами модулей.
 */

/** Что именно пришло на вход агенту. */
enum class AgentTarget(val title: String) {
    PERSON("ФИО"),
    PHONE("Телефон"),
    EMAIL("Email"),
    USERNAME("Никнейм"),
    DOMAIN("Домен"),
    IP("IP-адрес"),
    VIN("VIN / госномер"),
    PHOTO("Фотография"),
    OTHER("Запрос")
}

/** Стоимость шага каскада: от дешёвого к дорогому. */
enum class CostTier(val title: String, val order: Int) {
    FREE("Без ключей", 0),
    KEYED("По ключу", 1),
    PAID("Платный запрос", 2)
}

enum class StepState(val title: String) {
    PENDING("Ожидает"),
    RUNNING("Выполняется"),
    DONE("Готово"),
    SKIPPED("Пропущено"),
    FAILED("Ошибка")
}

/** Один шаг плана: какие модули запускаем и что получили. */
data class AgentStep(
    val id: String,
    val title: String,
    val tier: CostTier,
    val moduleIds: List<String> = emptyList(),
    val state: StepState = StepState.PENDING,
    val facts: Int = 0,
    val note: String = ""
)

/** Утвердительное утверждение отчёта: факт + отдельный статус достоверности. */
data class AgentFinding(
    val text: String,
    val kind: String,
    val value: String,
    val status: Confidence,
    val moduleLabel: String,
    val evidence: String = ""
)

/** Аналитическое предположение — вынесено отдельно от фактов. */
data class AgentAssumption(val text: String, val basis: String)

/** Связь «модуль → номер» для анонимизации и приложения B. */
data class ModuleLabel(val id: String, val label: String, val realName: String, val url: String)

/** Итог работы агента. */
data class AgentReport(
    val query: String,
    val target: AgentTarget,
    val plan: List<AgentStep>,
    val findings: List<AgentFinding>,
    val assumptions: List<AgentAssumption>,
    val modules: List<ModuleLabel>,
    val data: AggregatedData,
    val anonymousData: AggregatedData,
    val freeOnly: Boolean,
    val paidRequests: Int,
    val skippedPaid: List<String>,
    val durationMs: Long,
    val note: String = ""
) {
    val facts: Int get() = findings.size
    val hasResults: Boolean get() = findings.isNotEmpty()

    /** Что собрано без ключей — для пометки в отчёте. */
    val freeModules: List<ModuleLabel>
        get() = modules.filter { module ->
            plan.firstOrNull { step -> module.id in step.moduleIds }?.tier == CostTier.FREE
        }
}

/** Состояние агента в интерфейсе. */
data class AgentUi(
    val query: String = "",
    val target: AgentTarget = AgentTarget.OTHER,
    /** Активная вкладка: 0 — агент, 1 — авто, 2 — фото, 3 — объявления. */
    val section: Int = 0,
    val running: Boolean = false,
    val stage: String = "",
    val progress: Int = 0,
    val error: String? = null,
    val report: AgentReport? = null,
    val history: List<String> = emptyList()
)

/** Изменение в мониторинге объявлений. */
data class WatchItem(
    val id: String,
    val title: String,
    val query: String,
    val priceMax: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastChecked: Long = 0L,
    val lastTitle: String = "",
    val lastUrl: String = "",
    val hits: Int = 0
)

/** Результат проверки авто. */
data class AutoReport(
    val query: String,
    val vin: String = "",
    val plate: String = "",
    val summary: List<Pair<String, String>> = emptyList(),
    val findings: List<AgentFinding> = emptyList(),
    val assumptions: List<AgentAssumption> = emptyList(),
    val manualLinks: List<Pair<String, String>> = emptyList(),
    val modules: List<ModuleLabel> = emptyList(),
    val ok: Boolean = false,
    val error: String? = null
)

/** Состояние вкладки «Авто». */
data class AutoUi(
    val query: String = "",
    val running: Boolean = false,
    val report: AutoReport? = null,
    val error: String? = null,
    /** Телефон продавца: отдельный прогон агента по номеру. */
    val sellerPhone: String = "",
    val sellerRunning: Boolean = false,
    val sellerReport: AgentReport? = null,
    val sellerError: String? = null
)

/** Состояние вкладки «Поиск по фото». */
data class PhotoUi(
    val running: Boolean = false,
    val stage: String = "",
    val progress: Int = 0,
    val result: ru.jbosint.osint.data.PhotoSearchResult? = null,
    val error: String? = null,
    val platform: String? = null
)
