package ru.jbosint.osint.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.data.CustomAuth
import ru.jbosint.osint.data.CustomConnectionProbe
import ru.jbosint.osint.data.CustomModule
import ru.jbosint.osint.data.CustomModuleChecker
import ru.jbosint.osint.data.CustomModuleSpec
import ru.jbosint.osint.data.CustomModuleStore
import ru.jbosint.osint.data.FieldRule
import ru.jbosint.osint.data.KeyCheck
import ru.jbosint.osint.data.HistoryEntry
import ru.jbosint.osint.data.HistoryStore
import ru.jbosint.osint.data.KeyRepository
import ru.jbosint.osint.data.KeyState
import ru.jbosint.osint.data.KeyValidator
import ru.jbosint.osint.data.ProviderCatalog
import ru.jbosint.osint.data.ProviderSpec
import ru.jbosint.osint.data.SearchEngine
import ru.jbosint.osint.data.SettingsStore
import ru.jbosint.osint.report.ReportExporter

enum class Screen { HOME, RESULTS, KEYS, SETTINGS }

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
    val toast: String? = null
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

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _shareEvents = MutableSharedFlow<ShareRequest>(extraBufferCapacity = 4)
    val shareEvents = _shareEvents.asSharedFlow()

    init {
        // Автоудаление старых отчётов согласно настройке.
        val keepDays = settings.autoWipeDays
        if (keepDays > 0) {
            val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
            historyStore.list().filter { it.createdAt < cutoff }.forEach { historyStore.delete(it.id) }
        }
        _state.value = UiState(
            ready = true,
            screen = Screen.HOME,
            onboarding = settings.onboardingDone,
            analyst = settings.analyst,
            demoMode = settings.demoMode,
            secureScreen = settings.secureScreen,
            autoWipeDays = settings.autoWipeDays,
            history = historyStore.list(),
            providers = buildProviders(),
            customModules = customStore.list()
        )
    }

    private fun buildProviders(): List<ProviderUi> = ProviderCatalog.all.map { spec ->
        ProviderUi(
            spec = spec,
            enabled = keys.isEnabled(spec.id),
            hasKey = keys.key(spec.id).isNotBlank(),
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
        it.copy(screen = Screen.RESULTS, result = entry.data, resultEntry = entry, searchError = null)
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
                searchError = null, result = null, resultEntry = null
            )
        }
        viewModelScope.launch {
            val outcome = runCatching {
                engine.search(query, { stage, progress ->
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
                    demo = settings.demoMode
                )
                withContext(Dispatchers.IO) { historyStore.save(entry) }
                _state.update {
                    it.copy(
                        searching = false,
                        progress = 100,
                        stage = "Готово",
                        result = data,
                        resultEntry = entry,
                        screen = Screen.RESULTS,
                        history = historyStore.list()
                    )
                }
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

    // ------------------------------------------------------------------ история

    fun deleteHistory(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyStore.delete(id) }
            _state.update { it.copy(history = historyStore.list()) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyStore.clear() }
            _state.update { it.copy(history = emptyList()) }
        }
    }

    // ------------------------------------------------------------------- ключи

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
