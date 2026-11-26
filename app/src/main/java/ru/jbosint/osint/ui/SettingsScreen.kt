package ru.jbosint.osint.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.BuildConfig
import ru.jbosint.osint.data.AssistantCatalog
import ru.jbosint.osint.data.PerplexityAgent
import ru.jbosint.osint.data.KeyState
import ru.jbosint.osint.data.TermuxCheck
import ru.jbosint.osint.data.TermuxSpec

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmHistory by remember { mutableStateOf(false) }
    var confirmLeakPasswords by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ScreenHeader(
                eyebrow = "Система",
                title = "Настройки",
                subtitle = "Параметры отчётов, защита и хранение данных на устройстве."
            )
        }

        item {
            SectionHeader("Отчётность")
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                Text("Имя аналитика", style = MaterialTheme.typography.titleSmall, color = JbText)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Указывается на титульном листе PDF, в шапке Excel и в метаданных JSON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.analyst,
                    onValueChange = vm::setAnalyst,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Не указано", color = JbFaint) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors()
                )
            }
        }

        item {
            SectionHeader("Источники")
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                ToggleRow(
                    title = "Демонстрационный режим",
                    subtitle = "Результаты формируются без обращения к внешним сервисам — для проверки отчётов и обучения.",
                    checked = state.demoMode,
                    onCheckedChange = vm::setDemoMode
                )
                Spacer(Modifier.height(8.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Активных модулей: ${state.activeModules}",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.navigate(Screen.KEYS) }) {
                        Text("Управление ключами", color = JbAccent2, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        item {
            SectionHeader("Утечки")
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                ToggleRow(
                    title = "Показывать пароли в отчётах утечек",
                    subtitle = "Пароль из локального индекса дампов виден в карточке и попадает в JSON-экспорт. " +
                        "CSV и HTML не содержат пароль ни при каком режиме. По умолчанию выключено.",
                    checked = state.leak.showPasswords,
                    onCheckedChange = { enabled ->
                        if (enabled) confirmLeakPasswords = true else vm.setLeakShowPasswords(false)
                    }
                )
            }
        }

        item {
            SectionHeader("Модули")
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                ActionRow(
                    icon = Icons.Filled.Build,
                    title = "Мастер настройки модулей",
                    subtitle = "Пошагово выбрать, проверить и настроить ключи; скрыть или удалить модули.",
                    onClick = { vm.openModuleWizard() }
                )
            }
        }

        item {
            SectionHeader("GitHub-лаборатория")
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                ActionRow(
                    icon = Icons.Filled.Science,
                    title = "GitHub Лаборатория",
                    subtitle = "Клон репозитория, поиск скриптов и запуск через локальный сервер Termux.",
                    onClick = { vm.navigate(Screen.LAB) }
                )
            }
        }

        item { TermuxSection(state, vm) }

        item { ServerSection(state, vm) }

        item { AssistantSection(state, vm) }

        item { PluginSection { vm.navigate(Screen.PLUGINS) } }

        item { AccessSection(state, vm) }

        if (state.canManage) {
            item { BackupSection(state, vm) }
        }

        item {
            SectionHeader("Защита")
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                ToggleRow(
                    title = "Запрет скриншотов",
                    subtitle = "FLAG_SECURE: экран приложения скрывается в списке задач и не попадает в снимки.",
                    checked = state.secureScreen,
                    onCheckedChange = vm::setSecureScreen
                )
                Spacer(Modifier.height(10.dp))
                GradientDivider()
                Spacer(Modifier.height(12.dp))
                Text("Автоудаление истории", style = MaterialTheme.typography.titleSmall, color = JbText)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Отчёты старше указанного срока удаляются при запуске приложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30, 90).forEach { days ->
                        DayChip(
                            text = "$days дней",
                            selected = state.autoWipeDays == days,
                            onClick = { vm.setAutoWipeDays(days) }
                        )
                    }
                    DayChip(
                        text = "Не удалять",
                        selected = state.autoWipeDays == 0,
                        onClick = { vm.setAutoWipeDays(0) }
                    )
                }
            }
        }

        item {
            SectionHeader("Данные")
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                ActionRow(
                    icon = Icons.Filled.DeleteSweep,
                    title = "Очистить историю",
                    subtitle = "Удалить сохранённые исследования (ключи останутся).",
                    onClick = { confirmHistory = true }
                )
                Spacer(Modifier.height(10.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                ActionRow(
                    icon = Icons.Filled.Lock,
                    title = "Удалить все ключи",
                    subtitle = "Отключить модули и стереть зашифрованные ключи.",
                    onClick = { confirmWipe = true }
                )
                Spacer(Modifier.height(10.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                ActionRow(
                    icon = Icons.Filled.Info,
                    title = "Полный сброс",
                    subtitle = "Удалить ключи, историю и настройки без возможности восстановления.",
                    onClick = { confirmWipe = true }
                )
            }
        }

        item {
            SectionHeader("О приложении")
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(38)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("JB OSINT", style = MaterialTheme.typography.titleMedium, color = JbText)
                        Text(
                            "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.labelSmall,
                            color = JbFaint
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Сбор сведений из открытых источников, триангуляция и подготовка аналитических отчётов. " +
                        "Все данные обрабатываются локально на устройстве; ключи не покидают защищённое хранилище.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
            }
        }
    }

    if (confirmHistory) {
        AlertDialog(
            onDismissRequest = { confirmHistory = false },
            containerColor = JbSurfaceHigh,
            titleContentColor = JbText,
            textContentColor = JbMuted,
            title = { Text("Очистить историю?") },
            text = { Text("Все сохранённые исследования будут удалены. Ключи API останутся на месте.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    confirmHistory = false
                }) { Text("Удалить", color = JbRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmHistory = false }) { Text("Отмена", color = JbMuted) }
            }
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = JbSurfaceHigh,
            titleContentColor = JbText,
            textContentColor = JbMuted,
            title = { Text("Удалить ключи и данные?") },
            text = { Text("Ключи API и сохранённая история будут стёрты. Действие необратимо.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.wipeEverything()
                    confirmWipe = false
                }) { Text("Стереть", color = JbRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Отмена", color = JbMuted) }
            }
        )
    }

    if (confirmLeakPasswords) {
        AlertDialog(
            onDismissRequest = { confirmLeakPasswords = false },
            containerColor = JbSurfaceHigh,
            titleContentColor = JbText,
            textContentColor = JbMuted,
            title = { Text("Показывать пароли?") },
            text = {
                Text(
                    "Пароли из локального индекса дампов будут видны в карточках утечек и попадут " +
                        "в JSON-экспорт. Их можно будет скопировать. Включайте только если понимаете " +
                        "риск утечки: CSV и HTML пароли не содержат."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setLeakShowPasswords(true)
                    confirmLeakPasswords = false
                }) { Text("Включить", color = JbAmber) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeakPasswords = false }) { Text("Отмена", color = JbMuted) }
            }
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = JbText)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JbMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = JbAccent,
                uncheckedThumbColor = JbMuted,
                uncheckedTrackColor = JbSurfaceHigh,
                uncheckedBorderColor = JbBorder
            )
        )
    }
}

@Composable
internal fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = JbMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = JbText)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JbMuted)
        }
    }
}

@Composable
private fun DayChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color(0xFFD5D9FF) else JbMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) JbAccent.copy(alpha = 0.22f) else JbSurface)
            .border(
                1.dp,
                if (selected) JbAccent.copy(alpha = 0.55f) else JbBorder,
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Блок «Локальный бэкенд»: Termux, диагностика, скрипт настройки и белый список. */
@Composable
private fun TermuxSection(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val termux = state.termux
    var showScript by remember { mutableStateOf(false) }
    var newScript by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var uiNote by remember { mutableStateOf<String?>(null) }
    var homeDraft by remember(termux.home) { mutableStateOf(termux.home) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        uiNote = if (granted) "Разрешение RUN_COMMAND выдано" else "Разрешение не выдано"
    }
    val openTermux: () -> Unit = {
        val launch = context.packageManager.getLaunchIntentForPackage(TermuxSpec.PACKAGE)
        if (launch == null) {
            uiNote = "Termux не установлен: установите из F-Droid"
        } else {
            runCatching { context.startActivity(launch) }
                .onFailure { uiNote = it.message ?: "Не удалось открыть Termux" }
        }
    }
    val setup = remember(termux.home, showScript) { vm.termuxSetupScript() }

    SectionHeader("Локальный бэкенд (Termux)")
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Text(
            "Termux расширяет локальные возможности: Crawl4AI, MarkItDown, OCR документов и " +
                "Reverse API Engineer. Мост работает через официальный RUN_COMMAND и только для " +
                "скриптов из белого списка — произвольный код приложение не выполняет.",
            style = MaterialTheme.typography.bodySmall,
            color = JbMuted
        )
        Spacer(Modifier.height(10.dp))
        ToggleRow(
            title = "Termux-мост",
            subtitle = "Диагностика окружения и запуск доверенных Python-скриптов на телефоне.",
            checked = termux.enabled,
            onCheckedChange = vm::setTermuxEnabled
        )

        if (termux.enabled) {
            Spacer(Modifier.height(8.dp))
            GradientDivider()
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::runTermuxDiagnostics, enabled = !termux.checking) {
                    Text(
                        if (termux.checking) "Проверка…" else "Диагностика Termux",
                        color = if (termux.checking) JbFaint else JbAccent2,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (!TermuxSpec.isPermissionGranted(context)) {
                    TextButton(onClick = { permission.launch(TermuxSpec.PERMISSION) }) {
                        Text("Разрешить", color = JbAmber, style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(onClick = openTermux) {
                    Text("Открыть Termux", color = JbMuted, style = MaterialTheme.typography.labelMedium)
                }
            }

            (uiNote ?: termux.note)?.let { note ->
                Text(note, style = MaterialTheme.typography.labelSmall, color = JbAmber)
                Spacer(Modifier.height(6.dp))
            }

            termux.checks.forEach { check ->
                TermuxCheckRow(check)
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(2.dp))
            OutlinedTextField(
                value = homeDraft,
                onValueChange = { homeDraft = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Путь к Termux", color = JbFaint) },
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    TextButton(onClick = { vm.setTermuxHome(homeDraft) }, enabled = homeDraft.isNotBlank()) {
                        Text("Применить", color = JbAccent2, style = MaterialTheme.typography.labelSmall)
                    }
                }
            )

            Spacer(Modifier.height(10.dp))
            ActionRow(
                icon = Icons.Filled.ContentCopy,
                title = "Скрипт настройки",
                subtitle = "Crawl4AI, MarkItDown, RAE и общая папка — одной командой в Termux.",
                onClick = { showScript = true }
            )
            Spacer(Modifier.height(8.dp))
            GradientDivider()
            Spacer(Modifier.height(10.dp))

            Text("Доверенные скрипты", style = MaterialTheme.typography.titleSmall, color = JbText)
            Spacer(Modifier.height(3.dp))
            Text(
                "Запускаются из папки ~/JB_OSINT_Scripts. Остальные файлы приложению недоступны.",
                style = MaterialTheme.typography.bodySmall,
                color = JbMuted
            )
            Spacer(Modifier.height(6.dp))
            termux.trusted.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = JbSlate,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { vm.runTermuxScript(name) },
                        enabled = termux.running == null
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = if (termux.running == null) JbAccent2 else JbFaint,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (termux.running == name) "Запущен…" else "Запустить",
                            color = if (termux.running == null) JbAccent2 else JbFaint,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = { vm.removeTermuxScript(name) }) {
                        Text("×", color = JbFaint, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newScript,
                    onValueChange = { newScript = it.trim() },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("crawl4ai_wrapper.py", color = JbFaint) },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors()
                )
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = {
                        val error = vm.addTermuxScript(newScript)
                        addError = error
                        if (error == null) newScript = ""
                    },
                    enabled = newScript.isNotBlank()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = JbAccent2, modifier = Modifier.size(16.dp))
                    Text("Добавить", color = JbAccent2, style = MaterialTheme.typography.labelSmall)
                }
            }
            addError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = JbRed)
            }
        }
    }

    if (showScript) {
        AlertDialog(
            onDismissRequest = { showScript = false },
            containerColor = JbSurfaceHigh,
            titleContentColor = JbText,
            textContentColor = JbMuted,
            title = { Text("Настройка Termux") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "1. Установите Termux из F-Droid (рекомендуется) или Google Play.\n" +
                            "2. Нажмите «Копировать» и вставьте скрипт в Termux.\n" +
                            "3. Дождитесь завершения и вернитесь в приложение.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Скрипт идемпотентен: повторный запуск не клонирует репозиторий заново.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(setup, style = MaterialTheme.typography.labelSmall, color = JbSlate)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(setup))
                    uiNote = "Скрипт скопирован в буфер"
                }) { Text("Копировать", color = JbAccent2) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = openTermux) { Text("Открыть Termux", color = JbMuted) }
                    TextButton(onClick = { showScript = false }) { Text("Закрыть", color = JbMuted) }
                }
            }
        )
    }
}

@Composable
private fun TermuxCheckRow(check: TermuxCheck) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (check.ok) JbGreen else JbRed)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                check.title,
                style = MaterialTheme.typography.labelMedium,
                color = if (check.ok) JbText else JbAmber
            )
            Text(check.detail, style = MaterialTheme.typography.labelSmall, color = JbFaint)
            if (!check.ok && check.fix.isNotBlank()) {
                Text(check.fix, style = MaterialTheme.typography.labelSmall, color = JbMuted)
            }
        }
    }
}

/** Блок «Серверный ускоритель»: гибридный режим и fail-safe. */
@Composable
private fun ServerSection(state: UiState, vm: AppViewModel) {
    var urlDraft by remember(state.server.url) { mutableStateOf(state.server.url) }
    var keyDraft by remember { mutableStateOf("") }
    val server = state.server

    SectionHeader("Серверный ускоритель")
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Text(
            "Опциональный сервер для тяжёлых задач: обход JS/Cloudflare, RAE со сложной " +
                "авторизацией, cross-check отчётов. Без сервера приложение работает локально — " +
                "облачные функции просто отключаются.",
            style = MaterialTheme.typography.bodySmall,
            color = JbMuted
        )
        Spacer(Modifier.height(10.dp))
        ToggleRow(
            title = "Гибридный режим",
            subtitle = "При недоступности сервера поиск и отчёты продолжают работать на устройстве.",
            checked = server.enabled,
            onCheckedChange = vm::setServerEnabled
        )

        if (server.enabled) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Адрес сервера, https", color = JbFaint) },
                placeholder = { Text("https://api.example.com", color = JbFaint) },
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    TextButton(onClick = { vm.setServerUrl(urlDraft) }, enabled = urlDraft.isNotBlank()) {
                        Text("Применить", color = JbAccent2, style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        if (server.keyed) "X-API-Key сохранён — вставьте новый, чтобы заменить"
                        else "X-API-Key сервера",
                        color = JbFaint
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        vm.saveServerKey(keyDraft)
                        keyDraft = ""
                    },
                    enabled = keyDraft.length >= 8
                ) { Text("Сохранить ключ", color = if (keyDraft.length >= 8) JbAccent2 else JbFaint) }
                TextButton(onClick = vm::checkServer, enabled = !server.checking) {
                    Text(if (server.checking) "Проверка…" else "Проверить связь", color = JbAccent2)
                }
                if (server.keyed) {
                    TextButton(onClick = vm::clearServerKey) {
                        Text("Удалить ключ", color = JbRed)
                    }
                }
            }
            val note = server.note
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (server.online) JbGreen else JbAmber
                )
            } else {
                Text(
                    if (server.keyed) "Ключ сохранён" else "Ключ не задан",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
        }
    }
}

/** Блок «Интеллектуальный ассистент»: ключ, модель и сценарии использования. */
@Composable
private fun AssistantSection(state: UiState, vm: AppViewModel) {
    var keyDraft by remember { mutableStateOf("") }
    var modelDraft by remember(state.assistantModel) { mutableStateOf(state.assistantModel) }
    val context = LocalContext.current

    val provider = AssistantCatalog.provider(state.assistantProvider)
    var baseDraft by remember(state.assistantBaseUrl) { mutableStateOf(state.assistantBaseUrl) }

    SectionHeader("Интеллектуальный ассистент")
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Text("Модель и поставщик", style = MaterialTheme.typography.titleSmall, color = JbText)
        Spacer(Modifier.height(3.dp))
        Text(
            "Ассистент формирует аналитические выводы, размечает типы данных и готовит резюме отчёта. " +
                "Подключите любого OpenAI-совместимого поставщика или свой сервер: запросы уходят только " +
                "по HTTPS на выбранный адрес, ключи разных поставщиков хранятся раздельно в зашифрованном виде.",
            style = MaterialTheme.typography.bodySmall,
            color = JbMuted
        )
        Spacer(Modifier.height(8.dp))
        AssistantCatalog.providers.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    ProviderChip(
                        title = item.title,
                        selected = state.assistantProvider == item.id,
                        hasKey = item.id in state.assistantKeyed,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setAssistantProvider(item.id) }
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (provider.custom) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = baseDraft,
                onValueChange = { baseDraft = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Базовый адрес, https", color = JbFaint) },
                placeholder = { Text("https://api.example.com/v1", color = JbFaint) },
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    TextButton(
                        onClick = { vm.setAssistantBaseUrl(baseDraft) },
                        enabled = baseDraft.isNotBlank()
                    ) { Text("Применить", color = JbAccent2, style = MaterialTheme.typography.labelSmall) }
                }
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(2.dp))
        GradientDivider()
        Spacer(Modifier.height(10.dp))
        Text("Ключ ${provider.title}", style = MaterialTheme.typography.titleSmall, color = JbText)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text(
                    if (state.assistantConfigured) "Ключ сохранён — вставьте новый, чтобы заменить"
                    else provider.keyHint,
                    color = JbFaint
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.assistantChecking -> "Проверка ключа…"
                    !state.assistantConfigured -> "Ключ не задан"
                    state.assistantState == KeyState.VALID -> "Ключ активен"
                    state.assistantState == KeyState.INVALID -> "Ключ отклонён"
                    state.assistantState == KeyState.OFFLINE -> "Нет связи с сервисом"
                    else -> "Ключ сохранён, не проверен"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    state.assistantChecking -> JbMuted
                    !state.assistantConfigured -> JbFaint
                    state.assistantState == KeyState.VALID -> JbGreen
                    state.assistantState == KeyState.INVALID -> JbRed
                    else -> JbAmber
                },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    vm.saveAssistantKey(keyDraft)
                    keyDraft = ""
                },
                enabled = keyDraft.length >= 8
            ) { Text("Сохранить", color = if (keyDraft.length >= 8) JbAccent2 else JbFaint) }
            TextButton(
                onClick = vm::checkAssistantKey,
                enabled = state.assistantConfigured && !state.assistantChecking
            ) { Text("Проверить", color = JbAccent2) }
        }
        if (state.assistantError != null) {
            Text(
                state.assistantError,
                style = MaterialTheme.typography.labelSmall,
                color = JbAmber
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(provider.docsUrl)
                        )
                    )
                }
            }) {
                Text(
                    if (provider.custom) "Документация OpenAI-совместимого API"
                    else "Получить ключ: ${provider.title}",
                    color = JbAccent2,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (state.assistantConfigured) {
                TextButton(onClick = vm::clearAssistantKey) {
                    Text("Удалить ключ", color = JbRed, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        GradientDivider()
        Spacer(Modifier.height(12.dp))

        Text("Модель", style = MaterialTheme.typography.titleSmall, color = JbText)
        Spacer(Modifier.height(3.dp))
        Text(
            if (provider.models.isEmpty()) "Укажите ID модели, который поддерживает ваш сервер."
            else "Модели ${provider.title}. Можно указать любой ID вручную.",
            style = MaterialTheme.typography.bodySmall,
            color = JbMuted
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            provider.models.chunked(2).forEach { rowModels ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowModels.forEach { model ->
                        ModelChip(
                            title = model.title,
                            note = model.note,
                            selected = state.assistantModel == model.id,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.setAssistantModel(model.id) }
                        )
                    }
                    if (rowModels.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (provider.agent) {
            Spacer(Modifier.height(12.dp))
            Text("Уровень усилий Agent API", style = MaterialTheme.typography.titleSmall, color = JbText)
            Spacer(Modifier.height(3.dp))
            Text(
                "Глубина поиска и анализа: чем выше, тем больше источников проверяет сервис.",
                style = MaterialTheme.typography.bodySmall,
                color = JbMuted
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PerplexityAgent.presets.forEach { (id, note) ->
                    ModelChip(
                        title = id,
                        note = note,
                        selected = state.assistantPreset == id,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setAssistantPreset(id) }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = modelDraft,
            onValueChange = { modelDraft = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("ID модели", color = JbFaint) },
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
            trailingIcon = {
                TextButton(onClick = { vm.setAssistantModel(modelDraft) }, enabled = modelDraft.isNotBlank()) {
                    Text("Применить", color = JbAccent2, style = MaterialTheme.typography.labelSmall)
                }
            }
        )

        Spacer(Modifier.height(12.dp))
        GradientDivider()
        Spacer(Modifier.height(12.dp))

        Text("Сценарии", style = MaterialTheme.typography.titleSmall, color = JbText)
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "Анализ цифровых следов",
            subtitle = "После поиска ассистент делает выводы по найденным фактам и связям.",
            checked = state.assistantAutoAnalyze,
            onCheckedChange = vm::setAssistantAutoAnalyze
        )
        Spacer(Modifier.height(10.dp))
        ToggleRow(
            title = "Разметка типов данных",
            subtitle = "Определяет тип и релевантность значений; исходные факты не изменяются.",
            checked = state.assistantAutoClassify,
            onCheckedChange = vm::setAssistantAutoClassify
        )
        Spacer(Modifier.height(10.dp))
        ToggleRow(
            title = "Генерация запросов",
            subtitle = "Перед поиском ассистент уточняет формулировку по введённому значению.",
            checked = state.assistantAutoQueries,
            onCheckedChange = vm::setAssistantAutoQueries
        )
        Spacer(Modifier.height(10.dp))
        ToggleRow(
            title = "Интернет-исследование",
            subtitle = "Поиск в открытом вебе (DuckDuckGo) по запросам ассистента и краткая выжимка.",
            checked = state.autoResearch,
            onCheckedChange = vm::setAutoResearch
        )
        Spacer(Modifier.height(10.dp))
        ToggleRow(
            title = "Оформление резюме отчёта",
            subtitle = "Структурированное резюме по результатам анализа — раздел в PDF, Excel и JSON.",
            checked = state.assistantPolish,
            onCheckedChange = vm::setAssistantPolish
        )
    }
}

/** Компактный переключатель поставщика ИИ. */
@Composable
private fun ProviderChip(
    title: String,
    selected: Boolean,
    hasKey: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) JbAccent.copy(alpha = 0.18f) else JbSurface)
            .border(
                1.dp,
                if (selected) JbAccent.copy(alpha = 0.55f) else JbBorder,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) JbText else JbSlate,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (hasKey) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(JbGreen)
            )
        }
    }
}

@Composable
private fun ModelChip(
    title: String,
    note: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) JbAccent.copy(alpha = 0.18f) else JbSurface)
            .border(
                1.dp,
                if (selected) JbAccent.copy(alpha = 0.55f) else JbBorder,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) JbText else JbSlate
        )
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
    }
}
