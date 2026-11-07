package ru.jbosint.osint.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.BuildConfig
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.data.AssistantCatalog
import ru.jbosint.osint.data.AssistantClassification
import ru.jbosint.osint.data.AssistantParser
import ru.jbosint.osint.data.AssistantPrompts
import ru.jbosint.osint.data.AssistantRole
import ru.jbosint.osint.data.AssistantService
import ru.jbosint.osint.data.AssistantStore
import ru.jbosint.osint.data.WebHit
import ru.jbosint.osint.data.WebResearch
import ru.jbosint.osint.data.CustomAuth
import ru.jbosint.osint.data.CustomConnectionProbe
import ru.jbosint.osint.data.CustomModule
import ru.jbosint.osint.data.CustomModuleChecker
import ru.jbosint.osint.data.AutoSetup
import ru.jbosint.osint.data.Connectors
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
import ru.jbosint.osint.data.PhotoAnalysis
import ru.jbosint.osint.data.PhotoAnalyzer
import ru.jbosint.osint.data.QueryDetector
import ru.jbosint.osint.data.ReverseImage
import ru.jbosint.osint.data.Net
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.data.FieldRule
import ru.jbosint.osint.data.KeyCheck
import ru.jbosint.osint.data.HistoryEntry
import ru.jbosint.osint.data.HistoryStore
import ru.jbosint.osint.data.KeyRepository
import ru.jbosint.osint.data.ConnectionUi
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
import ru.jbosint.osint.report.ReportExporter

enum class Screen { HOME, RESULTS, KEYS, SETTINGS }

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
    val repairing: Boolean = false,
    val repairLog: List<String> = emptyList(),
    val connection: ConnectionUi? = null,
    val autoSetup: AutoSetupUi? = null,
    val manualDraft: CustomModule? = null,
    val manualKey: String = "",
    val toast: String? = null,
    // ---------------------------------------------- интеллектуальный ассистент
    val assistantConfigured: Boolean = false,
    val assistantProvider: String = AssistantCatalog.DEFAULT_PROVIDER,
    val assistantBaseUrl: String = "",
    /** Поставщики, у которых уже сохранён ключ (точки на переключателе). */
    val assistantKeyed: Set<String> = emptySet(),
    val assistantModel: String = AssistantCatalog.DEFAULT_MODEL,
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
    val classifications: Map<String, AssistantClassification> = emptyMap()
) {
    val activeModules: Int
        get() = providers.count { it.enabled && it.hasKey } + customModules.count { it.enabled && (it.hasKey || it.auth == CustomAuth.NONE) }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val keys = KeyRepository(app)
    private val historyStore = HistoryStore(app)
    private val exporter = ReportExporter(app)
    private val validator = KeyValidator(keys)
    private val customStore = CustomModuleStore(app)
    private val customChecker = CustomModuleChecker(customStore)
    private val engine = SearchEngine(keys, settings)
    private val assistantStore = AssistantStore(app)
    private val assistantService = AssistantService(assistantStore)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _shareEvents = MutableSharedFlow<ShareRequest>(extraBufferCapacity = 4)
    val shareEvents = _shareEvents.asSharedFlow()

    init {
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
            assistantState = assistantStore.state(),
            assistantCheckedAt = assistantStore.checkedAt(),
            assistantAutoAnalyze = assistantStore.autoAnalyze,
            assistantAutoClassify = assistantStore.autoClassify,
            assistantAutoQueries = assistantStore.autoQueries,
            assistantPolish = assistantStore.polishReport,
            autoResearch = assistantStore.autoResearch
        )
    }

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

    fun navigate(screen: Screen) = _state.update {
        it.copy(screen = screen, searchError = null)
    }

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
                val handles = PhotoAnalyzer.handlesFrom(hits.map { it.url }).take(3)
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
                val note = buildPhotoNote(photo, hits.size, handles, key.isNotBlank())
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
        providerReady: Boolean
    ): String {
        val parts = mutableListOf<String>()
        parts += "Снимок: ${photo.summary()}."
        if (photo.exif["Координаты"] != null) parts += "В файле сохранена геометка — она включена в отчёт."
        parts += when {
            !providerReady -> "Обратный поиск по изображению не выполнялся: не добавлен ключ модуля SauceNAO."
            hits == 0 -> "Обратный поиск выполнен, совпадений выше порога не найдено."
            else -> "Обратный поиск: $hits совпадений, ссылки добавлены в доказательную базу."
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
            val query = queryOverride?.trim()?.takeIf { it.isNotBlank() }
                ?: _state.value.query.trim().takeIf { it.isNotBlank() }
                ?: "osint"
            val blocks = ArrayList<DiagBlock>()
            val startedAt = System.currentTimeMillis()
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
                    testQuery = probeQuery.takeIf { it != query }
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
                    advice = advice
                )
            }

            val report = if (blocks.isEmpty()) {
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
            _state.update { it.copy(diagRunning = false, diagReport = report, diagBlocks = blocks) }
        }
    }

    fun clearDiagnostics() = _state.update { it.copy(diagReport = null) }

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
        val context = entry.data.facts.take(6).joinToString("; ") { "${it.kind}: ${it.value}" }
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

    fun consumeToast() = _state.update { it.copy(toast = null) }
}
