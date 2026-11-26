package ru.jbosint.osint.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.BuildConfig
import ru.jbosint.osint.core.aggregate.Aggregator
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.data.source.DataSourceRegistry
import ru.jbosint.osint.data.source.SearchParams
import ru.jbosint.osint.data.AssistantCatalog
import ru.jbosint.osint.data.AssistantClassification
import ru.jbosint.osint.data.AssistantParser
import ru.jbosint.osint.data.AssistantPrompts
import ru.jbosint.osint.data.AssistantRole
import ru.jbosint.osint.data.AssistantService
import ru.jbosint.osint.data.AssistantStore
import ru.jbosint.osint.data.AdviceOrigin
import ru.jbosint.osint.data.AiCallEntry
import ru.jbosint.osint.data.AssistantStats
import ru.jbosint.osint.data.ModuleAdvice
import ru.jbosint.osint.data.ModuleAnswerParser
import ru.jbosint.osint.data.ModuleApply
import ru.jbosint.osint.data.ModuleAssistantStore
import ru.jbosint.osint.data.ModulePlan
import ru.jbosint.osint.data.ModulePlanner
import ru.jbosint.osint.data.ModulePrompts
import ru.jbosint.osint.data.ModuleReportBuilder
import ru.jbosint.osint.data.TokenEstimate
import ru.jbosint.osint.data.AdminPolicy
import ru.jbosint.osint.data.AdminStore
import ru.jbosint.osint.data.BackupPayload
import ru.jbosint.osint.data.KeyBackupService
import ru.jbosint.osint.data.VaultCrypto
import ru.jbosint.osint.data.WorkMode
import ru.jbosint.osint.data.WebHit
import ru.jbosint.osint.data.WebResearch
import ru.jbosint.osint.data.CustomAuth
import ru.jbosint.osint.data.CustomConnectionProbe
import ru.jbosint.osint.data.CustomModule
import ru.jbosint.osint.data.CustomModuleChecker
import ru.jbosint.osint.data.AutoSetup
import ru.jbosint.osint.data.Connectors
import ru.jbosint.osint.data.AnalyzeResult
import ru.jbosint.osint.data.DocHit
import ru.jbosint.osint.data.DocRecord
import ru.jbosint.osint.data.DocSearch
import ru.jbosint.osint.data.DocumentStore
import ru.jbosint.osint.data.LeakIndexReport
import ru.jbosint.osint.data.LeakRules
import ru.jbosint.osint.data.LeakScan
import ru.jbosint.osint.data.LeakStore
import ru.jbosint.osint.data.DocumentText
import ru.jbosint.osint.data.DocHints
import ru.jbosint.osint.data.SourceLink
import ru.jbosint.osint.data.CustomModuleSpec
import ru.jbosint.osint.data.CustomModuleStore
import ru.jbosint.osint.data.CustomResponseParser
import ru.jbosint.osint.data.DiagBlock
import ru.jbosint.osint.data.DiagMask
import ru.jbosint.osint.data.DiagReport
import ru.jbosint.osint.data.DiagSamples
import ru.jbosint.osint.data.JsonShape
import ru.jbosint.osint.data.DataMerge
import ru.jbosint.osint.data.FaceSearch
import ru.jbosint.osint.data.PhotoAnalysis
import ru.jbosint.osint.data.PhotoAnalyzer
import ru.jbosint.osint.data.QueryDetector
import ru.jbosint.osint.data.QueryKind
import ru.jbosint.osint.data.ReverseImage
import ru.jbosint.osint.data.Net
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.data.FieldRule
import ru.jbosint.osint.data.KeyCheck
import ru.jbosint.osint.data.HistoryEntry
import ru.jbosint.osint.data.HistoryStore
import ru.jbosint.osint.data.KeyRepository
import ru.jbosint.osint.data.ConnectionUi
import ru.jbosint.osint.data.TermuxCheck
import ru.jbosint.osint.data.TermuxResults
import ru.jbosint.osint.data.TermuxSpec
import ru.jbosint.osint.data.PageReader
import ru.jbosint.osint.data.Remote
import ru.jbosint.osint.data.RaeRemote
import ru.jbosint.osint.data.SecureStore
import ru.jbosint.osint.data.KeyIntake
import ru.jbosint.osint.data.KeyState
import ru.jbosint.osint.data.ProviderGuess
import ru.jbosint.osint.data.RepairPlan
import ru.jbosint.osint.data.RepairStep
import ru.jbosint.osint.data.SetupRoute
import ru.jbosint.osint.data.KeyValidator
import ru.jbosint.osint.data.ProviderCatalog
import ru.jbosint.osint.data.ProviderSpec
import ru.jbosint.osint.data.SearchEngine
import ru.jbosint.osint.data.SettingsStore
import ru.jbosint.osint.core.model.ReportMeta
import ru.jbosint.osint.core.report.HtmlReport
import ru.jbosint.osint.core.report.XlsxReport
import ru.jbosint.osint.data.TgDossier
import ru.jbosint.osint.data.TgExport
import ru.jbosint.osint.data.TgParser
import ru.jbosint.osint.report.ReportExporter
import ru.jbosint.osint.profile.DossierTemplate
import ru.jbosint.osint.profile.ProfileBrief
import ru.jbosint.osint.profile.ProfileBuilder
import ru.jbosint.osint.profile.ProfileCompare
import ru.jbosint.osint.profile.ProfileData
import ru.jbosint.osint.profile.ProfileDiff
import ru.jbosint.osint.profile.ProfileExport
import ru.jbosint.osint.profile.ProfileExporter
import ru.jbosint.osint.profile.ProfileMessages
import ru.jbosint.osint.profile.ProfileStore
import ru.jbosint.osint.agent.AgentFinding
import ru.jbosint.osint.agent.AgentNarrator
import ru.jbosint.osint.agent.AgentPlanner
import ru.jbosint.osint.agent.AgentReport
import ru.jbosint.osint.agent.AgentRunner
import ru.jbosint.osint.agent.AgentTemplate
import ru.jbosint.osint.agent.AgentTemplates
import ru.jbosint.osint.data.Notify
import ru.jbosint.osint.data.JournalEntry
import ru.jbosint.osint.data.SearchJournal
import ru.jbosint.osint.data.TelegramRules
import ru.jbosint.osint.agent.AgentTarget
import ru.jbosint.osint.agent.AgentUi
import ru.jbosint.osint.agent.AutoReport
import ru.jbosint.osint.agent.AutoUi
import ru.jbosint.osint.agent.PhotoUi
import ru.jbosint.osint.agent.WatchItem
import ru.jbosint.osint.data.AutoCheck
import ru.jbosint.osint.data.PhotoSearch
import ru.jbosint.osint.data.PinHash
import ru.jbosint.osint.data.PhotoSearchResult
import ru.jbosint.osint.data.WatchStore

/** Попыток ввода PIN до паузы. Дальше пауза удваивается: 30 с, 60 с, 120 с … */
private const val MAX_PIN_FAILS = 5

/** Экраны, доступные только администратору. */
private val MANAGED_SCREENS = setOf(Screen.KEYS, Screen.SETTINGS, Screen.MODULES, Screen.PLUGINS)

enum class Screen { HOME, RESULTS, TELEGRAM, LEAKS, KEYS, SETTINGS, PROFILE, AGENT, MODULES, PLUGINS }

/** Состояние быстрой настройки модуля: прогресс или предложение ручной настройки. */
data class AutoSetupUi(
    val running: Boolean,
    val stage: String,
    val failed: Boolean,
    val title: String,
    val key: String,
    val link: String,
    val skip: Int = 0,
    val suggested: CustomModule? = null,
    val hints: DocHints = DocHints(),
    val exhausted: Boolean = false
)

data class ProviderUi(
    val spec: ProviderSpec,
    val enabled: Boolean,
    val hasKey: Boolean,
    val hasSecond: Boolean,
    val state: KeyState,
    val checkedAt: Long,
    val checking: Boolean = false
)

data class ShareRequest(val uri: Uri, val mime: String, val fileName: String)

/** Формат выгрузки Telegram-парсера. */
enum class TgFormat(val extension: String, val mime: String, val label: String) {
    HTML("html", "text/html", "HTML-отчёт"),
    CSV("csv", "text/csv", "CSV"),
    XLSX(
        "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Excel"
    ),
    RAW_JSON("json", "application/json", "Raw JSON")
}

/** Краткая карточка снимка, по которому выполняется или выполнен поиск. */
data class PhotoCard(
    val fileName: String,
    val summary: String,
    val faces: Int,
    val sha256: String,
    val hits: Int = 0
)

/** Предварительная выборка: факты без сохранения истории и без отчёта. */
data class PreviewState(
    val running: Boolean = false,
    val facts: List<Fact> = emptyList(),
    val rawFacts: Int = 0,
    val sourcesAttempted: Int = 0,
    val sourcesResponded: Int = 0,
    val error: String? = null
) {
    val isEmpty: Boolean get() = facts.isEmpty()
}

/** Состояние Telegram-парсера: ввод канала, прогресс сбора и готовый список аудитории. */
data class TgUi(
    val channel: String = "",
    val mode: ru.jbosint.osint.data.TelegramRules.Kind = ru.jbosint.osint.data.TelegramRules.Kind.USERNAME,
    val pages: Int = TgParser.DEFAULT_MAX_PAGES,
    val running: Boolean = false,
    val exporting: Boolean = false,
    val stage: String = "",
    val error: String? = null,
    val dossier: TgDossier? = null
)

/**
 * Предложение восстановить резервную копию, найденную на устройстве.
 * [config] — пользовательская сборка, иначе обычная копия ключей.
 */
data class RestoreOffer(
    val name: String,
    val path: String,
    val config: Boolean,
    val automatic: Boolean = true
)

data class UiState(
    val ready: Boolean = false,
    val screen: Screen = Screen.HOME,
    val onboarding: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val stage: String = "",
    val progress: Int = 0,
    val searchError: String? = null,
    val result: AggregatedData? = null,
    val resultEntry: HistoryEntry? = null,
    val photo: PhotoCard? = null,
    // ------------------------------------------- отдельная вкладка «Утечки»
    val leaksQuery: String = "",
    val leaksRunning: Boolean = false,
    val leaksFacts: List<Fact> = emptyList(),
    val leaksError: String? = null,
    val leaksSources: Int = 0,
    val leaksDone: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    val providers: List<ProviderUi> = emptyList(),
    val customModules: List<CustomModule> = emptyList(),
    val customChecking: Set<String> = emptySet(),
    val customChecks: Map<String, KeyCheck> = emptyMap(),
    val preview: PreviewState? = null,
    val analyst: String = "",
    val demoMode: Boolean = false,
    val secureScreen: Boolean = true,
    val autoWipeDays: Int = 30,
    val exporting: Boolean = false,
    val diagRunning: Boolean = false,
    val diagReport: String? = null,
    val diagBlocks: List<DiagBlock> = emptyList(),
    // -------------------------------------------------- ИИ-ассистент модулей
    /** Идёт проверка модулей и разбор рекомендаций. */
    val moduleBusy: Boolean = false,
    val moduleStage: String = "",
    val moduleAdvices: List<ModuleAdvice> = emptyList(),
    val moduleStats: AssistantStats = AssistantStats(),
    val moduleJournal: List<AiCallEntry> = emptyList(),
    val moduleRunAt: Long = 0L,
    val moduleQuery: String = "",
    val moduleError: String? = null,
    val moduleExporting: Boolean = false,
    // ------------------------------------------------ режим работы и резервные копии
    val workMode: String = WorkMode.ADMIN.id,
    val adminHasPassword: Boolean = false,
    /** Сессия администратора разблокирована (или пароля ещё нет). */
    val adminUnlocked: Boolean = true,
    val adminLockedUntil: Long = 0L,
    val adminRecovery: Boolean = false,
    val adminBiometric: Boolean = false,
    val backupBusy: Boolean = false,
    val backupNote: String? = null,
    val restoreOffer: RestoreOffer? = null,
    /** Экран, который ждёт ввода мастер-пароля. */
    val pendingUnlock: String? = null,
    /** Сколько ключей сейчас сохранено — для подсказок и проверки «пусто ли». */
    val storedKeys: Int = 0,
    val repairing: Boolean = false,
    val repairLog: List<String> = emptyList(),
    val connection: ConnectionUi? = null,
    val autoSetup: AutoSetupUi? = null,
    val manualDraft: CustomModule? = null,
    val manualKey: String = "",
    val toast: String? = null,
    val tg: TgUi = TgUi(),
    // ---------------------------------------------- интеллектуальный ассистент
    val assistantConfigured: Boolean = false,
    val assistantProvider: String = AssistantCatalog.DEFAULT_PROVIDER,
    val assistantBaseUrl: String = "",
    /** Поставщики, у которых уже сохранён ключ (точки на переключателе). */
    val assistantKeyed: Set<String> = emptySet(),
    val assistantModel: String = AssistantCatalog.DEFAULT_MODEL,
    /** Уровень усилий Agent API (low/medium/high). */
    val assistantPreset: String = "medium",
    val assistantState: KeyState = KeyState.UNCHECKED,
    val assistantCheckedAt: Long = 0L,
    val assistantChecking: Boolean = false,
    val assistantBusy: String = "",
    val assistantError: String? = null,
    val assistantAutoAnalyze: Boolean = false,
    val assistantAutoClassify: Boolean = false,
    val assistantAutoQueries: Boolean = false,
    val assistantPolish: Boolean = false,
    val autoResearch: Boolean = false,
    val analysisRunning: Boolean = false,
    val researchRunning: Boolean = false,
    val research: String = "",
    val researchError: String? = null,
    val classifications: Map<String, AssistantClassification> = emptyMap(),
    // ------------------------------------------------------------ профиль объекта
    val profile: ProfileData? = null,
    val profileBusy: Boolean = false,
    val profileChanges: List<String> = emptyList(),
    val profilePinSet: Boolean = false,
    val profileUnlocked: Boolean = true,
    val profileDossier: String = "",
    val profileAnonymize: Boolean = false,
    val compareText: String = "",
    val profileBriefs: List<ProfileBrief> = emptyList(),
    // ------------------------------------------------------------ автономные агенты
    val agent: AgentUi = AgentUi(),
    val auto: AutoUi = AutoUi(),
    val photoSearch: PhotoUi = PhotoUi(),
    val watches: List<WatchItem> = emptyList(),
    val watchNotes: Map<String, String> = emptyMap(),
    val journal: List<JournalEntry> = emptyList(),
    // ------------------------------------------------------------ локальный бэкенд
    val termux: TermuxUi = TermuxUi(),
    // ------------------------------------------------------------ серверный режим
    val server: ServerUi = ServerUi(),
    val raeServer: RaeServerUi = RaeServerUi(),
    // ------------------------------------------------------------ документы досье
    val docs: DocsUi = DocsUi(),
    // ------------------------------------------------------------ индекс утечек
    val leak: LeakUi = LeakUi(),
    // ------------------------------------------------------------ сверка фактов
    val crossCheck: CrossCheckUi = CrossCheckUi()
) {
    /** Управление ключами, модулями и настройками доступно только администратору. */
    val canManage: Boolean
        get() = workMode == WorkMode.ADMIN.id && (adminUnlocked || !adminHasPassword)

    val userMode: Boolean get() = workMode == WorkMode.USER.id

    val activeModules: Int
        get() = providers.count { it.enabled && it.hasKey } + customModules.count { it.enabled && (it.hasKey || it.auth == CustomAuth.NONE) }
}

/** Сверка фактов на сервере: поиск противоречий по одному полю. */
data class CrossCheckUi(
    val open: Boolean = false,
    val busy: Boolean = false,
    val facts: Int = 0,
    val result: AnalyzeResult? = null,
    val error: String? = null
)

/** Раздел «Документы»: локальное досье и полнотекстовый поиск по нему. */
data class DocsUi(
    val open: Boolean = false,
    val busy: Boolean = false,
    val query: String = "",
    val items: List<DocRecord> = emptyList(),
    val hits: List<DocHit> = emptyList(),
    /** Подсказка после импорта: что получилось и что не удалось. */
    val note: String? = null
)

/** Состояние локального бэкенда Termux. */
/**
 * Раздел «Утечки»: локальный индекс дампов плюс каскад API.
 * Отчёт отвечает на вопрос «учётная запись засветилась?», пароль не показывает.
 */
data class LeakUi(
    val open: Boolean = false,
    val useApi: Boolean = true,
    val useIndex: Boolean = true,
    val rawScan: Boolean = false,
    val query: String = "",
    val busy: Boolean = false,
    val stage: String = "",
    val note: String? = null,
    val offline: Boolean = false,
    val cached: Boolean = false,
    val sources: List<LeakSourceUi> = emptyList(),
    val fileCount: Int = 0,
    val rowCount: Long = 0,
    val secretRows: Long = 0,
    val sizeMb: Int = 0
)

data class LeakSourceUi(
    val id: String,
    val title: String,
    val hits: Int,
    val cached: Boolean = false,
    val note: String? = null,
    val rows: List<LeakRowUi> = emptyList()
)

data class LeakRowUi(
    val value: String,
    val where: String = "",
    val secret: Boolean = false
)

data class TermuxUi(
    val enabled: Boolean = false,
    val home: String = TermuxSpec.DEFAULT_HOME,
    val trusted: List<String> = TermuxSpec.DEFAULT_TRUSTED,
    val checking: Boolean = false,
    val checks: List<TermuxCheck> = emptyList(),
    val note: String? = null,
    val running: String? = null,
    val lastRunAt: Long = 0L
)

/** Состояние серверного ускорителя (гибридный режим). */
data class ServerUi(
    val enabled: Boolean = false,
    val url: String = "",
    val keyed: Boolean = false,
    val checking: Boolean = false,
    val online: Boolean = false,
    val note: String? = null
)

/** Ответ серверного RAE. */
data class RaeServerUi(
    val busy: Boolean = false,
    val result: RaeRemote? = null,
    val error: String? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val keys = KeyRepository(app)
    private val secure = SecureStore(app)
    private val historyStore = HistoryStore(app)
    private val exporter = ReportExporter(app)
    private val validator = KeyValidator(keys)
    private val customStore = CustomModuleStore(app)
    private val customChecker = CustomModuleChecker(customStore)
    private val engine = SearchEngine(keys, settings)
    private val assistantStore = AssistantStore(app)
    private val assistantService = AssistantService(assistantStore)
    /** Кэш рекомендаций и журнал обращений к модели (JSON во внутренней папке). */
    private val moduleAssistant = ModuleAssistantStore(java.io.File(app.filesDir, "module_assistant.json"))
    /** Режим работы, мастер-пароль и резервные копии ключей. */
    private val adminStore = AdminStore(app)
    private val backupService = KeyBackupService(app, keys, customStore, assistantStore, settings)
    private val profileStore = ProfileStore(app)
    private val agentRunner = AgentRunner(keys)
    private val watchStore = WatchStore(app)
    private val docStore = DocumentStore(app)
    private val leakStore = LeakStore(app)
    private val leakScan = LeakScan(leakStore, keys)
    /** Сообщения, импортированные пользователем для профиля (живут до пересборки). */
    private var importedMessages: List<ru.jbosint.osint.profile.ProfileMessage> = emptyList()
    private var termuxDiagRequest: String? = null
    private var termuxScriptRequest: String? = null

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _shareEvents = MutableSharedFlow<ShareRequest>(extraBufferCapacity = 4)
    val shareEvents = _shareEvents.asSharedFlow()

    init {
        // Серверный ускоритель чтения страниц: включается только когда сервер задан
        // и включён; иначе поиск остаётся полностью локальным.
        PageReader.serverFallback = { url ->
            if (!settings.serverEnabled || settings.serverUrl.isBlank()) null
            else Remote.scrape(settings.serverUrl, secure.get("server_key").orEmpty(), url)
        }
        // Результаты команд Termux приходят через PendingIntent -> BroadcastReceiver.
        viewModelScope.launch {
            TermuxResults.events.collect { result -> onTermuxResult(result) }
        }
        // Автоудаление старых отчётов и загрузка истории — вне главного потока.
        viewModelScope.launch(Dispatchers.IO) {
            val keepDays = settings.autoWipeDays
            if (keepDays > 0) {
                val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
                historyStore.list().filter { it.createdAt < cutoff }.forEach { historyStore.delete(it.id) }
            }
            val entries = historyStore.list()
            _state.update { it.copy(history = entries) }
        }
        _state.value = UiState(
            ready = true,
            screen = Screen.HOME,
            onboarding = settings.onboardingDone,
            analyst = settings.analyst,
            demoMode = settings.demoMode,
            secureScreen = settings.secureScreen,
            autoWipeDays = settings.autoWipeDays,
            history = emptyList(),
            providers = buildProviders(),
            customModules = customStore.list(),
            assistantConfigured = assistantStore.configured(),
            assistantProvider = assistantStore.provider,
            assistantBaseUrl = assistantStore.customBaseUrl,
            assistantKeyed = keyedProviders(),
            assistantModel = assistantStore.model,
            assistantPreset = assistantStore.preset,
            assistantState = assistantStore.state(),
            assistantCheckedAt = assistantStore.checkedAt(),
            assistantAutoAnalyze = assistantStore.autoAnalyze,
            assistantAutoClassify = assistantStore.autoClassify,
            assistantAutoQueries = assistantStore.autoQueries,
            assistantPolish = assistantStore.polishReport,
            autoResearch = assistantStore.autoResearch,
            profilePinSet = settings.profilePin.isNotBlank(),
            profileUnlocked = settings.profilePin.isBlank(),
            profileBriefs = profileStore.brief(),
            watches = watchStore.list(),
            moduleStats = moduleAssistant.stats(),
            moduleJournal = moduleAssistant.journal(),
            workMode = adminStore.mode.id,
            adminHasPassword = adminStore.hasPassword(),
            adminUnlocked = !adminStore.hasPassword(),
            adminLockedUntil = adminStore.lockedUntil(),
            adminRecovery = adminStore.hasRecovery(),
            adminBiometric = adminStore.biometric,
            storedKeys = countStoredKeys(),
            termux = TermuxUi(
                enabled = settings.termuxEnabled,
                home = settings.termuxHome,
                trusted = settings.termuxTrusted
            ),
            server = ServerUi(
                enabled = settings.serverEnabled,
                url = settings.serverUrl,
                keyed = secure.get("server_key")?.isNotBlank() == true
            )
        )
        detectBackupOffer()
    }

    private fun countStoredKeys(): Int =
        keys.activeCount() + customStore.list().count { customStore.key(it.id).isNotBlank() }

    private fun buildProviders(): List<ProviderUi> = ProviderCatalog.all.map { spec ->
        ProviderUi(
            spec = spec,
            enabled = keys.isEnabled(spec.id),
            hasKey = spec.keyless || keys.key(spec.id).isNotBlank(),
            hasSecond = keys.secondField(spec.id).isNotBlank(),
            state = keys.state(spec.id),
            checkedAt = keys.checkedAt(spec.id)
        )
    }

    private fun refreshProviders() = _state.update { it.copy(providers = buildProviders()) }

    private fun refreshCustom() = _state.update { it.copy(customModules = customStore.list()) }

    // ---------------------------------------------------------------- навигация

    fun navigate(screen: Screen) {
        val current = _state.value
        // В режиме «Пользователь» управление ключами и настройками закрыто.
        if (!current.canManage && screen in MANAGED_SCREENS) {
            _state.update { it.copy(toast = "Режим «Пользователь»: настройки и ключи заблокированы") }
            return
        }
        // Мастер-пароль спрашивается каждый раз при входе в управляющие разделы.
        if (screen in MANAGED_SCREENS && current.adminHasPassword && !current.adminUnlocked) {
            _state.update {
                it.copy(pendingUnlock = screen.name, toast = "Введите мастер-пароль администратора")
            }
            return
        }
        _state.update { it.copy(screen = screen, searchError = null) }
    }

    /** Пароль введён для отложенного перехода: при успехе открываем нужный экран. */
    fun completePendingUnlock(password: String): String? {
        val target = _state.value.pendingUnlock ?: return null
        val error = adminUnlock(password)
        if (error != null) return error
        val screen = runCatching { Screen.valueOf(target) }.getOrNull() ?: Screen.SETTINGS
        _state.update { it.copy(pendingUnlock = null, screen = screen, searchError = null) }
        return null
    }

    fun cancelPendingUnlock() = _state.update { it.copy(pendingUnlock = null) }

    fun backHome() = _state.update { it.copy(screen = Screen.HOME) }

    fun openHistory(entry: HistoryEntry) = _state.update {
        it.copy(
            screen = Screen.RESULTS,
            result = entry.data,
            resultEntry = entry,
            searchError = null,
            research = entry.aiResearch,
            researchError = null
        )
    }

    // ---------------------------------------------------------------- онбординг

    fun completeOnboarding(analyst: String) {
        settings.onboardingDone = true
        settings.analyst = analyst.trim()
        _state.update { it.copy(onboarding = true, analyst = analyst.trim(), screen = Screen.HOME) }
    }

    // ------------------------------------------------------------------- поиск

    fun setQuery(value: String) = _state.update {
        it.copy(query = value, searchError = null, preview = null)
    }

    /** «Тестировать поиск»: предварительный список фактов без отчёта и без сохранения. */
    fun previewSearch() {
        val current = _state.value
        if (current.preview?.running == true || current.searching) return
        val query = current.query.trim()
        if (query.length < 2) {
            _state.update { it.copy(preview = PreviewState(error = "Введите запрос длиной от 2 символов")) }
            return
        }
        _state.update { it.copy(preview = PreviewState(running = true), searchError = null) }
        viewModelScope.launch {
            val modules = customStore.activeModules()
            val outcome = runCatching {
                engine.search(query, { _, _ -> }, modules)
            }
            outcome.onSuccess { data ->
                _state.update {
                    it.copy(
                        preview = PreviewState(
                            running = false,
                            facts = data.facts,
                            rawFacts = data.stats.rawFacts,
                            sourcesAttempted = data.stats.sourcesAttempted,
                            sourcesResponded = data.stats.sourcesResponded
                        )
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        preview = PreviewState(
                            running = false,
                            error = "Предпросмотр не удался: ${error.message ?: "неизвестная ошибка"}"
                        )
                    )
                }
            }
        }
    }

    fun clearPreview() = _state.update { it.copy(preview = null) }

    // ------------------------------------------------------- проверка утечек

    fun setLeaksQuery(value: String) = _state.update {
        it.copy(
            leaksQuery = value, leaksError = null,
            leaksFacts = emptyList(), leaksSources = 0, leaksDone = false
        )
    }

    fun clearLeaks() = _state.update {
        it.copy(
            leaksQuery = "", leaksFacts = emptyList(), leaksError = null,
            leaksSources = 0, leaksDone = false
        )
    }

    /**
     * Проверка только по email/телефону через официальные API (HIBP, LeakCheck).
     * Пароль не запрашивается и не сохраняется: результат — лишь факт утечки,
     * дата, набор данных и типы скомпрометированных полей.
     */
    fun runLeaksCheck() {
        val current = _state.value
        if (current.leaksRunning) return
        val query = current.leaksQuery.trim()
        val kind = QueryDetector.detect(query)
        if (query.length < 3 || (kind != QueryKind.EMAIL && kind != QueryKind.PHONE)) {
            _state.update { it.copy(leaksError = "Введите email или телефон для проверки") }
            return
        }
        if (keys.key("hibp").isBlank() && keys.key("leakcheck").isBlank()) {
            _state.update {
                it.copy(leaksError = "Добавьте ключ Have I Been Pwned или LeakCheck в разделе «Ключи»")
            }
            return
        }
        _state.update {
            it.copy(
                leaksRunning = true, leaksError = null,
                leaksFacts = emptyList(), leaksSources = 0, leaksDone = false
            )
        }
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val outcome = runCatching {
                val sources = DataSourceRegistry.leakSources
                val responded = java.util.concurrent.atomic.AtomicInteger(0)
                val raw = coroutineScope {
                    sources.map { source ->
                        async {
                            val found: List<RawFact> = runCatching {
                                kotlinx.coroutines.withTimeout(25_000) {
                                    source.search(SearchParams(query), keys)
                                }
                            }.getOrElse { emptyList() }
                            if (found.isNotEmpty()) responded.incrementAndGet()
                            found
                        }
                    }.awaitAll().flatten()
                }
                val counted = responded.get()
                Aggregator.aggregate(
                    query,
                    raw,
                    SearchStats(
                        sourcesAttempted = sources.size,
                        sourcesResponded = counted,
                        sourcesFailed = sources.size - counted,
                        rawFacts = raw.size,
                        durationMs = System.currentTimeMillis() - started
                    )
                ) to counted
            }
            outcome.onSuccess { (data, counted) ->
                _state.update {
                    it.copy(
                        leaksRunning = false,
                        leaksFacts = data.facts,
                        leaksSources = counted,
                        leaksDone = true
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        leaksRunning = false,
                        leaksError = "Проверка не удалась: ${error.message ?: "неизвестная ошибка"}"
                    )
                }
            }
        }
    }

    fun startSearch() {
        val current = _state.value
        if (current.searching) return
        val query = current.query.trim()
        if (query.length < 2) {
            _state.update { it.copy(searchError = "Введите запрос: имя, адрес почты, телефон, домен, IP или никнейм") }
            return
        }
        if (keys.activeCount() == 0 && customStore.activeModules().isEmpty() && !settings.demoMode) {
            _state.update {
                it.copy(
                    searchError = "Нет активных модулей. Добавьте хотя бы один API-ключ или включите демо-режим."
                )
            }
            return
        }
        _state.update {
            it.copy(
                searching = true, stage = "Подготовка модулей", progress = 0,
                searchError = null, result = null, resultEntry = null,
                research = "", researchError = null, classifications = emptyMap()
            )
        }
        viewModelScope.launch {
            var effective = query
            if (assistantStore.autoQueries && assistantStore.configured()) {
                _state.update { it.copy(stage = "Ассистент: подготовка запросов") }
                effective = refineQuery(query) ?: query
            }
            val outcome = runCatching {
                engine.search(effective, { stage, progress ->
                    _state.update { s -> s.copy(stage = stage, progress = progress.coerceIn(0, 100)) }
                }, customStore.activeModules())
            }
            outcome.onSuccess { data ->
                val entry = HistoryEntry(
                    id = historyStore.newId(),
                    query = data.query,
                    createdAt = System.currentTimeMillis(),
                    analyst = settings.analyst,
                    data = data,
                    demo = settings.demoMode,
                    queryType = QueryDetector.detect(effective).title
                )
                val entries = withContext(Dispatchers.IO) {
                    historyStore.save(entry)
                    historyStore.list()
                }
                _state.update {
                    it.copy(
                        searching = false,
                        progress = 100,
                        stage = "Готово",
                        result = data,
                        resultEntry = entry,
                        screen = Screen.RESULTS,
                        history = entries
                    )
                }
                autoEnrich(entry)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        searching = false,
                        progress = 0,
                        stage = "",
                        searchError = "Поиск прерван: ${error.message ?: "неизвестная ошибка"}"
                    )
                }
            }
        }
    }

    /** «Поиск по фото»: разбор снимка, обратный поиск и уточнение найденных профилей. */
    fun startPhotoSearch(uri: Uri) {
        val current = _state.value
        if (current.searching || current.preview?.running == true) return
        _state.update {
            it.copy(
                searching = true, stage = "Анализ фотографии", progress = 3,
                searchError = null, result = null, resultEntry = null, photo = null,
                research = "", researchError = null, classifications = emptyMap()
            )
        }
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val outcome = runCatching {
                val app = getApplication<Application>()
                val photo = PhotoAnalyzer.analyze(app, uri)
                _state.update {
                    it.copy(
                        stage = "Обратный поиск по изображению", progress = 30,
                        photo = PhotoCard(photo.fileName, photo.summary(), photo.faces, photo.sha256, 0)
                    )
                }
                val photoFacts = PhotoAnalyzer.facts(photo).toMutableList()
                val key = keys.key("saucenao")
                val hits = if (key.isNotBlank()) {
                    runCatching { ReverseImage.sauceNao(key, photo) }.getOrElse { emptyList() }
                } else emptyList()

                hits.forEach { hit ->
                    photoFacts += RawFact(
                        category = FactCategory.DOCUMENT,
                        kind = "Совпадение изображения",
                        value = hit.url,
                        subject = photo.fileName,
                        attributes = mapOf(
                            "совпадение" to "${hit.similarity}%",
                            "ресурс" to hit.provider,
                            "название" to hit.title
                        ),
                        sourceId = "photo",
                        sourceUrl = hit.url
                    )
                }

                // Поиск по лицу: отдельный сервис, адрес эндпоинта и ключ задаёт пользователь.
                _state.update { it.copy(stage = "Поиск по лицу", progress = 36) }
                val faceKey = keys.key("faceapi")
                val faceEndpoint = keys.secondField("faceapi")
                val faceReady = faceKey.isNotBlank() && faceEndpoint.isNotBlank()
                val faceHits = if (faceReady) {
                    runCatching { FaceSearch.search(faceEndpoint, faceKey, photo) }.getOrElse { emptyList() }
                } else emptyList()
                val knownHosts = hits.map { it.url.substringAfter("://").substringBefore('/') }.toSet()
                faceHits.forEach { hit ->
                    val host = hit.url.substringAfter("://").substringBefore('/')
                    val confirmed = knownHosts.any { it.equals(host, ignoreCase = true) }
                    photoFacts += RawFact(
                        category = FactCategory.DOCUMENT,
                        kind = "Совпадение по фото",
                        value = hit.url,
                        subject = photo.fileName,
                        attributes = mapOf(
                            "схожесть" to "${hit.similarity}%",
                            "ресурс" to hit.provider,
                            "статус" to FaceSearch.statusFor(hit.similarity, confirmed),
                            "название" to hit.title
                        ),
                        sourceId = "faceapi",
                        sourceUrl = hit.url
                    )
                }
                val handles = PhotoAnalyzer.handlesFrom((hits.map { it.url } + faceHits.map { it.url })).take(3)
                val parts = mutableListOf<AggregatedData>()
                handles.forEachIndexed { index, handle ->
                    _state.update {
                        it.copy(stage = "Уточнение по найденному профилю: $handle", progress = 40 + index * 15)
                    }
                    runCatching { engine.search(handle, { _, _ -> }, customStore.activeModules()) }
                        .getOrNull()?.let { parts += it }
                }
                val merged = DataMerge.merge(
                    query = "Фото ${photo.fileName}",
                    parts = parts,
                    extra = photoFacts,
                    durationMs = System.currentTimeMillis() - started
                )
                val note = buildPhotoNote(photo, hits.size, handles, key.isNotBlank(), faceHits.size, faceReady)
                _state.update { s ->
                    s.copy(photo = s.photo?.copy(hits = hits.size) ?: PhotoCard(photo.fileName, photo.summary(), photo.faces, photo.sha256, hits.size))
                }
                merged to note
            }
            outcome.onSuccess { (data, note) ->
                val entry = HistoryEntry(
                    id = historyStore.newId(),
                    query = data.query,
                    createdAt = System.currentTimeMillis(),
                    analyst = settings.analyst,
                    data = data,
                    demo = settings.demoMode,
                    queryType = "Фотография",
                    photoNote = note
                )
                val entries = withContext(Dispatchers.IO) {
                    historyStore.save(entry)
                    historyStore.list()
                }
                _state.update {
                    it.copy(
                        searching = false, progress = 100, stage = "Готово",
                        result = data, resultEntry = entry, screen = Screen.RESULTS, history = entries
                    )
                }
                autoEnrich(entry)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        searching = false, progress = 0, stage = "", photo = null,
                        searchError = "Поиск по фото прерван: ${error.message ?: "неизвестная ошибка"}"
                    )
                }
            }
        }
    }

    private fun buildPhotoNote(
        photo: PhotoAnalysis,
        hits: Int,
        handles: List<String>,
        providerReady: Boolean,
        faceHits: Int = 0,
        faceReady: Boolean = false
    ): String {
        val parts = mutableListOf<String>()
        parts += "Снимок: ${photo.summary()}."
        if (photo.exif["Координаты"] != null) parts += "В файле сохранена геометка — она включена в отчёт."
        parts += when {
            !providerReady -> "Обратный поиск по изображению не выполнялся: не добавлен ключ модуля SauceNAO."
            hits == 0 -> "Обратный поиск выполнен, совпадений выше порога не найдено."
            else -> "Обратный поиск: $hits совпадений, ссылки добавлены в доказательную базу."
        }
        if (faceReady) {
            parts += if (faceHits == 0) "Поиск по лицу выполнен, совпадений не найдено."
            else "Поиск по лицу: $faceHits совпадений, у каждой находки указаны схожесть и статус проверки."
        } else {
            parts += "Поиск по лицу не выполнялся: в «Модули» не заданы ключ и адрес API сервиса."
        }
        if (handles.isNotEmpty()) {
            parts += "По найденным ссылкам дополнительно проверены профили: ${handles.joinToString(", ")}."
        }
        parts += "Выводы по личности на снимке не делаются автоматически: совпадение изображения не подтверждает тождество."
        return parts.joinToString(" ")
    }

    // ------------------------------------------------------------------ история

    fun deleteHistory(id: String) {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                historyStore.delete(id)
                historyStore.list()
            }
            _state.update { it.copy(history = entries) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyStore.clear() }
            _state.update { it.copy(history = emptyList()) }
        }
    }

    // ------------------------------------------------------------------- ключи

    fun notify(message: String) = _state.update { it.copy(toast = message) }

    fun toggleProvider(id: String, enabled: Boolean) {
        keys.setEnabled(id, enabled)
        refreshProviders()
    }

    fun saveKey(id: String, value: String) {
        keys.saveKey(id, value)
        refreshProviders()
        _state.update { it.copy(toast = "Ключ сохранён в защищённом хранилище") }
    }

    fun saveSecondField(id: String, value: String) {
        keys.saveSecondField(id, value)
        refreshProviders()
    }

    fun clearKey(id: String) {
        keys.saveKey(id, "")
        keys.saveSecondField(id, "")
        refreshProviders()
        _state.update { it.copy(toast = "Ключ удалён") }
    }

    fun checkKey(id: String) {
        val provider = _state.value.providers.firstOrNull { it.spec.id == id } ?: return
        if (provider.checking) return
        _state.update { s ->
            s.copy(providers = s.providers.map { if (it.spec.id == id) it.copy(checking = true, state = KeyState.CHECKING) else it })
        }
        viewModelScope.launch {
            val result = validator.check(id)
            _state.update { s ->
                s.copy(
                    providers = s.providers.map {
                        if (it.spec.id == id) it.copy(checking = false, state = result.state, checkedAt = keys.checkedAt(id), hasKey = keys.key(id).isNotBlank())
                        else it
                    },
                    toast = result.detail
                )
            }
        }
    }

    fun checkAll() {
        viewModelScope.launch {
            _state.value.providers.filter { it.hasKey && !it.checking }.forEach { provider ->
                checkKey(provider.spec.id)
            }
        }
    }

    // ------------------------------------------------------- быстрая настройка

    /** Фоновая задача автоподбора: отмена в диалоге должна действительно её останавливать. */
    private var autoSetupJob: Job? = null

    /** Быстрый режим: только название, ключ и ссылка на сервис — остальное подбирает приложение. */
    fun createModuleQuick(title: String, key: String, link: String) {
        if (_state.value.autoSetup?.running == true) return
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) { notify("Укажите название модуля"); return }
        if (cleanTitle.length > CustomModuleSpec.MAX_TITLE) { notify("Название длиннее ${CustomModuleSpec.MAX_TITLE} символов"); return }
        if (key.isBlank()) { notify("Укажите API-ключ"); return }
        val source = SourceLink.parse(link)
        if (source == null) { notify("Укажите ссылку на сайт сервиса или его Telegram-бота"); return }
        _state.update {
            it.copy(
                autoSetup = AutoSetupUi(
                    running = true, stage = "Изучаем ${source.display}…",
                    failed = false, title = cleanTitle, key = key, link = link.trim()
                )
            )
        }
        autoSetupJob = viewModelScope.launch {
            val hints = runCatching { AutoSetup.fetchHints(source) }.getOrDefault(DocHints())
            _state.update { it.copy(autoSetup = it.autoSetup?.copy(stage = "Подбираем способ подключения…", hints = hints)) }
            val outcome = AutoSetup.detect(source, key, hints, skip = 0)
            applyAutoOutcome(cleanTitle, key, outcome)
        }
    }

    fun retryAutoSetup() {
        val ui = _state.value.autoSetup ?: return
        if (ui.exhausted) return
        _state.update { it.copy(autoSetup = ui.copy(running = true, stage = "Пробуем оставшиеся варианты…", failed = false)) }
        autoSetupJob = viewModelScope.launch {
            val source = SourceLink.parse(ui.link) ?: return@launch
            val outcome = AutoSetup.detect(source, ui.key, ui.hints, skip = ui.skip)
            applyAutoOutcome(ui.title, ui.key, outcome)
        }
    }

    fun openManualAfterAuto() {
        val ui = _state.value.autoSetup ?: return
        _state.update {
            it.copy(
                autoSetup = null,
                manualKey = ui.key,
                manualDraft = ui.suggested ?: CustomModule(
                    id = "", title = ui.title, urlTemplate = "",
                    auth = CustomAuth.AUTO, enabled = true, hasKey = false
                )
            )
        }
    }

    fun clearManualDraft() = _state.update { it.copy(manualDraft = null, manualKey = "") }

    /** Черновик модуля из RAE: открывает обычный редактор с готовым URL-шаблоном. */
    fun openCustomDraft(module: CustomModule) {
        _state.update { it.copy(manualDraft = module, manualKey = "") }
    }

    fun cancelAutoSetup() {
        autoSetupJob?.cancel()
        autoSetupJob = null
        _state.update { it.copy(autoSetup = null) }
    }

    private fun applyAutoOutcome(title: String, key: String, outcome: AutoSetup.Outcome) {
        if (_state.value.autoSetup == null) return
        val setup = outcome.setup
        autoSetupJob = null
        if (outcome.fixed && setup != null && !outcome.urlTemplate.isNullOrBlank()) {
            val module = CustomModule(
                id = java.util.UUID.randomUUID().toString(),
                title = title,
                urlTemplate = outcome.urlTemplate,
                auth = setup.auth,
                method = setup.method,
                headerName = setup.headerName.ifBlank { "X-API-Key" },
                paramName = setup.paramName.ifBlank { "api_key" },
                enabled = true,
                createdAt = System.currentTimeMillis(),
                hasKey = true
            )
            customStore.save(module, key)
            refreshCustom()
            _state.update {
                it.copy(
                    autoSetup = null,
                    toast = if (outcome.strict) "Модуль настроен! Можно начинать поиск"
                    else "Адрес найден, но ключ отклонён (${outcome.code}) — проверьте ключ"
                )
            }
            if (outcome.strict) checkCustomModule(module.id)
        } else {
            val host = SourceLink.parse(_state.value.autoSetup?.link.orEmpty())?.host ?: ""
            val suggested = CustomModule(
                id = "",
                title = title,
                urlTemplate = outcome.urlTemplate ?: "https://$host/search?q=${CustomModuleSpec.PLACEHOLDER}",
                auth = setup?.auth ?: CustomAuth.AUTO,
                method = setup?.method ?: "GET",
                headerName = setup?.headerName?.ifBlank { "X-API-Key" } ?: "X-API-Key",
                paramName = setup?.paramName?.ifBlank { "api_key" } ?: "api_key",
                enabled = true,
                hasKey = false
            )
            _state.update {
                it.copy(
                    autoSetup = it.autoSetup?.copy(
                        running = false, failed = true, skip = outcome.attempts,
                        stage = outcome.detail,
                        suggested = suggested, exhausted = outcome.exhausted
                    )
                )
            }
        }
    }

    // ------------------------------------------------- подключение по ключу

    /**
     * Один ключ — один шаг: разбираем строку, определяем сервис, сохраняем
     * ключ и проверяем его. Для незнакомого сервиса автоматически собирается
     * свой модуль (адрес, метод и способ передачи ключа подбираются перебором).
     */
    fun connectByKey(raw: String) {
        if (_state.value.connection?.running == true) return
        val intake = KeyIntake.parse(raw)
        if (intake == null || !intake.hasKey) {
            _state.update {
                it.copy(connection = ConnectionUi(
                    done = true, ok = false, title = "Ключ не распознан",
                    detail = "Вставьте ключ из кабинета сервиса — строку длиной от 8 символов. Можно вместе с адресом или названием сервиса."
                ))
            }
            return
        }
        val route = ProviderGuess.route(intake)
        _state.update {
            it.copy(connection = ConnectionUi(running = true).step("Ключ найден: ${maskKey(intake.key)}"))
        }
        viewModelScope.launch {
            val result = when (route) {
                is SetupRoute.BuiltIn -> connectBuiltIn(route.id, intake.key, route.how)
                is SetupRoute.Assistant -> connectAssistant(route.providerId, intake.key, route.how)
                is SetupRoute.Custom -> connectCustom(route.url, route.title, intake.key, route.how)
                is SetupRoute.Unknown -> ConnectionUi(
                    done = true, ok = false, title = "Сервис не определён",
                    detail = "Добавьте адрес API (например, https://api.example.com) рядом с ключом — тогда приложение подберёт запрос само.",
                    lines = _state.value.connection?.lines.orEmpty()
                )
            }
            _state.update { it.copy(connection = result) }
        }
    }

    fun closeConnection() = _state.update { it.copy(connection = null) }

    /** Поставщики с сохранённым ключом — для индикаторов на переключателе. */
    private fun keyedProviders(): Set<String> =
        AssistantCatalog.providers.filter { assistantStore.hasKey(it.id) }.map { it.id }.toSet()

    private fun appendStep(text: String) {
        _state.update { s -> s.copy(connection = s.connection?.step(text)) }
    }

    /** Маска ключа для журнала: видно только начало и конец. */
    private fun maskKey(key: String): String =
        if (key.length <= 10) "***"
        else key.take(4) + "…" + key.takeLast(4)

    private suspend fun connectBuiltIn(id: String, key: String, why: String): ConnectionUi {
        val spec = ProviderCatalog.byId(id)
            ?: return ConnectionUi(done = true, ok = false, title = "Нет такого модуля", detail = why)
        appendStep("Сервис: ${spec.title} — $why")
        keys.saveKey(id, key)
        // Платному модулю нужен лимит расхода: без него проверка не пройдёт и поиск молчит.
        if (spec.requiresSecondField != null && keys.secondField(id).isBlank() && id == "dyxless") {
            keys.saveSecondField(id, "10")
            appendStep("Лимит расхода: 10 ₽ на запрос — меняется в настройках модуля.")
        }
        keys.setEnabled(id, true)
        refreshProviders()
        appendStep("Ключ сохранён, модуль включён. Проверяем…")
        val check = runCatching { validator.check(id) }
            .getOrElse { KeyCheck(KeyState.INVALID, it.message ?: "ошибка проверки") }
        refreshProviders()
        return if (check.state == KeyState.VALID) {
            ConnectionUi(
                done = true, ok = true, title = "${spec.title} подключён",
                detail = "${check.detail}. Модуль включён и участвует в поиске.",
                lines = _state.value.connection?.lines.orEmpty() + "Проверка: ${check.detail}"
            )
        } else {
            ConnectionUi(
                done = true, ok = false, title = "${spec.title}: ключ не принят",
                detail = "${check.detail}. Проверьте тип ключа в кабинете сервиса: ${spec.docsUrl}",
                lines = _state.value.connection?.lines.orEmpty() + "Проверка: ${check.detail}"
            )
        }
    }

    private suspend fun connectAssistant(providerId: String, key: String, why: String): ConnectionUi {
        val spec = AssistantCatalog.provider(providerId)
        appendStep("Это ключ ИИ-ассистента (${spec.title}) — подключаю его в настройках ассистента")
        assistantStore.provider = spec.id
        assistantStore.saveKey(key, spec.id)
        // Если сохранённая модель не из набора этого поставщика — берём его умолчание.
        if (spec.defaultModel.isNotBlank()) {
            val saved = assistantStore.model
            if (spec.models.isNotEmpty() && spec.models.none { it.id == saved }) {
                assistantStore.model = spec.defaultModel
            }
        }
        val check = runCatching { assistantService.probe() }
            .getOrElse { KeyCheck(KeyState.INVALID, it.message ?: "ошибка проверки") }
        _state.update {
            it.copy(
                assistantProvider = assistantStore.provider,
                assistantBaseUrl = assistantStore.customBaseUrl,
                assistantKeyed = keyedProviders(),
                assistantModel = assistantStore.model,
                assistantPreset = assistantStore.preset,
                assistantConfigured = assistantStore.configured(),
                assistantState = assistantStore.state(),
                assistantCheckedAt = assistantStore.checkedAt()
            )
        }
        return if (check.state == KeyState.VALID) {
            ConnectionUi(
                done = true, ok = true, title = "Ассистент на ${spec.title} подключён",
                detail = "${check.detail}. Модель: ${assistantStore.model}.",
                lines = _state.value.connection?.lines.orEmpty()
            )
        } else {
            ConnectionUi(
                done = true, ok = false, title = "${spec.title}: ключ не принят",
                detail = "${check.detail}. Ключ получить: ${spec.docsUrl}",
                lines = _state.value.connection?.lines.orEmpty()
            )
        }
    }

    private suspend fun connectCustom(
        url: String,
        title: String,
        key: String,
        why: String
    ): ConnectionUi {
        appendStep("$why. Изучаю документацию и подбираю запрос…")
        val link = SourceLink.parse(url)
            ?: return ConnectionUi(done = true, ok = false, title = "Адрес не разобран", detail = url)
        val hints = runCatching { AutoSetup.fetchHints(link) }.getOrDefault(DocHints())
        val outcome = AutoSetup.detect(link, key, hints)
        val setup = outcome.setup
        if (outcome.fixed && setup != null && !outcome.urlTemplate.isNullOrBlank()) {
            val module = CustomModule(
                id = java.util.UUID.randomUUID().toString(),
                title = title.take(CustomModuleSpec.MAX_TITLE),
                urlTemplate = outcome.urlTemplate,
                auth = setup.auth,
                method = setup.method,
                headerName = setup.headerName.ifBlank { "X-API-Key" },
                paramName = setup.paramName.ifBlank { "api_key" },
                enabled = true,
                createdAt = System.currentTimeMillis(),
                hasKey = true
            )
            customStore.save(module, key)
            refreshCustom()
            appendStep("Модуль создан: ${setup.method} ${outcome.urlTemplate}, ${AutoSetup.authTitle(setup)}")
            val key2 = if (outcome.strict) "Ключ принят сервисом" else "Адрес найден, но ключ отклонён (${outcome.code})"
            return ConnectionUi(
                done = true, ok = outcome.strict,
                title = if (outcome.strict) "«${module.title}» подключён" else "«${module.title}»: нужен рабочий ключ",
                detail = "$key2. Модуль включён — ответы попадут в отчёт с пометкой источника.",
                lines = _state.value.connection?.lines.orEmpty()
            )
        }
        return ConnectionUi(
            done = true, ok = false, title = "«$title» не подобрался автоматически",
            detail = "${outcome.detail}. Добавьте модуль вручную в разделе «Свои модули»: адрес, ключ и правила разбора.",
            lines = _state.value.connection?.lines.orEmpty()
        )
    }

    // ------------------------------------------------------- починка модулей

    /**
     * Разбирает последний отчёт диагностики и лечит, что лечится без человека:
     * переносит ключ из своего модуля-дубликата во встроенный, заново подбирает
     * адрес и способ передачи ключа, перепроверяет отклонённые ключи.
     * В конце собирает свежий отчёт — видно, что изменилось.
     */
    fun repairModules() {
        val blocks = _state.value.diagBlocks
        if (blocks.isEmpty()) {
            notify("Сначала выполните диагностику — исправлять нечего")
            return
        }
        if (_state.value.repairing) return
        _state.update { it.copy(repairing = true, repairLog = emptyList()) }
        viewModelScope.launch {
            var fixed = 0
            var checked = 0
            for (block in blocks) {
                runCatching {
                    if (block.kind.startsWith("пользовательский")) {
                        val module = customStore.list().firstOrNull { it.title == block.title }
                            ?: return@runCatching
                        when (val step = RepairPlan.forCustom(module, block.code)) {
                            is RepairStep.Migrate -> {
                                checked++
                                if (migrateToBuiltIn(module, step.providerId, step.reason)) fixed++
                            }
                            is RepairStep.Rediscover -> {
                                checked++
                                if (rediscoverCustom(module, step.reason)) fixed++
                            }
                            else -> appendRepair("«${module.title}»: ${step.reason()}")
                        }
                    } else {
                        val provider = _state.value.providers
                            .firstOrNull { it.spec.title == block.title } ?: return@runCatching
                        val id = provider.spec.id
                        val state = keys.state(id)
                        when (val step = RepairPlan.forBuiltIn(id, state, block.facts)) {
                            is RepairStep.Recheck -> {
                                checked++
                                val check = validator.check(id)
                                refreshProviders()
                                if (check.state == KeyState.VALID) {
                                    fixed++
                                    appendRepair("«${provider.spec.title}»: ключ принят заново — ${check.detail}")
                                } else {
                                    appendRepair("«${provider.spec.title}»: ${check.detail}")
                                }
                            }
                            is RepairStep.Manual -> appendRepair("«${provider.spec.title}»: ${step.reason}")
                            else -> Unit
                        }
                    }
                }.onFailure { appendRepair("${block.title}: ${it.message ?: "ошибка починки"}") }
            }
            _state.update {
                it.copy(repairing = false, toast = "Проверено модулей: $checked, исправлено: $fixed")
            }
            runDiagnostics()
        }
    }

    fun clearRepairLog() = _state.update { it.copy(repairLog = emptyList()) }

    private fun appendRepair(line: String) {
        _state.update { s -> s.copy(repairLog = s.repairLog + DiagMask.text(line)) }
    }

    private fun RepairStep.reason(): String = when (this) {
        is RepairStep.Migrate -> reason
        is RepairStep.Rediscover -> reason
        is RepairStep.Recheck -> reason
        is RepairStep.Manual -> reason
    }

    /** Ключ из своего модуля → встроенный; дубликат выключается только после успешной проверки. */
    private suspend fun migrateToBuiltIn(module: CustomModule, providerId: String, reason: String): Boolean {
        val spec = ProviderCatalog.byId(providerId) ?: return false
        val key = customStore.key(module.id)
        if (key.isBlank()) {
            appendRepair("«${module.title}»: $reason, но ключ не сохранён — добавьте его в «${spec.title}» вручную")
            return false
        }
        keys.saveKey(providerId, key)
        keys.setEnabled(providerId, true)
        refreshProviders()
        val check = validator.check(providerId)
        refreshProviders()
        return if (check.state == KeyState.VALID) {
            customStore.setEnabled(module.id, false)
            refreshCustom()
            appendRepair("«${module.title}» → встроенный «${spec.title}»: ключ перенесён и принят, дубликат выключен")
            true
        } else {
            appendRepair("«${module.title}» → «${spec.title}»: ключ отклонён (${check.detail})")
            false
        }
    }

    /** Повторный автоподбор запроса для своего модуля с сохранением найденного варианта. */
    private suspend fun rediscoverCustom(module: CustomModule, reason: String): Boolean {
        val host = CustomModuleSpec.hostOf(module.urlTemplate)
        val link = SourceLink.parse("https://$host") ?: return false
        val key = customStore.key(module.id)
        if (key.isBlank()) {
            appendRepair("«${module.title}»: $reason, но ключ не сохранён — перенастроить нельзя")
            return false
        }
        val hints = runCatching { AutoSetup.fetchHints(link) }.getOrDefault(DocHints())
        val outcome = AutoSetup.detect(link, key, hints)
        val setup = outcome.setup
        if (outcome.fixed && setup != null && !outcome.urlTemplate.isNullOrBlank()) {
            customStore.save(
                module.copy(
                    urlTemplate = outcome.urlTemplate,
                    auth = setup.auth,
                    method = setup.method,
                    headerName = setup.headerName.ifBlank { module.headerName },
                    paramName = setup.paramName.ifBlank { module.paramName },
                    enabled = true
                ),
                key
            )
            refreshCustom()
            appendRepair("«${module.title}»: $reason → найден рабочий вариант ${setup.method} ${outcome.urlTemplate} (${AutoSetup.authTitle(setup)})")
            return true
        }
        appendRepair("«${module.title}»: $reason, но рабочий вариант не найден — ${outcome.detail}")
        return false
    }

    // ------------------------------------------------------------- диагностика

    /**
     * Собирает отчёт по всем включённым модулям: что запрошено, что вернулось,
     * как разобрано. Отчёт копируется в буфер и передаётся разработчику.
     */
    fun runDiagnostics(queryOverride: String? = null) {
        if (_state.value.diagRunning) return
        _state.update { it.copy(diagRunning = true, diagReport = null) }
        viewModelScope.launch {
            val query = resolveDiagQuery(queryOverride)
            val startedAt = System.currentTimeMillis()
            val blocks = collectBlocks(query)
            _state.update {
                it.copy(
                    diagRunning = false,
                    diagReport = renderDiag(query, blocks, startedAt),
                    diagBlocks = blocks
                )
            }
        }
    }

    private fun resolveDiagQuery(queryOverride: String?): String =
        queryOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: _state.value.query.trim().takeIf { it.isNotBlank() }
            ?: "osint"

    private fun renderDiag(query: String, blocks: List<DiagBlock>, startedAt: Long): String =
        if (blocks.isEmpty()) {
            "JB OSINT · диагностика\nВключённых модулей нет: включите хотя бы один модуль и повторите."
        } else {
            DiagReport.render(
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                android = "${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}",
                query = query,
                blocks = blocks,
                startedAt = startedAt
            )
        }

    /** Опрос включённых модулей. Возвращает блоки — их используют и отчёт, и ИИ-ассистент. */
    private suspend fun collectBlocks(query: String): List<DiagBlock> = withContext(Dispatchers.IO) {
        val blocks = ArrayList<DiagBlock>()
        var index = 0

        _state.value.providers.filter { it.enabled }.forEach { p ->
            index++
            val spec = p.spec
            val hasKey = spec.keyless || keys.key(spec.id).isNotBlank()
            val check = if (!hasKey) {
                KeyCheck(KeyState.EMPTY, "ключ не задан — модуль не сможет обратиться к сервису")
            } else {
                runCatching { validator.check(spec.id) }
                    .getOrElse { KeyCheck(KeyState.INVALID, "ошибка проверки: ${it.message ?: it.javaClass.simpleName}") }
            }
            // Каждому API — свой тип данных: общий запрос «osint» для половины
            // модулей заведомо пуст и выглядит как поломка.
            val probeQuery = DiagSamples.forProvider(spec.id) ?: query
            val t0 = System.currentTimeMillis()
            val facts = if (check.state == KeyState.VALID) {
                runCatching { Connectors.byId(spec.id)?.collect(probeQuery, keys) ?: emptyList() }
                    .getOrElse { emptyList() }
            } else emptyList()
            val elapsed = System.currentTimeMillis() - t0
            val first = facts.firstOrNull()
            val sample = first?.let { f ->
                buildString {
                    append("${f.kind}: ${f.value.take(120)}")
                    if (f.attributes.isNotEmpty()) {
                        append(" · ")
                        append(f.attributes.entries.take(3).joinToString("; ") { "${it.key}: ${it.value.take(60)}" })
                    }
                }
            }.orEmpty()
            val accepted = DiagSamples.acceptedFor(spec.id)
            val advice = when {
                check.state == KeyState.VALID && facts.isNotEmpty() -> "работает"
                check.state == KeyState.VALID && accepted != null ->
                    "ключ принят, фактов 0: по запросу «$probeQuery» ничего не найдено; модуль принимает $accepted"
                check.state == KeyState.VALID ->
                    "ключ принят, но фактов 0: по запросу «$probeQuery» сервис ничего не вернул"
                check.state == KeyState.OFFLINE -> "нет связи с сервисом (сеть или блокировка)"
                check.state == KeyState.EMPTY -> "ключ не задан"
                else -> "не работает: ${check.detail}"
            }
            blocks += DiagBlock(
                index = index,
                title = spec.title,
                kind = "встроенный модуль",
                enabled = p.enabled,
                endpoint = "адрес задан в коде модуля",
                method = "GET",
                code = if (check.state == KeyState.VALID) 200 else 0,
                elapsedMs = elapsed,
                bytes = 0,
                checkDetail = "${check.state.title}: ${check.detail} · документация: ${spec.docsUrl}",
                structure = "",
                facts = facts.size,
                fields = first?.attributes?.keys?.joinToString(", ").orEmpty(),
                sample = sample,
                error = if (check.state == KeyState.VALID) null else check.detail,
                advice = advice,
                testQuery = probeQuery.takeIf { it != query },
                moduleId = spec.id,
                keyState = check.state.name
            )
        }

        _state.value.customModules.filter { it.enabled }.forEach { module ->
            index++
            val key = customStore.key(module.id)
            val url = CustomModuleSpec.buildUrl(module, key, query)
            val t0 = System.currentTimeMillis()
            val response = runCatching {
                Net.fetch(
                    url, CustomModuleSpec.headers(module, key), attempts = 1,
                    method = module.method, body = CustomModuleSpec.body(module, key)
                )
            }.getOrNull()
            val elapsed = System.currentTimeMillis() - t0
            val body = response?.body.orEmpty()
            val parsed = if (response != null && response.ok) parseCustom(body, module, query) else null
            val maskedSample = if (body.isBlank()) "" else DiagMask.json(body).let {
                if (it.length > 1400) it.take(1400) + "\n… обрезано (${body.length} байт)" else it
            }
            val advice = when {
                response == null -> "нет ответа: сеть, таймаут или неверный адрес"
                response.code !in 200..299 -> "эндпоинт ответил ${response.code}: проверьте адрес, ключ и способ передачи «${module.auth.title}»"
                parsed == null -> "ответ не JSON — модуль разбирает только JSON API"
                parsed.isEmpty() -> "ответ получен, но поля не распознаны: задайте правила «JSON-путь → поле» (структура выше подскажет путь)"
                else -> "работает"
            }
            blocks += DiagBlock(
                index = index,
                title = module.title,
                kind = "пользовательский модуль",
                enabled = module.enabled,
                endpoint = DiagMask.url(url),
                method = module.method,
                code = response?.code ?: 0,
                elapsedMs = elapsed,
                bytes = body.length,
                checkDetail = "авторизация: ${module.auth.title}",
                structure = if (body.isBlank()) "" else JsonShape.describe(body),
                facts = parsed?.size ?: 0,
                fields = module.fields.joinToString(", ") { if (it.path.isBlank()) it.field.kind else "${it.path} → ${it.field.kind}" },
                sample = maskedSample,
                error = when {
                    response == null -> "запрос не выполнен"
                    response.code !in 200..299 -> "HTTP ${response.code}"
                    parsed == null -> "тело ответа не является JSON"
                    else -> null
                },
                advice = advice,
                moduleId = module.id
            )
        }

        blocks
    }

    fun clearDiagnostics() = _state.update { it.copy(diagReport = null) }

    // ------------------------------------------------ ИИ-ассистент модулей

    /**
     * Проверка модулей с экономией токенов:
     * 1) локальный разбор кодов и структуры ответа — без модели;
     * 2) кэш рекомендаций по отпечатку проблемы — без модели;
     * 3) оставшееся уходит в модель одной пачкой (до пяти модулей) с коротким промтом.
     */
    fun runModuleAssistant(queryOverride: String? = null) {
        if (_state.value.moduleBusy) return
        _state.update { it.copy(moduleBusy = true, moduleStage = "Проверяем модули…", moduleError = null) }
        viewModelScope.launch {
            val query = resolveDiagQuery(queryOverride)
            val aiReady = assistantStore.configured()
            val blocks = collectBlocks(query)
            if (blocks.isEmpty()) {
                _state.update {
                    it.copy(
                        moduleBusy = false,
                        moduleStage = "",
                        moduleAdvices = emptyList(),
                        moduleError = "Включённых модулей нет: включите хотя бы один на экране «Ключи»."
                    )
                }
                return@launch
            }

            val plan = ModulePlanner.plan(blocks, moduleAssistant)
            plan.advices.filter { it.origin == AdviceOrigin.CACHE }
                .forEach { moduleAssistant.touch(it.fingerprint) }
            moduleAssistant.recordLocal(plan.localCount)
            _state.update {
                it.copy(
                    moduleAdvices = plan.advices,
                    moduleQuery = query,
                    moduleStats = moduleAssistant.stats(),
                    moduleJournal = moduleAssistant.journal()
                )
            }

            if (!plan.needAi) {
                _state.update {
                    it.copy(
                        moduleBusy = false,
                        moduleStage = "",
                        moduleRunAt = System.currentTimeMillis()
                    )
                }
                return@launch
            }
            if (!aiReady) {
                _state.update {
                    it.copy(
                        moduleBusy = false,
                        moduleStage = "",
                        moduleRunAt = System.currentTimeMillis(),
                        moduleError = "ИИ не настроен — выше локальные рекомендации. Подключите поставщика " +
                            "в настройках, чтобы разбирать нестандартные ответы и правила полей."
                    )
                }
                return@launch
            }

            val answers = HashMap<Int, ModuleAnswerParser.Answer>()
            var tokensIn = 0
            var tokensOut = 0
            var failure: String? = null
            val provider = assistantStore.provider
            val model = assistantStore.model

            plan.batches.forEachIndexed { index, batch ->
                _state.update { it.copy(moduleStage = "ИИ разбирает модули (${index + 1} из ${plan.batches.size})…") }
                val prompt = ModulePrompts.batch(batch)
                val outcome = assistantService.chat(AssistantRole.MODULE_DOCTOR, prompt)
                outcome.onSuccess { raw ->
                    val parsed = ModuleAnswerParser.parse(raw, batch.map { it.number }.toSet())
                    val spentIn = TokenEstimate.of(prompt.length)
                    val spentOut = TokenEstimate.of(raw.length)
                    if (parsed.isEmpty()) {
                        moduleAssistant.recordCall(
                            AiCallEntry(
                                at = System.currentTimeMillis(),
                                purpose = "diagnose_modules",
                                modules = batch.map { it.title },
                                promptChars = prompt.length,
                                answerChars = raw.length,
                                tokensIn = spentIn,
                                tokensOut = spentOut,
                                provider = provider,
                                model = model,
                                ok = false,
                                note = "ответ не разобран"
                            )
                        )
                        failure = "ответ модели не разобран"
                    } else {
                        answers.putAll(parsed)
                        tokensIn += spentIn
                        tokensOut += spentOut
                        moduleAssistant.recordCall(
                            AiCallEntry(
                                at = System.currentTimeMillis(),
                                purpose = "diagnose_modules",
                                modules = batch.map { it.title },
                                promptChars = prompt.length,
                                answerChars = raw.length,
                                tokensIn = spentIn,
                                tokensOut = spentOut,
                                provider = provider,
                                model = model,
                                ok = true
                            )
                        )
                    }
                }.onFailure { error ->
                    failure = error.message ?: "ошибка запроса к ИИ"
                    moduleAssistant.recordCall(
                        AiCallEntry(
                            at = System.currentTimeMillis(),
                            purpose = "diagnose_modules",
                            modules = batch.map { it.title },
                            promptChars = prompt.length,
                            answerChars = 0,
                            tokensIn = TokenEstimate.of(prompt.length),
                            tokensOut = 0,
                            provider = provider,
                            model = model,
                            ok = false,
                            note = (error.message ?: "").take(200)
                        )
                    )
                }
            }

            if (answers.isNotEmpty()) {
                ModuleApply.cacheEntries(plan, answers, provider, model, tokensIn + tokensOut)
                    .forEach { moduleAssistant.put(it) }
            }
            val merged = ModuleApply.apply(
                ModulePlan(plan.advices, emptyList(), plan.cacheHits, plan.localCount, plan.aiItems),
                answers,
                provider,
                model
            )
            _state.update {
                it.copy(
                    moduleBusy = false,
                    moduleStage = "",
                    moduleAdvices = merged,
                    moduleStats = moduleAssistant.stats(),
                    moduleJournal = moduleAssistant.journal(),
                    moduleRunAt = System.currentTimeMillis(),
                    moduleError = if (answers.isEmpty()) "ИИ недоступен: ${failure ?: "нет ответа"}. Оставлены локальные рекомендации." else null
                )
            }
        }
    }

    /** Экспорт отчёта по модулям: JSON, Excel, PDF или HTML. */
    fun exportModuleReport(format: ReportExporter.Format) {
        val current = _state.value
        if (current.moduleAdvices.isEmpty()) {
            _state.update { it.copy(toast = "Сначала запустите разбор модулей") }
            return
        }
        if (current.moduleExporting) return
        _state.update { it.copy(moduleExporting = true) }
        viewModelScope.launch {
            val data = ModuleReportBuilder.data(
                query = current.moduleQuery.ifBlank { current.query },
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                android = "${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}",
                advices = current.moduleAdvices,
                stats = moduleAssistant.stats()
            )
            val outcome = runCatching { exporter.moduleReport(data, format) }
            _state.update { it.copy(moduleExporting = false) }
            outcome.onSuccess { exported ->
                _state.update {
                    it.copy(
                        toast = if (exported.publicFolder != null)
                            "${format.label} сохранён: ${exported.publicFolder}"
                        else "${format.label} готов — используйте «Поделиться»"
                    )
                }
                _shareEvents.emit(ShareRequest(exported.uri, exported.mime, exported.fileName))
            }.onFailure { error ->
                _state.update { it.copy(toast = "Ошибка экспорта: ${error.message ?: "неизвестная"}") }
            }
        }
    }

    fun refreshModuleJournal() = _state.update {
        it.copy(moduleJournal = moduleAssistant.journal(), moduleStats = moduleAssistant.stats())
    }

    fun clearModuleCache() {
        moduleAssistant.clearCache()
        _state.update {
            it.copy(
                moduleStats = moduleAssistant.stats(),
                toast = "Кэш рекомендаций очищен — следующий разбор снова спросит ИИ"
            )
        }
    }

    fun clearModuleJournal() {
        moduleAssistant.clearJournal()
        _state.update {
            it.copy(
                moduleJournal = emptyList(),
                moduleStats = moduleAssistant.stats(),
                toast = "Журнал обращений к ИИ очищен"
            )
        }
    }

    fun clearModuleAssistant() = _state.update {
        it.copy(moduleAdvices = emptyList(), moduleError = null, moduleQuery = "", moduleStage = "")
    }

    fun openModules() {
        refreshModuleJournal()
        navigate(Screen.MODULES)
    }

    private fun parseCustom(body: String, module: CustomModule, query: String): List<RawFact>? {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONObject().put("items", JSONArray(body)) }.getOrNull()
            ?: return null
        return runCatching {
            CustomResponseParser.parse(obj, query, CustomModuleSpec.SOURCE_ID, module.fields)
        }.getOrNull()
    }

    // --------------------------------------------------- пользовательские модули

    fun saveCustomModule(
        id: String,
        title: String,
        urlTemplate: String,
        auth: CustomAuth,
        headerName: String,
        paramName: String,
        key: String?,
        fields: List<FieldRule> = emptyList(),
        method: String = "GET"
    ) {
        val error = CustomModuleSpec.validate(title, urlTemplate)
            ?: CustomModuleSpec.validateRules(fields)
        if (error != null) {
            _state.update { it.copy(toast = error) }
            return
        }
        val existing = if (id.isNotBlank()) customStore.get(id) else null
        val module = CustomModule(
            id = id.ifBlank { java.util.UUID.randomUUID().toString() },
            title = title.trim(),
            urlTemplate = urlTemplate.trim(),
            auth = auth,
            method = method.ifBlank { "GET" },
            headerName = headerName.trim(),
            paramName = paramName.trim(),
            enabled = existing?.enabled ?: true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            fields = fields
        )
        customStore.save(module, key)
        refreshCustom()
        _state.update {
            it.copy(
                toast = when {
                    auth == CustomAuth.AUTO -> "Проверяется…"
                    existing == null -> "Модуль добавлен"
                    else -> "Модуль обновлён"
                }
            )
        }
        // ТЗ: при добавлении модуля сразу проверяем связь; при ошибке модуль отключается.
        // Если способ подключения не выбран — сначала автоподбор метода и передачи ключа.
        checkCustomModule(module.id)
    }

    fun setCustomModuleEnabled(id: String, enabled: Boolean) {
        customStore.setEnabled(id, enabled)
        refreshCustom()
    }

    fun deleteCustomModule(id: String) {
        customStore.delete(id)
        refreshCustom()
        _state.update { it.copy(toast = "Модуль удалён") }
    }

    fun checkCustomModule(id: String) {
        val module = customStore.get(id) ?: return
        if (_state.value.customChecking.contains(id)) return
        _state.update { it.copy(customChecking = it.customChecking + id, toast = "Проверяется…") }
        viewModelScope.launch {
            val pair: Pair<KeyCheck, CustomConnectionProbe.Setup?> = if (module.auth == CustomAuth.AUTO) {
                autoConfigure(module)
            } else {
                customChecker.check(module) to null
            }
            val (result, resolved) = pair
            val failed = result.state == KeyState.INVALID || result.state == KeyState.OFFLINE ||
                result.state == KeyState.EMPTY
            if (failed && module.enabled) customStore.setEnabled(id, false)
            _state.update {
                it.copy(
                    customChecking = it.customChecking - id,
                    customChecks = it.customChecks + (id to result),
                    customModules = customStore.list(),
                    toast = when {
                        failed -> result.detail + " — модуль отключён"
                        resolved != null -> "Модуль настроен успешно: ${resolved.method}, ${resolved.auth.title.lowercase()}"
                        else -> result.detail
                    }
                )
            }
        }
    }

    /**
     * Автоподбор параметров подключения: метод GET/POST и способ передачи ключа.
     * Найденная комбинация сохраняется в модуль, чтобы поиск шёл уже без перебора.
     */
    private suspend fun autoConfigure(module: CustomModule): Pair<KeyCheck, CustomConnectionProbe.Setup?> {
        val probe = CustomConnectionProbe().detect(module, customStore.key(module.id))
        val setup = probe.setup
        if (setup != null) {
            customStore.save(
                module.copy(
                    auth = setup.auth,
                    method = setup.method,
                    headerName = setup.headerName,
                    paramName = setup.paramName
                ),
                null
            )
        }
        val state = when {
            probe.ok -> KeyState.VALID
            probe.detail == "Нет соединения" -> KeyState.OFFLINE
            probe.setup == null -> KeyState.INVALID
            else -> KeyState.INVALID
        }
        return KeyCheck(state, probe.detail) to setup
    }

    // -------------------------------------------------------------- настройки

    fun setAnalyst(value: String) {
        val trimmed = value.take(80)
        settings.analyst = trimmed
        _state.update { it.copy(analyst = trimmed) }
    }

    fun setDemoMode(value: Boolean) {
        settings.demoMode = value
        _state.update { it.copy(demoMode = value) }
    }

    fun setSecureScreen(value: Boolean) {
        settings.secureScreen = value
        _state.update { it.copy(secureScreen = value) }
    }

    fun setAutoWipeDays(days: Int) {
        settings.autoWipeDays = days
        _state.update { it.copy(autoWipeDays = days) }
    }

    // ------------------------------------------------------------ локальный бэкенд

    fun setTermuxEnabled(value: Boolean) {
        settings.termuxEnabled = value
        _state.update { it.copy(termux = it.termux.copy(enabled = value)) }
    }

    fun setTermuxHome(raw: String) {
        val home = TermuxSpec.normalizeHome(raw)
        settings.termuxHome = home
        _state.update { it.copy(termux = it.termux.copy(home = home), toast = "Путь Termux: $home") }
    }

    /** @return текст ошибки или null, если скрипт добавлен в белый список. */
    fun addTermuxScript(raw: String): String? {
        val name = TermuxSpec.sanitizeScriptName(raw)
            ?: return "Имя скрипта: латиница, цифры, точка, дефис; файл .py или .sh"
        val next = (settings.termuxTrusted + name).distinct()
        settings.termuxTrusted = next
        _state.update {
            it.copy(termux = it.termux.copy(trusted = next), toast = "Скрипт $name разрешён")
        }
        return null
    }

    fun removeTermuxScript(name: String) {
        val next = settings.termuxTrusted - name
        settings.termuxTrusted = next
        _state.update { it.copy(termux = it.termux.copy(trusted = next)) }
    }

    fun termuxSetupScript(): String = TermuxSpec.setupScript(settings.termuxHome)

    /** Диагностика: локальные проверки сразу, остальное — ответом из Termux. */
    fun runTermuxDiagnostics() {
        val app = getApplication<Application>()
        val local = TermuxSpec.localChecks(app)
        val blocked = local.firstOrNull { !it.ok }
        if (blocked != null) {
            _state.update {
                it.copy(termux = it.termux.copy(checking = false, checks = local, note = blocked.detail))
            }
            return
        }
        val requestId = "diag-${System.currentTimeMillis()}"
        termuxDiagRequest = requestId
        _state.update {
            it.copy(termux = it.termux.copy(checking = true, checks = local, note = null))
        }
        val started = TermuxSpec.startDiagnostics(app, settings.termuxHome, requestId)
        if (started.isFailure) {
            termuxDiagRequest = null
            _state.update {
                it.copy(
                    termux = it.termux.copy(
                        checking = false,
                        note = started.exceptionOrNull()?.message ?: "Termux не запустился"
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            delay(20_000)
            if (termuxDiagRequest == requestId) {
                termuxDiagRequest = null
                _state.update {
                    it.copy(
                        termux = it.termux.copy(
                            checking = false,
                            note = "Termux не ответил за 20 с: проверьте allow-external-apps=true и разрешение RUN_COMMAND"
                        )
                    )
                }
            }
        }
    }

    /** Запуск только доверенного скрипта; произвольный код из UI не выполняется. */
    fun runTermuxScript(name: String) {
        val app = getApplication<Application>()
        val current = _state.value.termux
        if (!TermuxSpec.isTrusted(current.trusted, name)) {
            _state.update { it.copy(toast = "Скрипт не в белом списке") }
            return
        }
        val requestId = "run-${System.currentTimeMillis()}"
        termuxScriptRequest = requestId
        val started = TermuxSpec.startScript(
            app,
            current.home,
            name,
            background = false,
            requestId = requestId
        )
        if (started.isFailure) {
            termuxScriptRequest = null
            _state.update {
                it.copy(
                    termux = it.termux.copy(
                        note = started.exceptionOrNull()?.message ?: "Termux не запустился"
                    )
                )
            }
            return
        }
        _state.update {
            it.copy(termux = it.termux.copy(running = name, note = "Открываю Termux: $name"))
        }
    }

    private fun onTermuxResult(result: ru.jbosint.osint.data.TermuxResult) {
        when (result.requestId) {
            termuxDiagRequest -> {
                termuxDiagRequest = null
                val parsed = if (result.stdout.isBlank()) {
                    emptyList()
                } else {
                    TermuxSpec.parseDiagnostics(result.stdout)
                }
                val note = when {
                    result.error.isNotBlank() -> result.error
                    result.exitCode != 0 -> "Termux вернул код ${result.exitCode}: ${result.stderr.take(300)}".trim()
                    else -> "Диагностика завершена"
                }
                _state.update { s ->
                    val local = s.termux.checks.filter { it.id == "termux" || it.id == "permission" }
                    s.copy(
                        termux = s.termux.copy(
                            checking = false,
                            checks = if (parsed.isEmpty()) s.termux.checks else local + parsed,
                            note = note,
                            lastRunAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            termuxScriptRequest -> {
                termuxScriptRequest = null
                val note = when {
                    result.error.isNotBlank() -> result.error
                    result.stdout.isNotBlank() -> result.stdout.takeLast(500)
                    result.stderr.isNotBlank() -> result.stderr.takeLast(500)
                    else -> "Скрипт завершён (код ${result.exitCode})"
                }
                _state.update {
                    it.copy(
                        termux = it.termux.copy(
                            running = null,
                            note = note,
                            lastRunAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ серверный режим

    fun setServerEnabled(value: Boolean) {
        settings.serverEnabled = value
        _state.update { it.copy(server = it.server.copy(enabled = value)) }
        if (value) checkServer()
    }

    fun setServerUrl(raw: String) {
        val url = Remote.normalizeUrl(raw)
        if (url == null) {
            _state.update {
                it.copy(toast = "Адрес сервера: только https, например https://api.example.com")
            }
            return
        }
        settings.serverUrl = url
        _state.update { it.copy(server = it.server.copy(url = url, note = null)) }
        checkServer()
    }

    fun saveServerKey(raw: String) {
        val key = raw.trim()
        if (key.length < 8) {
            _state.update { it.copy(toast = "Ключ сервера слишком короткий") }
            return
        }
        secure.put("server_key", key)
        _state.update {
            it.copy(server = it.server.copy(keyed = true), toast = "Ключ сервера сохранён")
        }
    }

    fun clearServerKey() {
        secure.remove("server_key")
        _state.update {
            it.copy(server = it.server.copy(keyed = false, online = false, note = "Ключ удалён"))
        }
    }

    fun checkServer() {
        val server = _state.value.server
        if (!server.enabled) {
            _state.update { it.copy(server = it.server.copy(note = "Серверный режим выключен")) }
            return
        }
        if (server.url.isBlank()) {
            _state.update { it.copy(server = it.server.copy(note = "Укажите адрес сервера")) }
            return
        }
        _state.update { it.copy(server = it.server.copy(checking = true, note = null)) }
        viewModelScope.launch {
            val current = _state.value.server
            val health = Remote.health(current.url, secure.get("server_key").orEmpty())
            _state.update {
                it.copy(
                    server = it.server.copy(
                        checking = false,
                        online = health.online,
                        note = if (health.online) health.detail
                        else "Режим ограниченной функциональности. Выполнено локально. ${health.detail}"
                    )
                )
            }
        }
    }

    /** Серверный RAE: URL + цель. При ошибке остаётся локальный путь через Termux. */
    fun raeGenerateRemote(url: String, prompt: String) {
        val server = _state.value.server
        if (!server.enabled || server.url.isBlank()) {
            _state.update { it.copy(raeServer = RaeServerUi(error = "Серверный режим выключен")) }
            return
        }
        val target = Remote.normalizeUrl(url)
        if (target == null) {
            _state.update { it.copy(raeServer = RaeServerUi(error = "Адрес сайта: только https")) }
            return
        }
        _state.update { it.copy(raeServer = RaeServerUi(busy = true)) }
        viewModelScope.launch {
            val result = Remote.raeGenerate(
                server.url,
                secure.get("server_key").orEmpty(),
                target,
                prompt
            )
            _state.update { s ->
                val ui = result.fold(
                    onSuccess = { RaeServerUi(result = it) },
                    onFailure = {
                        RaeServerUi(
                            error = (it.message ?: "Ошибка сервера") +
                                ". Локальный режим доступен: Termux → «Из трафика»."
                        )
                    }
                )
                s.copy(
                    raeServer = ui,
                    server = s.server.copy(online = ui.result != null || s.server.online)
                )
            }
        }
    }

    fun clearRaeServer() {
        _state.update { it.copy(raeServer = RaeServerUi()) }
    }

    // --------------------------------------- режим работы и резервные копии ключей

    /** Ищем копию только когда ключей нет: иначе предложение бессмысленно. */
    private fun detectBackupOffer() {
        if (keys.activeCount() > 0 || adminStore.mode == WorkMode.USER) return
        val file = runCatching { backupService.latestBackup() }.getOrNull() ?: return
        _state.update {
            it.copy(
                restoreOffer = RestoreOffer(
                    name = file.name,
                    path = file.absolutePath,
                    config = file.name == KeyBackupService.CONFIG_FILE
                )
            )
        }
    }

    fun dismissRestoreOffer() = _state.update { it.copy(restoreOffer = null, backupNote = null) }

    /** Создать или сменить мастер-пароль администратора. Возвращает текст ошибки. */
    fun adminSetPassword(password: String, current: String? = null): String? {
        if (current != null && adminStore.hasPassword() && !adminStore.verify(current)) {
            return lockMessage() ?: "Текущий пароль неверен"
        }
        AdminPolicy.validate(password)?.let { return it }
        if (!adminStore.setPassword(password)) return "Не удалось сохранить пароль"
        refreshAccessState()
        _state.update { it.copy(adminUnlocked = true, toast = "Мастер-пароль сохранён") }
        return null
    }

    /** Проверка пароля с паузой после пяти ошибок. Возвращает текст ошибки. */
    fun adminUnlock(password: String): String? {
        if (!adminStore.hasPassword()) {
            _state.update { it.copy(adminUnlocked = true) }
            return null
        }
        lockMessage()?.let { return it }
        if (!adminStore.verify(password)) {
            refreshAccessState()
            return lockMessage()
                ?: "Неверный пароль. Осталось попыток: ${(AdminPolicy.MAX_FAILS - adminStore.fails()).coerceAtLeast(0)}"
        }
        refreshAccessState()
        _state.update { it.copy(adminUnlocked = true, toast = "Режим администратора разблокирован") }
        return null
    }

    /** Разблокировка после успешной биометрии (сам диалог показывает экран). */
    fun adminUnlockTrusted() {
        if (!adminStore.hasPassword() || !adminStore.biometric) return
        refreshAccessState()
        _state.update { it.copy(adminUnlocked = true, toast = "Разблокировано биометрией") }
    }

    fun adminLock() {
        if (!adminStore.hasPassword()) return
        _state.update { it.copy(adminUnlocked = false) }
    }

    fun setAdminBiometric(enabled: Boolean) {
        adminStore.biometric = enabled
        _state.update { it.copy(adminBiometric = enabled) }
    }

    /** Переключение режима: вход в «Администратор» всегда требует пароль. */
    fun switchMode(target: WorkMode, password: String?): String? {
        if (target == WorkMode.USER) {
            adminStore.mode = WorkMode.USER
            refreshAccessState()
            _state.update {
                it.copy(
                    workMode = WorkMode.USER.id,
                    screen = Screen.HOME,
                    adminUnlocked = !adminStore.hasPassword(),
                    toast = "Режим «Пользователь»: ключи и настройки заблокированы"
                )
            }
            return null
        }
        if (!adminStore.hasPassword()) return "Сначала задайте мастер-пароль администратора"
        adminUnlock(password.orEmpty())?.let { return it }
        adminStore.mode = WorkMode.ADMIN
        refreshAccessState()
        _state.update { it.copy(workMode = WorkMode.ADMIN.id, toast = "Режим «Администратор»") }
        return null
    }

    /** Резервный код показывается один раз — вызывающая сторона обязана его сохранить. */
    fun generateRecoveryCode(): String? {
        if (!adminStore.hasPassword()) return null
        val code = adminStore.generateRecoveryCode()
        _state.update { it.copy(adminRecovery = true) }
        return code
    }

    fun resetAdminWithRecovery(code: String, newPassword: String): String? {
        AdminPolicy.validate(newPassword)?.let { return it }
        if (!adminStore.resetWithRecovery(code, newPassword)) return "Резервный код не подошёл"
        refreshAccessState()
        _state.update { it.copy(adminUnlocked = true, toast = "Пароль сброшен резервным кодом") }
        return null
    }

    /** Резервная копия всех ключей: jb_osint_keys.json. */
    fun exportKeys(password: String) {
        if (_state.value.backupBusy) return
        masterError(password)?.let {
            _state.update { state -> state.copy(toast = it) }
            return
        }
        val count = runCatching { backupService.payloadForBackup(false, BackupPayload.MODE_ADMIN).keyCount }
            .getOrDefault(0)
        if (count == 0) {
            _state.update { it.copy(toast = "Сохранять нечего: ключи не заданы") }
            return
        }
        _state.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            val outcome = runCatching { backupService.exportKeys(password.toCharArray()) }
            outcome.onSuccess { file ->
                val saved = runCatching {
                    exporter.save(file.name, file.bytes, "application/json")
                }.getOrNull()
                _state.update {
                    it.copy(
                        backupBusy = false,
                        backupNote = "Копия от ${stamp()} · ключей: $count",
                        toast = if (saved?.publicFolder != null)
                            "Копия сохранена: ${saved.publicFolder}/${file.name}"
                        else "Копия готова — используйте «Поделиться»"
                    )
                }
                saved?.let { _shareEvents.emit(ShareRequest(it.uri, it.mime, it.fileName)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(backupBusy = false, toast = "Не удалось сохранить копию: ${error.message ?: "ошибка"}")
                }
            }
        }
    }

    /** Пользовательская сборка: активные модули и их ключи, после импорта — режим «Пользователь». */
    fun createUserBuild(password: String) {
        if (_state.value.backupBusy) return
        masterError(password)?.let { error ->
            _state.update { state -> state.copy(toast = error) }
            return
        }
        val payload = runCatching { backupService.payloadForBackup(true, BackupPayload.MODE_USER) }.getOrNull()
        if (payload == null || payload.keyCount == 0) {
            _state.update { it.copy(toast = "Нет включённых модулей с ключами для сборки") }
            return
        }
        _state.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            runCatching { backupService.createUserBuild(password.toCharArray()) }
                .onSuccess { file ->
                    val saved = runCatching {
                        exporter.save(file.name, file.bytes, KeyBackupService.MIME)
                    }.getOrNull()
                    _state.update {
                        it.copy(
                            backupBusy = false,
                            backupNote = "Сборка от ${stamp()} · модулей: ${payload.custom.size + payload.providers.size}",
                            toast = "Сборка готова: ${file.name} — передайте файл вместе с приложением"
                        )
                    }
                    saved?.let { _shareEvents.emit(ShareRequest(it.uri, it.mime, it.fileName)) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(backupBusy = false, toast = "Не удалось собрать: ${error.message ?: "ошибка"}")
                    }
                }
        }
    }

    /** Импорт из пикера: читаем содержимое и восстанавливаем. */
    fun importKeysFromUri(uri: Uri, password: String): String? {
        val bytes = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return "Не удалось прочитать файл"
        return importKeys(bytes, password)
    }

    /** Восстановление из найденной на устройстве копии. */
    fun restoreFromOffer(password: String): String? {
        val offer = _state.value.restoreOffer ?: return "Файл не найден"
        val bytes = runCatching { java.io.File(offer.path).readBytes() }.getOrNull()
            ?: return "Файл недоступен: возможно, он удалён"
        val error = importKeys(bytes, password)
        if (error == null) _state.update { it.copy(restoreOffer = null) }
        return error
    }

    /**
     * Расшифровка и применение копии. Выполняется синхронно: PBKDF2 на 210 000
     * итераций занимает доли секунды, зато ошибку пароля видно сразу в диалоге.
     */
    fun importKeys(bytes: ByteArray, password: String): String? {
        // Копия защищена мастер-паролем: без него не расшифровываем.
        if (adminStore.hasPassword()) {
            lockMessage()?.let { return it }
            if (!adminStore.verify(password)) {
                refreshAccessState()
                return lockMessage() ?: "Мастер-пароль неверен"
            }
        }
        val result = runCatching {
            runBlocking(Dispatchers.IO) { backupService.restore(bytes, password.toCharArray()) }
        }
        return result.fold(
            onSuccess = { report ->
                applyRestoredMode(report)
                _state.update {
                    it.copy(
                        backupNote = "Восстановлено с ${stamp()} · ключей: ${report.totalKeys}",
                        toast = buildString {
                            append("Восстановлено ключей: ").append(report.totalKeys)
                            if (report.userBuild) append(" · включён режим «Пользователь»")
                        }
                    )
                }
                null
            },
            onFailure = { error ->
                error.message?.takeIf { it.isNotBlank() } ?: "Не удалось прочитать копию"
            }
        )
    }

    private fun applyRestoredMode(report: ru.jbosint.osint.data.RestoreResult) {
        if (report.userBuild) {
            adminStore.mode = WorkMode.USER
        } else if (adminStore.mode == WorkMode.USER && adminStore.hasPassword()) {
            // Ключи восстановлены — остаёмся в пользовательском режиме, пока не введён пароль.
            adminStore.mode = WorkMode.ADMIN
        }
        refreshProviders()
        refreshCustom()
        refreshAccessState()
        _state.update {
            it.copy(
                providers = buildProviders(),
                customModules = customStore.list(),
                assistantConfigured = assistantStore.configured(),
                assistantProvider = assistantStore.provider,
                assistantKeyed = keyedProviders(),
                assistantModel = assistantStore.model,
                analyst = settings.analyst,
                demoMode = settings.demoMode,
                storedKeys = countStoredKeys(),
                workMode = adminStore.mode.id,
                screen = if (adminStore.mode == WorkMode.USER) Screen.HOME else it.screen
            )
        }
    }

    private fun refreshAccessState() {
        _state.update {
            it.copy(
                workMode = adminStore.mode.id,
                adminHasPassword = adminStore.hasPassword(),
                adminLockedUntil = adminStore.lockedUntil(),
                adminRecovery = adminStore.hasRecovery(),
                adminBiometric = adminStore.biometric,
                storedKeys = countStoredKeys()
            )
        }
    }

    private fun lockMessage(): String? {
        val left = adminStore.lockLeft()
        return if (left > 0) "Слишком много попыток. Вход возможен через ${AdminPolicy.lockText(left)}" else null
    }

    /**
     * Экспорт и сборка используют мастер-пароль: он же становится ключом шифрования
     * файла. Проверка идёт через общий счётчик ошибок, поэтому перебор упирается
     * в ту же паузу, что и вход в настройки.
     */
    private fun masterError(password: String): String? {
        if (!adminStore.hasPassword()) {
            return "Сначала задайте мастер-пароль: Настройки → Режим работы"
        }
        lockMessage()?.let { return it }
        if (!adminStore.verify(password)) {
            refreshAccessState()
            return lockMessage() ?: "Мастер-пароль неверен"
        }
        return null
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru"))
            .format(java.util.Date())

    fun wipeKeys() {
        keys.wipeKeys()
        refreshProviders()
        _state.update { it.copy(toast = "Все ключи удалены") }
    }

    fun wipeEverything() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                keys.wipeKeys()
                customStore.wipe()
                historyStore.clear()
            }
            _state.update {
                it.copy(
                    providers = buildProviders(),
                    customModules = emptyList(),
                    history = emptyList(),
                    result = null,
                    resultEntry = null,
                    screen = Screen.HOME,
                    toast = "Ключи и история удалены"
                )
            }
        }
    }

    // ------------------------------------------------ интеллектуальный ассистент

    fun saveAssistantKey(value: String) {
        assistantStore.saveKey(value, assistantStore.provider)
        _state.update {
            it.copy(
                assistantConfigured = assistantStore.configured(),
                assistantKeyed = keyedProviders(),
                assistantState = if (assistantStore.configured()) KeyState.UNCHECKED else KeyState.EMPTY,
                assistantError = null
            )
        }
    }

    fun clearAssistantKey() {
        assistantStore.clearKey()
        _state.update {
            it.copy(
                assistantConfigured = false,
                assistantKeyed = keyedProviders(),
                assistantState = KeyState.EMPTY,
                assistantError = null,
                classifications = emptyMap()
            )
        }
    }

    /** Смена поставщика ИИ: ключи хранятся раздельно, модель — своя у каждого. */
    fun setAssistantProvider(id: String) {
        val spec = AssistantCatalog.provider(id)
        assistantStore.provider = spec.id
        if (spec.custom && assistantStore.customBaseUrl.isBlank()) {
            assistantStore.customBaseUrl = ""
        }
        _state.update {
            it.copy(
                assistantProvider = assistantStore.provider,
                assistantBaseUrl = assistantStore.customBaseUrl,
                assistantModel = assistantStore.model,
                assistantConfigured = assistantStore.configured(),
                assistantKeyed = keyedProviders(),
                assistantState = if (assistantStore.configured()) KeyState.UNCHECKED else KeyState.EMPTY,
                assistantCheckedAt = 0L,
                assistantError = null
            )
        }
    }

    /** Базовый адрес для «своего сервера»: только https, без /chat/completions. */
    fun setAssistantBaseUrl(raw: String) {
        if (raw.isBlank()) {
            assistantStore.customBaseUrl = ""
            _state.update { it.copy(assistantBaseUrl = "", assistantError = null) }
            return
        }
        val normalised = AssistantCatalog.normaliseBase(raw)
        if (normalised == null) {
            _state.update { it.copy(assistantError = "Нужен адрес вида https://api.example.com/v1") }
            return
        }
        assistantStore.customBaseUrl = normalised
        val known = AssistantCatalog.byBaseUrl(normalised)
        if (known != null && known.id != "custom") {
            // Адрес совпал с известным поставщиком — переключаемся на него.
            setAssistantProvider(known.id)
            return
        }
        _state.update { it.copy(assistantBaseUrl = normalised, assistantError = null) }
    }

    fun setAssistantPreset(value: String) {
        assistantStore.preset = value
        _state.update { it.copy(assistantPreset = assistantStore.preset) }
    }

    fun setAssistantModel(id: String) {
        assistantStore.model = id.trim().ifBlank { AssistantCatalog.DEFAULT_MODEL }
        _state.update { it.copy(assistantModel = assistantStore.model) }
    }

    fun checkAssistantKey() {
        if (!assistantStore.configured()) {
            val title = AssistantCatalog.provider(assistantStore.provider).title
            _state.update { it.copy(assistantError = "Сначала вставьте ключ $title") }
            return
        }
        if (_state.value.assistantChecking) return
        _state.update { it.copy(assistantChecking = true, assistantError = null) }
        viewModelScope.launch {
            val check = assistantService.probe()
            _state.update {
                it.copy(
                    assistantChecking = false,
                    assistantState = check.state,
                    assistantCheckedAt = assistantStore.checkedAt(),
                    assistantError = if (check.state == KeyState.VALID) null else check.detail
                )
            }
        }
    }

    fun setAssistantAutoAnalyze(value: Boolean) {
        assistantStore.autoAnalyze = value
        _state.update { it.copy(assistantAutoAnalyze = value) }
    }

    fun setAssistantAutoClassify(value: Boolean) {
        assistantStore.autoClassify = value
        _state.update { it.copy(assistantAutoClassify = value) }
    }

    fun setAssistantAutoQueries(value: Boolean) {
        assistantStore.autoQueries = value
        _state.update { it.copy(assistantAutoQueries = value) }
    }

    fun setAssistantPolish(value: Boolean) {
        assistantStore.polishReport = value
        _state.update { it.copy(assistantPolish = value) }
    }

    fun setAutoResearch(value: Boolean) {
        assistantStore.autoResearch = value
        _state.update { it.copy(autoResearch = value) }
    }

    /** Кнопка «Проанализировать»: вывод аналитика по цифровым следам. */
    fun analyzeResult() {
        val entry = _state.value.resultEntry
        if (entry == null) {
            _state.update { it.copy(toast = "Сначала выполните поиск") }
            return
        }
        if (!requireKey() || _state.value.analysisRunning) return
        _state.update {
            it.copy(analysisRunning = true, assistantBusy = AssistantRole.ANALYST.title, assistantError = null)
        }
        viewModelScope.launch {
            val outcome = doAnalysis(entry)
            _state.update { it.copy(analysisRunning = false, assistantBusy = "") }
            outcome.onSuccess { text ->
                persistEntry(entry.copy(aiAnalysis = text))
                _state.update { it.copy(toast = "Аналитический вывод готов") }
            }.onFailure { error ->
                _state.update { it.copy(assistantError = error.message ?: "Не удалось получить ответ") }
            }
        }
    }

    /** Кнопка «Оформить отчёт»: синтез резюме по готовому исследованию. */
    fun synthesizeReport() {
        val entry = _state.value.resultEntry
        if (entry == null) {
            _state.update { it.copy(toast = "Сначала выполните поиск") }
            return
        }
        if (!requireKey() || _state.value.analysisRunning) return
        _state.update {
            it.copy(analysisRunning = true, assistantBusy = AssistantRole.SYNTHESIS.title, assistantError = null)
        }
        viewModelScope.launch {
            val outcome = doNarrative(entry)
            _state.update { it.copy(analysisRunning = false, assistantBusy = "") }
            outcome.onSuccess { text ->
                persistEntry(entry.copy(aiNarrative = text))
                _state.update { it.copy(toast = "Резюме для отчёта готово") }
            }.onFailure { error ->
                _state.update { it.copy(assistantError = error.message ?: "Не удалось оформить отчёт") }
            }
        }
    }

    /** Кнопка «Интернет-поиск»: запросы модели → DuckDuckGo → выжимка модели. */
    fun researchWeb() {
        val entry = _state.value.resultEntry
        if (entry == null) {
            _state.update { it.copy(toast = "Сначала выполните поиск") }
            return
        }
        if (!requireKey() || _state.value.researchRunning) return
        _state.update { it.copy(researchRunning = true, researchError = null, assistantError = null) }
        viewModelScope.launch {
            val outcome = doResearch(entry)
            _state.update { it.copy(researchRunning = false) }
            outcome.onSuccess { text ->
                persistEntry(entry.copy(aiResearch = text))
                _state.update { it.copy(research = text, researchError = null, toast = "Интернет-исследование завершено") }
            }.onFailure { error ->
                _state.update { it.copy(researchError = error.message ?: "Не удалось получить выжимку") }
            }
        }
    }

    fun clearResearch() {
        val entry = _state.value.resultEntry ?: return
        _state.update { it.copy(research = "", researchError = null) }
        viewModelScope.launch { persistEntry(entry.copy(aiResearch = "")) }
    }

    private fun requireKey(): Boolean {
        if (assistantStore.configured()) return true
        _state.update {
            it.copy(
                assistantError = "Ключ ассистента не задан",
                toast = "Добавьте ключ в «Настройки → Интеллектуальный ассистент»"
            )
        }
        return false
    }

    // ------------------------------------------------- пошаговые операции ассистента

    private suspend fun doAnalysis(entry: HistoryEntry): Result<String> {
        val prompt = AssistantPrompts.analyze(entry.query, entry.data.facts, entry.data.links)
        return assistantService.chat(AssistantRole.ANALYST, prompt).mapCatching { raw ->
            AssistantParser.analysis(raw) ?: throw IllegalStateException("Модель вернула слишком короткий ответ")
        }
    }

    private suspend fun doNarrative(entry: HistoryEntry): Result<String> {
        val prompt = AssistantPrompts.synthesize(entry.query, entry.data, entry.aiAnalysis)
        return assistantService.chat(AssistantRole.SYNTHESIS, prompt).mapCatching { raw ->
            AssistantParser.analysis(raw) ?: throw IllegalStateException("Модель вернула слишком короткий ответ")
        }
    }

    private suspend fun doResearch(entry: HistoryEntry): Result<String> {
        val context = entry.data.facts.take(8).joinToString("; ") { "${it.kind}: ${it.value}" }
        // Agent API сам выполняет веб-поиск и возвращает источники — промежуточные
        // запросы и разбор HTML-выдачи не нужны.
        val agentProvider = ru.jbosint.osint.data.AssistantCatalog
            .provider(assistantStore.provider).agent
        if (agentProvider) {
            val report = assistantService.agentReport(entry.query, context).getOrThrow()
            val text = report.asText()
            if (text.isBlank()) throw IllegalStateException("Модель не вернула сведений")
            return Result.success(text)
        }
        val plan = assistantService.chat(
            AssistantRole.RESEARCHER,
            AssistantPrompts.researchQueries(entry.query, context)
        )
        val queries = plan.getOrNull()?.let { AssistantParser.searchStrings(it) }
            ?.takeIf { it.isNotEmpty() } ?: listOf(entry.query)

        val hits = ArrayList<WebHit>()
        for (query in queries.take(2)) {
            hits += WebResearch.search(query, 4)
            if (hits.size >= 8) break
        }
        val unique = hits.distinctBy { it.url }.take(8)
        if (unique.isEmpty()) {
            return Result.failure(
                IllegalStateException("Поиск не дал результатов: сервис недоступен или запрос слишком узкий")
            )
        }
        val summary = assistantService.chat(
            AssistantRole.RESEARCHER,
            AssistantPrompts.researchSummary(entry.query, WebResearch.digest(unique))
        ).getOrThrow()
        val text = AssistantParser.analysis(summary) ?: summary.trim().take(6000)
        val sources = unique.joinToString("\n") { "- ${it.title} — ${it.url}" }
        return Result.success(text + "\n\nИсточники:\n" + sources)
    }

    /** Разметка типов значений моделью: подсказки в результатах, сами факты не меняются. */
    private suspend fun doClassification(entry: HistoryEntry): Map<String, AssistantClassification> {
        val values = entry.data.facts.map { it.value }.filter { it.isNotBlank() }.distinct().take(12)
        if (values.isEmpty()) return emptyMap()
        val prompt = if (values.size == 1) AssistantPrompts.classify(values.first())
        else AssistantPrompts.classifyBatch(values)
        val raw = assistantService.chat(AssistantRole.CLASSIFIER, prompt).getOrNull() ?: return emptyMap()
        val parsed = AssistantParser.classifications(raw)
        if (parsed.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, AssistantClassification>()
        if (values.size == 1) {
            map[values.first()] = parsed.first()
        } else if (parsed.size >= values.size) {
            values.forEachIndexed { index, value -> map[value] = parsed[index] }
        }
        return map
    }

    /** Планировщик запросов: модель подбирает формулировку до запуска модулей. */
    private suspend fun refineQuery(input: String): String? {
        val modules = listOf(
            "профили" to "социальные сети и публичные профили",
            "контакты" to "почта, телефон, мессенджеры",
            "домены" to "домены, IP, сертификаты",
            "утечки" to "публичные базы компрометации"
        )
        val prompt = AssistantPrompts.queries(input, modules, "")
        val raw = assistantService.chat(AssistantRole.QUERY_PLANNER, prompt).getOrNull() ?: return null
        return AssistantParser.queries(raw).map { it.query }
            .firstOrNull { it.length in 2..120 && !it.equals(input, ignoreCase = true) }
    }

    /** Последовательное обогащение результата по включённым сценариям. */
    private fun autoEnrich(entry: HistoryEntry) {
        if (!assistantStore.configured()) return
        if (!assistantStore.autoClassify && !assistantStore.autoAnalyze &&
            !assistantStore.autoResearch && !assistantStore.polishReport
        ) return
        viewModelScope.launch {
            var current = entry
            if (assistantStore.autoClassify) {
                val map = doClassification(current)
                if (map.isNotEmpty()) {
                    _state.update { it.copy(classifications = it.classifications + map) }
                    current = current.copy(
                        aiNotes = current.aiNotes + map.mapValues { (_, v) -> "${v.typeLabel} · значимость ${v.relevanceLabel}" }
                    )
                    persistEntry(current)
                }
            }
            if (assistantStore.autoAnalyze) {
                doAnalysis(current).onSuccess { text ->
                    current = current.copy(aiAnalysis = text)
                    persistEntry(current)
                }
            }
            if (assistantStore.autoResearch) {
                doResearch(current).onSuccess { text ->
                    current = current.copy(aiResearch = text)
                    persistEntry(current)
                    _state.update { it.copy(research = text, researchError = null) }
                }.onFailure { error ->
                    _state.update { it.copy(researchError = error.message) }
                }
            }
            if (assistantStore.polishReport && current.aiAnalysis.isNotBlank()) {
                doNarrative(current).onSuccess { text ->
                    current = current.copy(aiNarrative = text)
                    persistEntry(current)
                    _state.update { it.copy(toast = "Ассистент оформил резюме отчёта") }
                }
            }
        }
    }

    private suspend fun persistEntry(entry: HistoryEntry) {
        val entries = withContext(Dispatchers.IO) {
            historyStore.save(entry)
            historyStore.list()
        }
        _state.update { s ->
            s.copy(
                resultEntry = if (s.resultEntry?.id == entry.id) entry else s.resultEntry,
                result = if (s.resultEntry?.id == entry.id) entry.data else s.result,
                research = if (s.resultEntry?.id == entry.id) entry.aiResearch else s.research,
                history = entries
            )
        }
    }

    // -------------------------------------------------------- telegram-парсер

    fun setTgChannel(value: String) = _state.update { it.copy(tg = it.tg.copy(channel = value, error = null)) }

    fun setTgPages(pages: Int) = _state.update {
        it.copy(tg = it.tg.copy(pages = pages.coerceIn(1, 20)))
    }

    fun setTgMode(mode: ru.jbosint.osint.data.TelegramRules.Kind) = _state.update {
        it.copy(tg = it.tg.copy(mode = mode, error = null))
    }

    fun runTelegramParse() {
        val current = _state.value.tg
        if (current.running) return
        val channel = current.channel.trim()
        if (channel.isBlank()) {
            _state.update { it.copy(tg = it.tg.copy(error = "Укажите входные данные для анализа")) }
            return
        }
        val parsed = TelegramRules.parse(channel, current.mode)
        if (!parsed.valid) {
            _state.update { it.copy(tg = it.tg.copy(error = parsed.error)) }
            return
        }
        val telesint = keys.key("telesint")
        val telelog = keys.key("telelog")
        val funstat = keys.key("funstat")
        if (telesint.isBlank() && telelog.isBlank() && funstat.isBlank()) {
            _state.update {
                it.copy(tg = it.tg.copy(error = "Добавьте ключ TeleSINT, Telelog или Funstat в разделе «Модули»"))
            }
            return
        }
        _state.update { it.copy(tg = it.tg.copy(running = true, stage = "Подготовка…", error = null, dossier = null)) }
        viewModelScope.launch {
            val outcome = runCatching {
                TgParser.collect(
                    telesintKey = telesint,
                    telelogKey = telelog,
                    funstatKey = funstat,
                    input = channel,
                    kindHint = current.mode,
                    maxPages = current.pages
                ) { stage -> _state.update { it.copy(tg = it.tg.copy(stage = stage)) } }
            }
            outcome.onSuccess { dossier ->
                _state.update {
                    it.copy(
                        tg = it.tg.copy(running = false, stage = "", dossier = dossier),
                        toast = "Готово: ${dossier.facts.size} фактов, ${dossier.members.size} участников"
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        tg = it.tg.copy(running = false, stage = "", error = error.message ?: "Не удалось собрать данные")
                    )
                }
            }
        }
    }

    /** Выгрузка результата парсера: HTML-отчёт, CSV или Excel. */
    fun exportTelegram(format: TgFormat) {
        val dossier = _state.value.tg.dossier ?: run {
            _state.update { it.copy(toast = "Сначала соберите аудиторию канала") }
            return
        }
        if (_state.value.tg.exporting) return
        _state.update { it.copy(tg = it.tg.copy(exporting = true)) }
        viewModelScope.launch {
            val meta = ReportMeta(
                analyst = _state.value.analyst,
                reportId = "TG-" + java.text.SimpleDateFormat("yyMMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date(dossier.collectedAt)),
                createdAt = dossier.collectedAt,
                appVersion = BuildConfig.VERSION_NAME,
                queryType = "Telegram-канал",
                classification = "КОНФИДЕНЦИАЛЬНО · ДЛЯ ВНУТРЕННЕГО ИСПОЛЬЗОВАНИЯ"
            )
            val bytes = runCatching {
                when (format) {
                    TgFormat.HTML -> {
                        val intro = "Объект ${dossier.title} (${dossier.link}): " +
                            "фактов ${dossier.facts.size}, участников ${dossier.members.size}, бэкенды ${dossier.backends.joinToString()}." +
                            if (dossier.hasMore) " Выгрузка неполная — увеличьте число страниц." else ""
                        HtmlReport(meta, TgExport.data(dossier), intro = intro, factsTitle = "Результат Telegram-парсера")
                            .build().toByteArray(Charsets.UTF_8)
                    }
                    TgFormat.CSV -> TgExport.csv(dossier).toByteArray(Charsets.UTF_8)
                    TgFormat.XLSX -> XlsxReport(meta, TgExport.data(dossier)).build()
                    TgFormat.RAW_JSON -> TgExport.rawJson(dossier).toByteArray(Charsets.UTF_8)
                }
            }
            _state.update { it.copy(tg = it.tg.copy(exporting = false)) }
            bytes.onSuccess { data ->
                val outcome = runCatching {
                    exporter.save(TgExport.fileName(dossier, format.extension), data, format.mime)
                }
                outcome.onSuccess { exported ->
                    val where = exported.publicFolder
                    _state.update {
                        it.copy(
                            toast = if (where != null) "${format.label} сохранён: $where"
                            else "${format.label} готов — используйте «Поделиться»"
                        )
                    }
                    _shareEvents.emit(ShareRequest(exported.uri, exported.mime, exported.fileName))
                }.onFailure { error ->
                    _state.update { it.copy(toast = "Ошибка выгрузки: ${error.message ?: "неизвестная"}") }
                }
            }.onFailure { error ->
                _state.update { it.copy(toast = "Ошибка выгрузки: ${error.message ?: "неизвестная"}") }
            }
        }
    }

    // ------------------------------------------------------------------ экспорт

    fun export(format: ReportExporter.Format) {
        val current = _state.value
        val entry = current.resultEntry
        if (entry == null) {
            _state.update { it.copy(toast = "Сначала выполните поиск") }
            return
        }
        if (current.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val outcome = runCatching { exporter.export(entry, format) }
            _state.update { it.copy(exporting = false) }
            outcome.onSuccess { exported ->
                val where = exported.publicFolder
                _state.update {
                    it.copy(
                        toast = if (where != null)
                            "${format.label} сохранён: $where"
                        else
                            "${format.label} готов — используйте «Поделиться»"
                    )
                }
                _shareEvents.emit(ShareRequest(exported.uri, exported.mime, exported.fileName))
            }.onFailure { error ->
                _state.update { it.copy(toast = "Ошибка экспорта: ${error.message ?: "неизвестная"}") }
            }
        }
    }

    // ------------------------------------------------------------------ профиль

    /** Профиль из последнего поиска (факты всех модулей). */
    fun openProfileFromLastResult() {
        val entry = _state.value.resultEntry ?: _state.value.history.firstOrNull()
        if (entry == null) {
            _state.update { it.copy(toast = "Сначала выполните поиск") }
            return
        }
        buildProfile(entry = entry, dossier = null)
    }

    /** Профиль канала/чата из результата Telegram-парсера. */
    fun openProfileFromTelegram() {
        val dossier = _state.value.tg.dossier
        if (dossier == null) {
            _state.update { it.copy(toast = "Сначала соберите аудиторию канала") }
            return
        }
        buildProfile(entry = null, dossier = dossier)
    }

    /** Демонстрационный профиль: все блоки заполнены без ключей и сети. */
    fun openDemoProfile() {
        importedMessages = ProfileMessages.demo()
        buildProfile(entry = null, dossier = null, messages = importedMessages, demo = true, query = "@demo_subject")
    }

    /** Пересборка профиля из тех же данных (после импорта сообщений или смены модулей). */
    fun rebuildProfile() {
        val current = _state.value.profile ?: return
        val entry = _state.value.resultEntry
        val dossier = _state.value.tg.dossier
        buildProfile(entry, dossier, importedMessages.ifEmpty { current.messages }, demo = current.demo, query = current.query)
    }

    private fun buildProfile(
        entry: HistoryEntry?,
        dossier: TgDossier?,
        messages: List<ru.jbosint.osint.profile.ProfileMessage> = importedMessages,
        demo: Boolean = false,
        query: String = ""
    ) {
        _state.update { it.copy(profileBusy = true) }
        viewModelScope.launch {
            val profile = withContext(Dispatchers.Default) {
                ProfileBuilder.build(entry, dossier, messages, demo, query)
            }
            val previous = profileStore.load(profile.id) ?: previousProfileFor(profile)
            val changes = ProfileDiff.diff(previous, profile)
            withContext(Dispatchers.IO) { profileStore.save(profile) }
            _state.update {
                it.copy(
                    profile = profile,
                    profileChanges = changes,
                    profileBusy = false,
                    screen = Screen.PROFILE,
                    profileBriefs = profileStore.brief(),
                    profileDossier = "",
                    compareText = "",
                    assistantError = null
                )
            }
        }
    }

    private fun previousProfileFor(profile: ProfileData): ProfileData? =
        profileStore.ids().asSequence()
            .mapNotNull { profileStore.load(it) }
            .firstOrNull { candidate ->
                candidate.id != profile.id &&
                    (candidate.subject.username.isNotBlank() &&
                        candidate.subject.username.equals(profile.subject.username, true) ||
                        candidate.subject.id.isNotBlank() && candidate.subject.id == profile.subject.id)
            }

    /** Импорт выгрузки сообщений (CSV/JSON): дата, отправитель, текст, тип. */
    fun importProfileMessages(uri: Uri) {
        val profile = _state.value.profile
        if (profile == null) {
            _state.update { it.copy(toast = "Сначала откройте профиль") }
            return
        }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = uri.lastPathSegment.orEmpty()
                    val raw = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Не удалось открыть файл")
                    ProfileMessages.parse(name, raw)
                }
            }
            outcome.onSuccess { result ->
                if (result.error != null || result.messages.isEmpty()) {
                    _state.update { it.copy(toast = "Импорт не удался: ${result.error ?: "нет сообщений"}") }
                    return@onSuccess
                }
                importedMessages = result.messages
                _state.update {
                    it.copy(
                        toast = "Загружено ${result.messages.size} сообщений" +
                            if (result.skipped > 0) ", пропущено ${result.skipped}" else ""
                    )
                }
                rebuildProfile()
            }.onFailure { error ->
                _state.update { it.copy(toast = "Импорт не удался: ${error.message ?: "ошибка чтения"}") }
            }
        }
    }

    /** Выгрузка профиля: сообщения, аудитория, Excel, граф, досье. */
    fun exportProfile(kind: ProfileExport) {
        val profile = _state.value.profile ?: run {
            _state.update { it.copy(toast = "Профиль не открыт") }
            return
        }
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val anonymize = _state.value.profileAnonymize
            val outcome = runCatching {
                withContext(Dispatchers.Default) {
                    val base = ProfileExporter.baseName(profile)
                    when (kind) {
                        ProfileExport.XLSX -> Triple(
                            "$base.xlsx",
                            ProfileExporter.xlsx(profile, anonymize),
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                        ProfileExport.MESSAGES_CSV -> Triple(
                            ProfileExporter.messagesName(profile, "csv"),
                            ProfileExporter.messagesCsv(profile, anonymize),
                            "text/csv"
                        )
                        ProfileExport.AUDIENCE_CSV -> Triple(
                            ProfileExporter.contactsName(profile, "csv"),
                            ProfileExporter.contactsCsv(profile, anonymize),
                            "text/csv"
                        )
                        ProfileExport.GRAPHML -> Triple(
                            ProfileExporter.graphName(profile),
                            ProfileExporter.graphMl(profile, anonymize),
                            "application/graphml+xml"
                        )
                        ProfileExport.DOSSIER -> Triple(
                            "$base.html",
                            ProfileExporter.dossierHtml(profile, DossierTemplate.PERSON, anonymize),
                            "text/html"
                        )
                    }
                }
            }
            _state.update { it.copy(exporting = false) }
            outcome.onSuccess { (fileName, bytes, mime) ->
                val saved = runCatching { exporter.save(fileName, bytes, mime) }
                saved.onSuccess { exported ->
                    _state.update {
                        it.copy(
                            toast = if (exported.publicFolder != null)
                                "${kind.label}: ${exported.publicFolder}"
                            else "${kind.label} готов — «Поделиться»"
                        )
                    }
                    _shareEvents.emit(ShareRequest(exported.uri, exported.mime, exported.fileName))
                }.onFailure { error ->
                    _state.update { it.copy(toast = "Ошибка сохранения: ${error.message ?: "неизвестная"}") }
                }
            }.onFailure { error ->
                _state.update { it.copy(toast = "Ошибка выгрузки: ${error.message ?: "неизвестная"}") }
            }
        }
    }

    /** Кнопка «ИИ-досье»: аналитический отчёт по всем собранным данным. */
    fun generateProfileReport() {
        val profile = _state.value.profile ?: return
        if (!requireKey() || _state.value.analysisRunning) return
        _state.update {
            it.copy(analysisRunning = true, assistantBusy = AssistantRole.ANALYST.title, assistantError = null)
        }
        viewModelScope.launch {
            val prompt = buildString {
                appendLine("Ты аналитик OSINT. Составь досье по объекту на основе только этих данных.")
                appendLine("Объект: ${profile.subject.title} (@${profile.subject.username}), " +
                    "ID ${profile.subject.id}, статус ${profile.subject.status.title}.")
                appendLine("Ссылки: ${profile.subject.link}.")
                appendLine("Идентификаторы: " + profile.identifiers.take(20)
                    .joinToString(", ") { "${it.kind}=${it.value}" })
                appendLine("Группы: " + profile.groups.take(15).joinToString(", "))
                appendLine("Контакты: " + profile.contacts.take(15)
                    .joinToString(", ") { "${it.name}${if (it.username.isNotBlank()) " (@" + it.username + ")" else ""}" })
                appendLine("Метрики: сообщений ${profile.summary.messages}, медиа ${profile.summary.media}, " +
                    "реплаев ${profile.summary.replies}, период ${ProfileBuilder.dateText(profile.summary.from)}—" +
                    "${ProfileBuilder.dateText(profile.summary.to)}.")
                appendLine("Поведение: пик активности ${profile.behavior.heatmap.peakHour}:00, " +
                    "ночью ${profile.behavior.heatmap.nightShare}%, тональность ${profile.behavior.sentiment.tone}.")
                appendLine("Темы: " + profile.behavior.topics.joinToString(", ") { "${it.name} ${it.share}%" })
                appendLine("Репутация: токсичность ${profile.reputation.toxicity}%, спам ${profile.reputation.spam}%, " +
                    "доверие ${profile.reputation.trust}%.")
                appendLine("Нет данных по: " + profile.missing.joinToString(", "))
                appendLine("Структура: 1) Кто это; 2) Цифровые следы; 3) Круг общения; 4) Поведение и риски; " +
                    "5) Что проверить дальше. Не выдумывай факты, ссылайся на перечисленное.")
            }
            val outcome = assistantService.chat(AssistantRole.ANALYST, prompt)
            _state.update { it.copy(analysisRunning = false, assistantBusy = "") }
            outcome.onSuccess { text ->
                _state.update { it.copy(profileDossier = text, toast = "ИИ-досье готово") }
            }.onFailure { error ->
                _state.update { it.copy(assistantError = error.message ?: "Не удалось получить ответ") }
            }
        }
    }

    /** Сравнение текущего профиля с другим сохранённым. */
    fun compareProfiles() {
        val current = _state.value.profile ?: return
        val other = profileStore.ids()
            .filter { it != current.id }
            .mapNotNull { profileStore.load(it) }
            .firstOrNull()
        if (other == null) {
            _state.update { it.copy(toast = "Нужны два профиля: откройте второй объект и вернитесь") }
            return
        }
        _state.update { it.copy(compareText = ProfileCompare.compare(current, other)) }
    }

    fun setProfileAnonymize(value: Boolean) = _state.update { it.copy(profileAnonymize = value) }

    /** PIN на раздел анализа: хранится только SHA-256. */
    fun setProfilePin(pin: String) {
        val clean = pin.filter { it.isDigit() }
        if (clean.length < 4) {
            _state.update { it.copy(toast = "PIN должен быть не короче 4 цифр") }
            return
        }
        settings.profilePin = PinHash.hash(clean)
        settings.pinFails = 0
        settings.pinLockUntil = 0L
        _state.update { it.copy(profilePinSet = true, profileUnlocked = true, toast = "PIN установлен") }
    }

    fun clearProfilePin() {
        settings.profilePin = ""
        settings.pinFails = 0
        settings.pinLockUntil = 0L
        _state.update { it.copy(profilePinSet = false, profileUnlocked = true, toast = "PIN снят") }
    }

    fun unlockProfile(pin: String) {
        val expected = settings.profilePin
        if (expected.isBlank()) {
            _state.update { it.copy(profileUnlocked = true) }
            return
        }
        val now = System.currentTimeMillis()
        val lockUntil = settings.pinLockUntil
        if (lockUntil > now) {
            val left = ((lockUntil - now) / 1000L).coerceAtLeast(1L)
            _state.update { it.copy(toast = "Слишком много попыток. Повторите через $left с") }
            return
        }
        val clean = pin.filter { it.isDigit() }
        if (PinHash.verify(clean, expected)) {
            // Старый PIN, сохранённый как «голый» SHA-256, молча переписываем в PBKDF2.
            if (PinHash.needsRehash(expected)) settings.profilePin = PinHash.hash(clean)
            settings.pinFails = 0
            settings.pinLockUntil = 0L
            _state.update { it.copy(profileUnlocked = true) }
            return
        }
        val fails = settings.pinFails + 1
        settings.pinFails = fails
        if (fails % MAX_PIN_FAILS == 0) {
            // Пауза растёт: 30 с, 60 с, 120 с … но не больше 10 минут.
            val exponent = (fails / MAX_PIN_FAILS - 1).coerceIn(0, 4)
            val lockSeconds = (30L shl exponent).coerceAtMost(600L)
            settings.pinLockUntil = now + lockSeconds * 1000L
            _state.update { it.copy(toast = "Неверный PIN. Ввод заблокирован на $lockSeconds с") }
        } else {
            val left = MAX_PIN_FAILS - (fails % MAX_PIN_FAILS)
            _state.update { it.copy(toast = "Неверный PIN. Осталось попыток: $left") }
        }
    }

    /** «Очистить профиль»: удаляет кэш профиля и импортированные сообщения. */
    fun clearProfile() {
        val profile = _state.value.profile
        if (profile != null) profileStore.delete(profile.id)
        importedMessages = emptyList()
        _state.update {
            it.copy(
                profile = null,
                profileChanges = emptyList(),
                profileDossier = "",
                compareText = "",
                profileBriefs = profileStore.brief(),
                screen = Screen.HOME,
                toast = "Профиль и кэш удалены"
            )
        }
    }

    // ------------------------------------------------------- автономный агент

    fun setAgentQuery(value: String) = _state.update {
        it.copy(agent = it.agent.copy(query = value, target = AgentPlanner.detect(value), error = null))
    }

    /** Запуск каскада: бесплатные модули → по ключу → платные (с лимитом). */
    fun runAgent() {
        val current = _state.value.agent
        val query = current.query.trim()
        if (query.isBlank()) {
            _state.update { it.copy(toast = "Введите ФИО, телефон, почту, VIN, домен или ник") }
            return
        }
        if (current.running) return
        // Telegram-ник проверяем до запроса: иначе платные модули тратятся впустую.
        val override = current.targetOverride
        val looksTelegram = query.startsWith("@") || override == AgentTarget.USERNAME
        if (looksTelegram) {
            val error = TelegramRules.error(query)
            if (error != null) {
                _state.update { it.copy(agent = it.agent.copy(error = error)) }
                return
            }
        }
        _state.update {
            it.copy(agent = it.agent.copy(running = true, error = null, report = null, stage = "Планирование", progress = 2))
        }
        viewModelScope.launch {
            val depth = _state.value.agent.depth
            val outcome = runCatching {
                agentRunner.run(query, depth, override) { stage, progress ->
                    _state.update { state -> state.copy(agent = state.agent.copy(stage = stage, progress = progress)) }
                }
            }
            outcome.onSuccess { report ->
                _state.update {
                    it.copy(
                        agent = it.agent.copy(
                            running = false,
                            report = report,
                            stage = "",
                            progress = 100,
                            history = (listOf(query) + it.agent.history).distinct().take(8)
                        ),
                        journal = SearchJournal.last(30),
                        toast = if (report.hasResults) "Готово: ${report.findings.size} фактов"
                        else "Данные не найдены — смотрите журнал модулей"
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        agent = it.agent.copy(running = false, error = error.message ?: "Не удалось выполнить поиск", stage = ""),
                        journal = SearchJournal.last(30)
                    )
                }
            }
        }
    }

    fun clearAgent() = _state.update { it.copy(agent = AgentUi(query = it.agent.query)) }

    /** Профиль из результатов агента — тот же экран, что и для обычного поиска. */
    fun openAgentProfile() {
        val report = _state.value.agent.report ?: return
        val entry = HistoryEntry(
            id = "agent_" + System.currentTimeMillis(),
            query = report.query,
            createdAt = System.currentTimeMillis(),
            analyst = _state.value.analyst,
            data = report.data,
            demo = false,
            queryType = report.target.title
        )
        viewModelScope.launch {
            val profile = withContext(Dispatchers.Default) { ProfileBuilder.build(entry, null, emptyList()) }
            profileStore.save(profile)
            _state.update {
                it.copy(profile = profile, screen = Screen.PROFILE, profileBriefs = profileStore.brief())
            }
        }
    }

    /** Экспорт отчёта агента: модули в документе анонимны, ссылки — в приложении B. */
    fun exportAgent(format: ReportExporter.Format) {
        val report = _state.value.agent.report ?: run {
            _state.update { it.copy(toast = "Сначала запустите агента") }
            return
        }
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val entry = HistoryEntry(
                id = "agent_" + report.durationMs,
                query = report.query,
                createdAt = System.currentTimeMillis(),
                analyst = _state.value.analyst,
                data = report.anonymousData,
                demo = false,
                aiAnalysis = buildString {
                    appendLine("Агент: ${report.target.title}. ${report.note}")
                    appendLine("Утверждения:")
                    report.findings.take(40).forEach { appendLine("- ${it.text} [${it.status.title}]") }
                    if (report.assumptions.isNotEmpty()) {
                        appendLine("Предположения:")
                        report.assumptions.forEach { appendLine("- ${it.text}") }
                    }
                    appendLine("Приложение B (реальные источники):")
                    report.modules.forEach { appendLine("- ${it.label}: ${it.realName} — ${it.url}") }
                },
                queryType = report.target.title
            )
            val outcome = runCatching { exporter.export(entry, format) }
            _state.update { it.copy(exporting = false) }
            outcome.onSuccess { exported ->
                _state.update { it.copy(toast = "${format.label} готов") }
                _shareEvents.emit(ShareRequest(exported.uri, exported.mime, exported.fileName))
            }.onFailure { error ->
                _state.update { it.copy(toast = "Ошибка экспорта: ${error.message ?: "неизвестная"}") }
            }
        }
    }

    // ------------------------------------------------------------ проверка авто

    fun setAutoQuery(value: String) = _state.update {
        it.copy(auto = it.auto.copy(query = value, error = null))
    }

    fun runAutoCheck() {
        val query = _state.value.auto.query.trim()
        if (query.isBlank()) {
            _state.update { it.copy(toast = "Введите VIN, номер кузова или госномер") }
            return
        }
        if (_state.value.auto.running) return
        _state.update { it.copy(auto = it.auto.copy(running = true, error = null, report = null)) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val compact = query.filter { it.isLetterOrDigit() }.uppercase()
                    val isVin = compact.length == 17
                    val raw = ArrayList<ru.jbosint.osint.core.model.RawFact>()
                    raw += AutoCheck.decodeVin(if (isVin) compact else "")
                    if (isVin) raw += runCatching { ru.jbosint.osint.data.VinDecoder.decode(compact) }
                        .getOrDefault(emptyList())
                    val key = keys.key("tronk")
                    var paid = 0
                    val baseUrl = keys.secondField("tronk")
                    if (key.isNotBlank() && baseUrl.startsWith("https://")) {
                        raw += AutoCheck.tronk(
                            key = key,
                            vin = if (isVin) compact else "",
                            plate = if (isVin) "" else query.uppercase(),
                            baseUrl = baseUrl
                        )
                        paid = 1
                    }
                    val stats = ru.jbosint.osint.core.model.SearchStats(
                        sourcesAttempted = 2,
                        sourcesResponded = if (raw.isEmpty()) 0 else 1,
                        sourcesFailed = if (raw.isEmpty()) 1 else 0,
                        rawFacts = raw.size,
                        duplicatesRemoved = 0,
                        durationMs = 0
                    )
                    val data = ru.jbosint.osint.core.aggregate.Aggregator.aggregate(query, raw, stats)
                        .copy(sources = listOf(ru.jbosint.osint.core.report.SourceCatalog.ref("tronk"), ru.jbosint.osint.core.report.SourceCatalog.ref("vin")))
                    val anonymized = ru.jbosint.osint.agent.AgentAnonymizer.apply(data)
                    val summary = raw.map { it.kind to it.value }.distinct().take(20)
                    AutoReport(
                        query = query,
                        vin = if (isVin) compact else "",
                        plate = if (isVin) "" else query.uppercase(),
                        summary = summary,
                        findings = AgentNarrator.findings(anonymized.data),
                        assumptions = AgentNarrator.assumptions(anonymized.data, AgentTarget.VIN),
                        manualLinks = AutoCheck.manualLinks(vin = if (isVin) compact else "", plate = if (isVin) "" else query.uppercase()),
                        modules = anonymized.modules,
                        ok = raw.isNotEmpty(),
                        error = when {
                            key.isBlank() -> "Ключ TRONK не задан — показаны локальный разбор VIN, NHTSA и ссылки для ручной проверки"
                            !keys.secondField("tronk").startsWith("https://") ->
                                "Укажите базовый URL API TRONK в «Ключи → Проверка авто» — без него платный модуль не работает"
                            else -> null
                        }
                    )
                }
            }
            outcome.onSuccess { report ->
                _state.update { it.copy(auto = it.auto.copy(running = false, report = report)) }
            }.onFailure { error ->
                _state.update { it.copy(auto = it.auto.copy(running = false, error = error.message ?: "Ошибка проверки")) }
            }
        }
    }

    // --------------------------------------------------------- поиск по фото

    fun setPhotoPlatform(platform: String?) = _state.update {
        it.copy(photoSearch = it.photoSearch.copy(platform = platform))
    }

    fun runPhotoSearch(uri: Uri) {
        if (_state.value.photoSearch.running) return
        _state.update {
            it.copy(
                photoSearch = PhotoUi(
                    running = true,
                    stage = "Анализ фотографии",
                    progress = 5,
                    photoUri = uri.toString()
                )
            )
        }
        viewModelScope.launch {
            val outcome = runCatching {
                val photo = PhotoAnalyzer.analyze(getApplication(), uri)
                _state.update {
                    it.copy(photoSearch = it.photoSearch.copy(stage = "Обратный поиск", progress = 35))
                }
                PhotoSearch.run(photo, keys) { stage, progress ->
                    _state.update { state -> state.copy(photoSearch = state.photoSearch.copy(stage = stage, progress = progress)) }
                }
            }
            outcome.onSuccess { result ->
                _state.update {
                    it.copy(
                        photoSearch = PhotoUi(running = false, result = result, progress = 100),
                        toast = if (result.hasResults) "Совпадений: ${result.found.size}" else "Совпадений не найдено"
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(photoSearch = it.photoSearch.copy(running = false, error = error.message ?: "Не удалось обработать фото", stage = ""))
                }
            }
        }
    }

    /** «Добавить в досье»: факты фото → профиль объекта. */
    fun addPhotoToDossier() {
        val result = _state.value.photoSearch.result ?: return
        val raw = PhotoAnalyzer.facts(result.photo) + PhotoSearch.toFacts(result)
        val stats = ru.jbosint.osint.core.model.SearchStats(
            sourcesAttempted = 1,
            sourcesResponded = 1,
            sourcesFailed = 0,
            rawFacts = raw.size,
            duplicatesRemoved = 0,
            durationMs = result.durationMs
        )
        val data = ru.jbosint.osint.core.aggregate.Aggregator.aggregate(result.photo.fileName, raw, stats)
            .copy(sources = listOf(ru.jbosint.osint.core.report.SourceCatalog.ref("photo")))
        val entry = HistoryEntry(
            id = "photo_" + System.currentTimeMillis(),
            query = result.photo.fileName,
            createdAt = System.currentTimeMillis(),
            analyst = _state.value.analyst,
            data = data,
            demo = false,
            queryType = "фотография",
            photoNote = PhotoSearch.summary(result)
        )
        viewModelScope.launch {
            val profile = withContext(Dispatchers.Default) { ProfileBuilder.build(entry, null, emptyList()) }
            profileStore.save(profile)
            _state.update {
                it.copy(profile = profile, screen = Screen.PROFILE, profileBriefs = profileStore.brief(), toast = "Совпадения добавлены в досье")
            }
        }
    }

    // ------------------------------------------------------ мониторинг объявлений

    fun refreshWatches() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { watchStore.list() }
            _state.update { it.copy(watches = items) }
        }
    }

    fun addWatch(title: String, query: String, priceMax: Int) {
        if (query.isBlank()) {
            _state.update { it.copy(toast = "Укажите критерий мониторинга") }
            return
        }
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) { watchStore.add(title, query, priceMax) }
            _state.update { it.copy(watches = it.watches + item, toast = "Мониторинг добавлен") }
        }
    }

    fun removeWatch(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { watchStore.remove(id) }
            _state.update {
                it.copy(watches = it.watches.filterNot { watch -> watch.id == id }, watchNotes = it.watchNotes - id)
            }
        }
    }

    fun checkWatch(id: String) {
        val item = _state.value.watches.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _state.update { it.copy(watchNotes = it.watchNotes + (id to "Проверяем…")) }
            val outcome = runCatching { watchStore.check(item) }
            outcome.onSuccess { check ->
                _state.update {
                    it.copy(
                        watches = it.watches.map { watch -> if (watch.id == id) check.item else watch },
                        watchNotes = it.watchNotes + (id to check.summary)
                    )
                }
                if (check.changed) {
                    Notify.watch(
                        getApplication(),
                        check.item.title,
                        "Новые объявления: ${check.fresh.size}. " + check.fresh.take(2).joinToString(", ")
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(watchNotes = it.watchNotes + (id to "Ошибка: ${error.message ?: "неизвестная"}")) }
            }
        }
    }

    fun checkAllWatches() {
        _state.value.watches.forEach { checkWatch(it.id) }
    }


    fun setAgentTarget(target: AgentTarget?) = _state.update {
        it.copy(agent = it.agent.copy(targetOverride = target, error = null))
    }

    fun refreshJournal() = _state.update { it.copy(journal = SearchJournal.last(30)) }

    fun clearJournal() {
        SearchJournal.clear()
        _state.update { it.copy(journal = emptyList()) }
    }

    fun setAgentDepth(depth: Int) = _state.update {
        it.copy(agent = it.agent.copy(depth = depth.coerceIn(1, 3)))
    }

    fun setAgentSection(section: Int) = _state.update {
        it.copy(agent = it.agent.copy(section = section.coerceIn(0, 3)))
    }

    /** Экспорт отчёта агента по выбранному шаблону (HTML-досье). */
    fun exportAgentHtml(template: AgentTemplate) {
        val report = _state.value.agent.report ?: run {
            _state.update { it.copy(toast = "Сначала запустите агента") }
            return
        }
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val outcome = runCatching {
                val bytes = withContext(Dispatchers.Default) { AgentTemplates.build(report, template) }
                exporter.save(AgentTemplates.fileName(report, template), bytes, "text/html")
            }
            _state.update { it.copy(exporting = false) }
            outcome.onSuccess { exported ->
                _state.update { it.copy(toast = "«${template.title}» готов") }
                _shareEvents.emit(ShareRequest(exported.uri, exported.mime, exported.fileName))
            }.onFailure { error ->
                _state.update { it.copy(toast = "Ошибка экспорта: ${error.message ?: "неизвестная"}") }
            }
        }
    }

    /** Связка «фото → агент»: никнейм из совпадения уходит в каскадный поиск. */
    fun runAgentForHandle(handle: String) {
        if (handle.isBlank()) {
            _state.update { it.copy(toast = "В совпадениях нет никнеймов") }
            return
        }
        _state.update {
            it.copy(
                agent = it.agent.copy(query = "@" + handle.trimStart('@'), target = AgentTarget.USERNAME, section = 0),
                screen = Screen.AGENT
            )
        }
        runAgent()
    }

    /** Анализ продавца по телефону: отдельный прогон агента. */
    fun setSellerPhone(value: String) = _state.update {
        it.copy(auto = it.auto.copy(sellerPhone = value, sellerError = null))
    }

    fun checkSeller() {
        val raw = _state.value.auto.sellerPhone.trim()
        if (raw.isBlank()) {
            _state.update { it.copy(toast = "Введите телефон продавца") }
            return
        }
        if (_state.value.auto.sellerRunning) return
        _state.update { it.copy(auto = it.auto.copy(sellerRunning = true, sellerError = null, sellerReport = null)) }
        viewModelScope.launch {
            val outcome = runCatching { agentRunner.run(raw) }
            outcome.onSuccess { report ->
                _state.update { it.copy(auto = it.auto.copy(sellerRunning = false, sellerReport = report)) }
            }.onFailure { error ->
                _state.update { it.copy(auto = it.auto.copy(sellerRunning = false, sellerError = error.message ?: "Ошибка проверки")) }
            }
        }
    }

    /** Обратный поиск по публичной ссылке на изображение (Google Lens / Bing через SerpAPI). */
    fun setPhotoUrlQuery(value: String) = _state.update {
        it.copy(photoSearch = it.photoSearch.copy(urlQuery = value, urlError = null))
    }

    fun runPhotoUrlSearch() {
        val url = _state.value.photoSearch.urlQuery.trim()
        if (!url.startsWith("https://")) {
            _state.update { it.copy(photoSearch = it.photoSearch.copy(urlError = "Нужна публичная ссылка https:// на изображение")) }
            return
        }
        val key = keys.key("serpapi")
        if (key.isBlank()) {
            _state.update { it.copy(photoSearch = it.photoSearch.copy(urlError = "Для поиска по ссылке нужен ключ SerpAPI")) }
            return
        }
        if (_state.value.photoSearch.urlBusy) return
        _state.update { it.copy(photoSearch = it.photoSearch.copy(urlBusy = true, urlError = null, urlMatches = emptyList())) }
        viewModelScope.launch {
            val outcome = runCatching { PhotoSearch.reverseByUrl(url, key) }
            outcome.onSuccess { matches ->
                _state.update {
                    it.copy(
                        photoSearch = it.photoSearch.copy(urlBusy = false, urlMatches = matches),
                        toast = if (matches.isEmpty()) "Совпадений по ссылке не найдено" else "Совпадений: ${matches.size}"
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(photoSearch = it.photoSearch.copy(urlBusy = false, urlError = error.message ?: "Ошибка запроса")) }
            }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    // ------------------------------------------------------------- документы досье

    fun openDocs() {
        _state.update {
            it.copy(docs = it.docs.copy(open = true, items = docStore.list(), note = null))
        }
    }

    fun closeDocs() = _state.update { it.copy(docs = it.docs.copy(open = false)) }

    fun setDocsQuery(raw: String) {
        val query = raw.take(80)
        _state.update { it.copy(docs = it.docs.copy(query = query)) }
        if (DocSearch.terms(query).isEmpty()) {
            _state.update { it.copy(docs = it.docs.copy(hits = emptyList())) }
            return
        }
        viewModelScope.launch {
            val hits = withContext(Dispatchers.IO) { DocSearch.search(docStore.texts(), query) }
            _state.update { it.copy(docs = it.docs.copy(hits = hits)) }
        }
    }

    /**
     * Импорт файла: текст извлекается локально (TXT/MD/HTML/DOCX), PDF и офисные
     * форматы — через серверный MarkItDown, если сервер включён. Файл остаётся
     * в приватной папке приложения, наружу уходят только байты для извлечения текста.
     */
    fun importDocument(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(docs = it.docs.copy(busy = true, note = null)) }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = displayName(uri) ?: "document.txt"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Не удалось прочитать файл")
                    if (bytes.isEmpty()) error("Файл пустой")
                    if (bytes.size > DocumentText.MAX_BYTES) error("Файл больше 12 МБ")

                    val local = DocumentText.extract(name, bytes)?.trim()
                    var text: String? = local
                    var provider = "local"
                    var note = ""
                    if (local.isNullOrBlank()) {
                        val base = settings.serverUrl
                        if (settings.serverEnabled && base.isNotBlank()) {
                            runCatching {
                                Remote.extractDocument(
                                    base,
                                    secure.get("server_key").orEmpty(),
                                    name,
                                    bytes
                                )
                            }.onSuccess { extracted ->
                                text = extracted.text.trim()
                                // Сервер сам говорит, что это было: OCR скана или обычный разбор.
                                provider = if (extracted.provider == "ocr") "ocr" else "server"
                            }.onFailure { error ->
                                note = "Сервер: ${error.message ?: "текст не извлечён"}"
                            }
                        } else {
                            note = "Формат требует серверного режима — включите сервер в настройках"
                        }
                    }
                    if (text.isNullOrBlank() && note.isBlank()) {
                        note = "Текст не найден (возможно, скан без распознавания)"
                    }
                    docStore.add(
                        title = DocumentText.title(name),
                        fileName = name,
                        bytes = bytes,
                        text = text.orEmpty(),
                        provider = provider,
                        note = note
                    )
                }
            }
            outcome.onSuccess { record ->
                _state.update {
                    it.copy(
                        docs = it.docs.copy(
                            busy = false,
                            items = docStore.list(),
                            hits = emptyList(),
                            query = "",
                            note = if (record.note.isBlank()) "Добавлено: ${record.title}"
                            else record.note
                        )
                    )
                }
            }
            outcome.onFailure { error ->
                _state.update {
                    it.copy(
                        docs = it.docs.copy(
                            busy = false,
                            note = error.message ?: "Импорт не удался"
                        )
                    )
                }
            }
        }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { docStore.delete(id) }
            _state.update {
                it.copy(
                    docs = it.docs.copy(
                        items = docStore.list(),
                        hits = it.docs.hits.filterNot { hit -> hit.doc.id == id }
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------- сверка фактов

    // ------------------------------------------------------------- утечки

    fun openLeakScan() {
        _state.update {
            it.copy(
                leak = it.leak.copy(
                    open = true,
                    useApi = settings.leakUseApi,
                    useIndex = settings.leakUseIndex,
                    rawScan = settings.leakRaw,
                    note = null
                )
            )
        }
        refreshLeakStats()
    }

    fun closeLeakScan() = _state.update { it.copy(leak = it.leak.copy(open = false)) }

    private fun refreshLeakStats() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                runCatching { leakStore.stats() }.getOrNull()
            } ?: return@launch
            _state.update {
                it.copy(
                    leak = it.leak.copy(
                        fileCount = stats.files,
                        rowCount = stats.rows,
                        secretRows = stats.secrets,
                        sizeMb = (stats.bytes / (1024 * 1024)).toInt()
                    )
                )
            }
        }
    }

    fun setLeakUseApi(value: Boolean) {
        settings.leakUseApi = value
        _state.update { it.copy(leak = it.leak.copy(useApi = value)) }
    }

    fun setLeakUseIndex(value: Boolean) {
        settings.leakUseIndex = value
        _state.update { it.copy(leak = it.leak.copy(useIndex = value)) }
    }

    fun setLeakRaw(value: Boolean) {
        settings.leakRaw = value
        _state.update { it.copy(leak = it.leak.copy(rawScan = value)) }
    }

    fun setLeakQuery(raw: String) =
        _state.update { it.copy(leak = it.leak.copy(query = raw.take(120))) }

    /** Переиндексация папки leaks/. Неизменившиеся файлы пропускаются. */
    fun indexLeaks() {
        if (_state.value.leak.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(leak = it.leak.copy(busy = true, stage = "Индексация…", note = null))
            }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    leakStore.index(LeakStore.leaksDir(getApplication())) { name, percent ->
                        _state.update { it.copy(leak = it.leak.copy(stage = "$name · $percent%")) }
                    }
                }
            }
            outcome.onSuccess { report ->
                val stats = withContext(Dispatchers.IO) { leakStore.stats() }
                _state.update {
                    it.copy(
                        leak = it.leak.copy(
                            busy = false,
                            stage = "Индекс: ${report.scanned} новых, ${report.skipped} без изменений",
                            note = buildIndexNote(report, stats.files),
                            fileCount = stats.files,
                            rowCount = stats.rows,
                            secretRows = stats.secrets,
                            sizeMb = (stats.bytes / (1024 * 1024)).toInt()
                        )
                    )
                }
            }
            outcome.onFailure { error ->
                _state.update {
                    it.copy(
                        leak = it.leak.copy(
                            busy = false,
                            note = "Индексация: ${error.message ?: "ошибка"}"
                        )
                    )
                }
            }
        }
    }

    private fun buildIndexNote(report: LeakIndexReport, files: Int): String = buildString {
        append("Файлов в индексе: $files")
        if (report.rows > 0) append(", строк: ${report.rows}")
        append(". ${report.tookMs} мс")
        if (report.errors.isNotEmpty()) {
            append(". Ошибки: ${report.errors.take(2).joinToString("; ")}")
        }
    }

    /** Дамп приходит через SAF и копируется в приватную папку приложения. */
    fun importLeakFile(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(leak = it.leak.copy(busy = true, stage = "Копирую файл…", note = null))
            }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = LeakStore.safeName(displayName(uri) ?: "leak.txt")
                    if (!LeakRules.indexable(name)) {
                        error("Поддерживаются CSV, TSV, TXT, SQL и JSON")
                    }
                    val target = java.io.File(LeakStore.leaksDir(getApplication()), name)
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                    } ?: error("Не удалось прочитать файл")
                    target
                }
            }
            outcome.onSuccess { file ->
                _state.update {
                    it.copy(
                        leak = it.leak.copy(
                            busy = false,
                            stage = "Добавлено: ${file.name}",
                            note = "Файл ${file.length() / 1024} КБ в папке leaks/"
                        )
                    )
                }
                indexLeaks()
            }
            outcome.onFailure { error ->
                _state.update {
                    it.copy(leak = it.leak.copy(busy = false, note = error.message ?: "Файл не добавлен"))
                }
            }
        }
    }

    /** Одна кнопка: индекс + кэш + каскад API, без дублей. */
    fun runLeakScan() {
        val leak = _state.value.leak
        if (leak.busy) return
        val subject = leak.query.trim().ifBlank { _state.value.query.trim() }
        if (subject.isBlank()) {
            _state.update { it.copy(toast = "Введите почту, телефон или ник") }
            return
        }
        if (!leak.useApi && !leak.useIndex) {
            _state.update { it.copy(toast = "Включите онлайн-API или локальный индекс") }
            return
        }
        viewModelScope.launch {
            if (leak.rawScan) {
                _state.update {
                    it.copy(leak = it.leak.copy(busy = true, stage = "Полная переиндексация…"))
                }
                runCatching {
                    withContext(Dispatchers.IO) {
                        leakStore.index(LeakStore.leaksDir(getApplication()), force = true) { name, percent ->
                            _state.update { it.copy(leak = it.leak.copy(stage = "$name · $percent%")) }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    leak = it.leak.copy(
                        busy = true,
                        stage = "Поиск…",
                        sources = emptyList(),
                        note = null,
                        offline = false,
                        cached = false,
                        query = subject
                    )
                )
            }
            val outcome = runCatching {
                leakScan.scan(subject, leak.useIndex, leak.useApi) { stage ->
                    _state.update { it.copy(leak = it.leak.copy(stage = stage)) }
                }
            }
            outcome.onSuccess { result ->
                val groups = result.sources.map { row ->
                    LeakSourceUi(
                        id = row.id,
                        title = row.title,
                        hits = row.hits,
                        cached = row.cached,
                        note = row.error,
                        rows = result.facts.filter { it.sourceId == row.id }.take(20).map { fact ->
                            LeakRowUi(
                                value = fact.value,
                                where = fact.attributes["Файл"]?.let { file ->
                                    "$file · стр. ${fact.attributes["Строка"] ?: "?"}"
                                } ?: fact.attributes["Дата"].orEmpty(),
                                secret = fact.attributes["Пароль в строке"]?.startsWith("есть") == true
                            )
                        }
                    )
                }
                _state.update {
                    it.copy(
                        leak = it.leak.copy(
                            busy = false,
                            stage = "Готово: ${result.tookMs} мс",
                            note = result.note,
                            offline = result.offline,
                            cached = result.cached,
                            sources = groups
                        )
                    )
                }
            }
            outcome.onFailure { error ->
                _state.update {
                    it.copy(
                        leak = it.leak.copy(
                            busy = false,
                            stage = "Поиск прерван",
                            note = error.message ?: "Ошибка поиска"
                        )
                    )
                }
            }
        }
    }

    /** В отчёте только маски — экспорт ничего не раскрывает. */
    fun exportLeakJson() {
        val leak = _state.value.leak
        if (leak.sources.isEmpty()) {
            _state.update { it.copy(toast = "Сначала выполните поиск") }
            return
        }
        viewModelScope.launch {
            val root = JSONObject()
                .put("schema", "jb-osint/leak-scan/1.0")
                .put("query", leak.query)
                .put("createdAt", System.currentTimeMillis())
                .put("offline", leak.offline)
                .put("cached", leak.cached)
                .put("note", leak.note.orEmpty())
            val sources = JSONArray()
            leak.sources.forEach { source ->
                val rows = JSONArray()
                source.rows.forEach { row ->
                    rows.put(
                        JSONObject()
                            .put("value", row.value)
                            .put("where", row.where)
                            .put("hadPassword", row.secret)
                    )
                }
                sources.put(
                    JSONObject()
                        .put("id", source.id)
                        .put("title", source.title)
                        .put("hits", source.hits)
                        .put("cached", source.cached)
                        .put("rows", rows)
                )
            }
            root.put("sources", sources)
            val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date())
            val saved = runCatching { exporter.save("leak-scan-$stamp.json", bytes, "application/json") }
            saved.onSuccess { exported ->
                _state.update { it.copy(toast = "Отчёт готов: ${exported.fileName}") }
                _shareEvents.emit(ShareRequest(exported.uri, exported.mime, exported.fileName))
            }.onFailure { error ->
                _state.update { it.copy(toast = "Экспорт не удался: ${error.message ?: "ошибка"}") }
            }
        }
    }

    fun openCrossCheck() {
        val data = _state.value.result
        if (data == null) {
            _state.update { it.copy(toast = "Сначала выполните поиск") }
            return
        }
        val facts = Remote.analyzeFacts(data.facts)
        _state.update {
            it.copy(
                crossCheck = it.crossCheck.copy(
                    open = true,
                    busy = false,
                    facts = facts.size,
                    result = null,
                    error = null
                )
            )
        }
    }

    fun closeCrossCheck() = _state.update { it.copy(crossCheck = it.crossCheck.copy(open = false)) }

    fun runCrossCheck() {
        val data = _state.value.result ?: return
        val base = settings.serverUrl
        if (!settings.serverEnabled || base.isBlank()) {
            _state.update {
                it.copy(
                    crossCheck = it.crossCheck.copy(
                        error = "Серверный режим выключен — включите сервер в настройках"
                    )
                )
            }
            return
        }
        val facts = Remote.analyzeFacts(data.facts)
        if (facts.isEmpty()) {
            _state.update { it.copy(crossCheck = it.crossCheck.copy(error = "Нет фактов для сверки")) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(crossCheck = it.crossCheck.copy(busy = true, error = null, facts = facts.size))
            }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    Remote.analyze(base, secure.get("server_key").orEmpty(), facts)
                }
            }
            outcome.onSuccess { result ->
                _state.update { it.copy(crossCheck = it.crossCheck.copy(busy = false, result = result)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        crossCheck = it.crossCheck.copy(
                            busy = false,
                            error = error.message ?: "Сверка не удалась"
                        )
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- документы досье

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()


}
