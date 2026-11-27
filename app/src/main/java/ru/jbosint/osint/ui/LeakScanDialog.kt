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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.jbosint.osint.data.LeakRules
import ru.jbosint.osint.data.PasswordStatus

/**
 * «Утечки»: глубокий поиск по локальным дампам и каскаду API.
 *
 * Пароль берётся только из локального индекса и по умолчанию маскируется.
 * В экспорт он попадает только при включённом режиме «Показывать пароли»,
 * а CSV/HTML не содержат его ни при каком режиме.
 */
@Composable
fun LeakScanCard(state: UiState, vm: AppViewModel) {
    val leak = state.leak
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("УТЕЧКИ", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Глубокий поиск по дампам",
                    style = MaterialTheme.typography.titleMedium,
                    color = JbText
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Индекс локальных файлов + каскад API. Пароли скрыты по умолчанию",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = vm::openLeakScan) {
                Text(
                    if (leak.fileCount > 0) "Файлов: ${leak.fileCount}" else "Открыть",
                    color = JbAccent2
                )
            }
        }
    }
}

@Composable
fun LeakScanDialog(state: UiState, vm: AppViewModel) {
    val leak = state.leak
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLeakFile(uri)
    }

    Dialog(
        onDismissRequest = vm::closeLeakScan,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = JbBg, modifier = Modifier.fillMaxSize()) {
            val show = leak.showPasswords && !leak.alwaysMask
            val selected = leak.selected
            if (selected != null) {
                LeakDetailScreen(
                    row = selected,
                    showPasswords = show,
                    onBack = vm::closeLeakRow,
                    onOpenSettings = {
                        vm.closeLeakScan()
                        vm.navigate(Screen.SETTINGS)
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("УТЕЧКИ", style = MaterialTheme.typography.labelSmall, color = JbAccent2)
                        Spacer(Modifier.weight(1f))
                        Pill(
                            when {
                                leak.alwaysMask -> "Пароли: всегда скрыты"
                                leak.showPasswords -> "Пароли: вкл"
                                else -> "Пароли: выкл"
                            },
                            when {
                                leak.alwaysMask -> JbFaint
                                leak.showPasswords -> JbAmber
                                else -> JbFaint
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        if (leak.fileCount > 0) {
                            Pill("${leak.fileCount} файл(ов)", JbFaint)
                            Spacer(Modifier.width(6.dp))
                        }
                        IconButton(onClick = vm::closeLeakScan) {
                            Icon(Icons.Filled.Close, "Закрыть", tint = JbMuted)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item { SourceToggles(state, vm) }

                        item {
                            OutlinedTextField(
                                value = leak.query,
                                onValueChange = vm::setLeakQuery,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Почта, телефон или ник") },
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                        }

                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = vm::runLeakScan,
                                    enabled = !leak.busy
                                ) {
                                    Text(if (leak.busy) "Сканирую…" else "Сканировать всё")
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = { picker.launch(arrayOf("text/*", "application/octet-stream", "application/json")) },
                                    enabled = !leak.busy
                                ) { Text("Добавить дамп", color = JbAccent2) }
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = vm::indexLeaks,
                                    enabled = !leak.busy
                                ) { Text("Индекс", color = JbMuted) }
                            }
                        }

                        if (leak.busy || leak.stage.isNotBlank()) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (leak.busy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = JbAccent2
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        leak.stage.ifBlank { "Готово" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = JbMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        leak.note?.let { note ->
                            item {
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        leak.offline -> JbAmber
                                        note.startsWith("Совпадений нет") -> JbMuted
                                        else -> JbGreen
                                    }
                                )
                            }
                        }

                        if (leak.sources.isNotEmpty()) {
                            item { SourceFilterRow(state, vm) }
                            val visible = if (leak.filterSource.isBlank()) leak.sources
                            else leak.sources.filter { it.id == leak.filterSource }
                            items(visible, key = { it.id }) { source ->
                                SourceBlock(
                                    source = source,
                                    showPasswords = show,
                                    onRow = vm::openLeakRow,
                                    onOpenSettings = {
                                        vm.closeLeakScan()
                                        vm.navigate(Screen.SETTINGS)
                                    }
                                )
                            }
                            item { ExportRow(vm, show) }
                        }

                        item {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Пароль берётся только из локального индекса дампов и по умолчанию " +
                                    "маскируется. В JSON он попадает лишь при включённом режиме; CSV и HTML " +
                                    "не содержат пароль никогда, только факт наличия. Кэш ответов API живёт 24 часа.",
                                style = MaterialTheme.typography.labelSmall,
                                color = JbFaint
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportRow(vm: AppViewModel, includePasswords: Boolean) {
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            if (includePasswords) askPassword = true else vm.exportLeakJson(null)
        }) {
            Icon(Icons.Filled.Share, null, tint = JbAccent2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (includePasswords) "JSON (шифр.)" else "JSON 2.0", color = JbAccent2)
        }
        TextButton(onClick = vm::exportLeakCsv) {
            Text("CSV", color = JbMuted)
        }
        TextButton(onClick = vm::exportLeakHtml) {
            Text("HTML", color = JbMuted)
        }
    }
    if (askPassword) {
        AlertDialog(
            onDismissRequest = { askPassword = false },
            containerColor = JbSurfaceHigh,
            title = { Text("Зашифровать экспорт?") },
            text = {
                Column {
                    Text(
                        "JSON содержит пароли. Задайте пароль (минимум 8 символов) — " +
                            "файл будет зашифрован AES-256-GCM (PBKDF2).",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("Пароль шифрования") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.length >= 8,
                    onClick = {
                        vm.exportLeakJson(password)
                        askPassword = false
                        password = ""
                    }
                ) { Text("Экспорт", color = JbAccent2) }
            },
            dismissButton = {
                TextButton(onClick = { askPassword = false }) { Text("Отмена", color = JbMuted) }
            }
        )
    }
}

@Composable
private fun SourceFilterRow(state: UiState, vm: AppViewModel) {
    val leak = state.leak
    var expanded by remember { mutableStateOf(false) }
    val current = leak.sources.firstOrNull { it.id == leak.filterSource }?.title
        ?: "Все источники"
    Box(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = true }) {
            Text("Источник: $current", color = JbAccent2)
            Icon(Icons.Filled.ArrowDropDown, null, tint = JbAccent2)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Все источники") },
                onClick = { vm.setLeakFilter(""); expanded = false }
            )
            leak.sources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.title) },
                    onClick = { vm.setLeakFilter(source.id); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun SourceBlock(
    source: LeakSourceUi,
    showPasswords: Boolean,
    onRow: (LeakRowUi) -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JbSurface)
            .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                source.title,
                style = MaterialTheme.typography.bodyMedium,
                color = JbText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (source.cached) {
                Pill("кэш", JbFaint)
                Spacer(Modifier.width(6.dp))
            }
            Pill("${source.hits}", if (source.hits > 0) JbGreen else JbFaint)
        }
        source.status?.let { status ->
            Spacer(Modifier.height(4.dp))
            Text(status, style = MaterialTheme.typography.labelSmall, color = JbAmber)
        }
        source.note?.let { note ->
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.labelSmall, color = JbMuted)
        }
        source.rows.forEach { row ->
            Spacer(Modifier.height(8.dp))
            LeakRecordRow(row, showPasswords, onRow, onOpenSettings)
        }
        if (source.hits > source.rows.size && source.rows.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Показано ${source.rows.size} из ${source.hits}",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }
    }
}

@Composable
private fun LeakRecordRow(
    row: LeakRowUi,
    showPasswords: Boolean,
    onRow: (LeakRowUi) -> Unit,
    onOpenSettings: () -> Unit
) {
    val copy = rememberClipboardCopier()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(JbBg)
            .clickable { onRow(row) }
            .padding(10.dp)
    ) {
        LabeledValue("Значение", row.value)
        LabeledValue("Ресурс", row.resource.ifBlank { "источник не указан" })
        if (row.breachDate.isNotBlank()) LabeledValue("Дата", row.breachDate)
        if (row.dataTypes.isNotEmpty()) LabeledValue("Типы данных", row.dataTypesText)
        PasswordLine(row, showPasswords, onOpenSettings)
        if (row.passwordSource.isNotBlank() || row.hadPassword) {
            LabeledValue("Источник пароля", passwordSourceLabel(row))
        }
        if (row.sourceRef.isNotBlank()) LabeledValue("Ссылка", row.sourceRef)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                onClick = { copy(row.value) }
            ) {
                Icon(Icons.Filled.ContentCopy, null, tint = JbAccent2, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Копировать", color = JbAccent2)
            }
            Spacer(Modifier.width(12.dp))
            val pair = row.password
            TextButton(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                enabled = showPasswords && !pair.isNullOrBlank(),
                onClick = {
                    if (showPasswords && !pair.isNullOrBlank()) copy("${row.value}:$pair")
                }
            ) {
                Text(
                    "Логин:пароль",
                    color = if (showPasswords && !pair.isNullOrBlank()) JbAccent2 else JbFaint
                )
            }
        }
    }
}

@Composable
private fun PasswordLine(
    row: LeakRowUi,
    showPasswords: Boolean,
    onOpenSettings: () -> Unit
) {
    if (row.password != null) {
        if (!showPasswords) {
            LabeledValue("Пароль", "••••••••")
            TextButton(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                onClick = onOpenSettings
            ) {
                Text("Пароли скрыты — включите показ в настройках", color = JbAmber)
            }
            return
        }
        var revealed by remember(row.recordId, row.value) { mutableStateOf(false) }
        var confirm by remember(row.recordId, row.value) { mutableStateOf(false) }
        LaunchedEffect(revealed) {
            if (revealed) {
                delay(60_000)
                revealed = false
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabeledValue(
                "Пароль",
                if (revealed) row.password.orEmpty() else "••••••••",
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                if (revealed) revealed = false else confirm = true
            }) {
                Icon(
                    if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Скрыть" else "Показать",
                    tint = JbAccent2,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(if (revealed) "Скрыть" else "Показать", color = JbAccent2)
            }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                containerColor = JbSurfaceHigh,
                title = { Text("Это ваши данные?") },
                text = {
                    Text(
                        "Показывать пароль можно только для собственных данных " +
                            "или данных с согласия владельца."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = false
                        revealed = true
                    }) { Text("Да, это мои данные", color = JbAccent2) }
                },
                dismissButton = {
                    TextButton(onClick = { confirm = false }) { Text("Нет", color = JbMuted) }
                }
            )
        }
    } else {
        LabeledValue("Пароль", passwordReason(row.passwordStatus), color = statusColor(row.passwordStatus))
    }
}

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = JbText) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint,
            modifier = Modifier.width(86.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private fun passwordSourceLabel(row: LeakRowUi): String {
    val source = when (row.passwordSource) {
        "local_index" -> "Локальный индекс"
        "hibp" -> "Have I Been Pwned"
        "leakcheck" -> "LeakCheck"
        "hudsonrock" -> "Hudson Rock"
        "dyxless" -> "Dyxless"
        else -> row.passwordSource.ifBlank { row.source }
    }
    return if (row.sourceRef.isNotBlank()) "$source · ${row.sourceRef}" else source
}

private fun passwordReason(status: PasswordStatus): String = when (status) {
    PasswordStatus.FOUND -> "Пароль найден"
    PasswordStatus.MASKED -> "Пароль найден, но не сохранён в кэше — повторите поиск"
    PasswordStatus.PAYWALLED -> "Полные записи недоступны на текущем тарифе источника"
    PasswordStatus.NOT_IN_INDEX -> "В локальных дампах пароля не найдено"
    PasswordStatus.API_WITHOUT_PASSWORD -> "Источник подтверждает факт утечки, пароли не передаёт"
    PasswordStatus.NONE -> "Совпадений не найдено"
}

private fun statusColor(status: PasswordStatus): androidx.compose.ui.graphics.Color = when (status) {
    PasswordStatus.PAYWALLED -> JbAmber
    PasswordStatus.NOT_IN_INDEX, PasswordStatus.API_WITHOUT_PASSWORD -> JbMuted
    PasswordStatus.FOUND, PasswordStatus.MASKED -> JbGreen
    PasswordStatus.NONE -> JbFaint
}

@Composable
private fun rememberClipboardCopier(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text: String ->
            clipboard.setText(AnnotatedString(text))
            scope.launch {
                delay(60_000)
                if (clipboard.getText()?.text == text) clipboard.setText(AnnotatedString(""))
            }
        }
    }
}

@Composable
private fun LeakDetailScreen(
    row: LeakRowUi,
    showPasswords: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val copy = rememberClipboardCopier()
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "Назад", tint = JbText)
            }
            Text("Запись утечки", style = MaterialTheme.typography.titleLarge, color = JbText)
        }
        Spacer(Modifier.height(12.dp))
        DetailField("Значение", row.value)
        DetailField("Ресурс", row.resource.ifBlank { "источник не указан" })
        if (row.breachDate.isNotBlank()) DetailField("Дата утечки", row.breachDate)
        if (row.dataTypes.isNotEmpty()) DetailField("Типы данных", row.dataTypesText)
        PasswordLine(row, showPasswords, onOpenSettings)
        DetailField("Статус пароля", row.passwordStatus.title)
        if (row.passwordSource.isNotBlank() || row.hadPassword) {
            DetailField("Источник пароля", passwordSourceLabel(row))
        }
        DetailField("Модуль", row.source.ifBlank { "не указан" })

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { copy(row.value) }) {
                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Копировать значение")
            }
            Spacer(Modifier.width(10.dp))
            val pair = row.password
            TextButton(
                enabled = showPasswords && !pair.isNullOrBlank(),
                onClick = {
                    if (showPasswords && !pair.isNullOrBlank()) copy("${row.value}:$pair")
                }
            ) { Text("Копировать логин:пароль", color = if (showPasswords) JbAccent2 else JbFaint) }
        }
        if (row.url.startsWith("http")) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { runCatching { uri.openUri(row.url) } }) {
                Icon(Icons.Filled.OpenInNew, null, tint = JbAccent2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Открыть источник", color = JbAccent2)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Проверяйте только свои данные или данные с согласия владельца. " +
                "Пароль скрывается автоматически через 60 секунд; буфер обмена очищается по таймеру.",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = JbFaint)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = JbText)
    }
}

@Composable
private fun SourceToggles(state: UiState, vm: AppViewModel) {
    val leak = state.leak
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JbSurface)
            .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        ToggleRow(
            title = "Онлайн-API",
            subtitle = "HIBP → LeakCheck → Hudson Rock → Dyxless; на 429 ждём и идём дальше",
            checked = leak.useApi,
            onChange = vm::setLeakUseApi
        )
        ToggleRow(
            title = "Локальный индекс",
            subtitle = "Файлы из папки приложения leaks/ — поиск по индексу, без чтения дампов",
            checked = leak.useIndex,
            onChange = vm::setLeakUseIndex
        )
        ToggleRow(
            title = "Глубокий поиск по дампам",
            subtitle = "Читать все файлы дампов заново (пароли из файлов). Долго и заметно по батарее",
            checked = leak.rawScan,
            onChange = vm::setLeakRaw
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = JbText)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Подсказка для экрана настроек: где лежат файлы и что попадает в индекс. */
fun leakIndexHint(): String =
    "Поддерживаются ${"CSV, TSV, TXT, SQL, JSON"}. Лимит строки — ${LeakRules.MAX_LINE} символов."
