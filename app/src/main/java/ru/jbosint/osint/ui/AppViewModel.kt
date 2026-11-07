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
import ru.jbosint.osint.data.JsonShape
import ru.jbosint.osint.data.Net
import ru.jbosint.osint.core.model.RawFact
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
    val diagRunning: Boolean = false,
    val diagReport: String? = null,
    val autoSetup: AutoSetupUi? = null,
    val manualDraft: CustomModule? = null,
    val manualKey: String = "",
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
                        suggested = suggested, exhausted = outcome.exhausted
                    )
                )
            }
        }
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
                val t0 = System.currentTimeMillis()
                val facts = if (check.state == KeyState.VALID) {
                    runCatching { Connectors.byId(spec.id)?.collect(query, keys) ?: emptyList() }
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
                val advice = when {
                    check.state == KeyState.VALID && facts.isNotEmpty() -> "работает"
                    check.state == KeyState.VALID -> "ключ принят, но фактов 0: по запросу «$query» сервис ничего не вернул"
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
                    advice = advice
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
            _state.update { it.copy(diagRunning = false, diagReport = report) }
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
