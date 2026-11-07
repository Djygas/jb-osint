package ru.jbosint.osint.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateListOf
import ru.jbosint.osint.data.CustomAuth
import ru.jbosint.osint.data.CustomModule
import ru.jbosint.osint.data.CustomModuleSpec
import ru.jbosint.osint.data.FactField
import ru.jbosint.osint.data.FieldRule
import ru.jbosint.osint.data.KeyCheck
import ru.jbosint.osint.data.KeyState
import ru.jbosint.osint.data.ProviderGroup

@Composable
fun KeysScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CustomModule?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    ScreenHeader(
                        eyebrow = "Доступ",
                        title = "API-ключи",
                        subtitle = "Подключите сервисы и включите нужные модули. Ключи хранятся только на устройстве."
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(JbSurface)
                    .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = JbGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Шифрование AES-256/GCM, мастер-ключ в Android Keystore",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.checkAll() }) {
                    Text("Проверить все", color = JbAccent2, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { vm.runDiagnostics() }) {
                    Icon(Icons.Filled.BugReport, contentDescription = null, tint = JbAccent2, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Диагностика", color = JbAccent2, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        ProviderGroup.entries.forEach { group ->
            val groupProviders = state.providers.filter { it.spec.group == group }
            if (groupProviders.isNotEmpty()) {
                item(key = "group_${group.name}") {
                    Text(
                        group.title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }
                items(groupProviders, key = { it.spec.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        onToggle = { enabled -> vm.toggleProvider(provider.spec.id, enabled) },
                        onSave = { value -> vm.saveKey(provider.spec.id, value) },
                        onSaveSecond = { value -> vm.saveSecondField(provider.spec.id, value) },
                        onClear = { vm.clearKey(provider.spec.id) },
                        onCheck = { vm.checkKey(provider.spec.id) },
                        onOpenDocs = {
                            val opened = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.spec.docsUrl)))
                            }.isSuccess
                            if (!opened) vm.notify("Не удалось открыть ссылку — проверьте браузер")
                        }
                    )
                }
            }
        }

        item(key = "custom_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "СВОИ МОДУЛИ",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Любой сервис с JSON-ответом: свой адрес, ключ и название",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                }
                TextButton(onClick = {
                    editing = null
                    editorOpen = true
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = JbAccent2, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Добавить", style = MaterialTheme.typography.labelMedium, color = JbAccent2)
                }
            }
        }

        if (state.customModules.isEmpty()) {
            item(key = "custom_empty") {
                Text(
                    "Пока нет своих модулей. Добавьте адрес API и ключ — модуль появится в поиске наравне со встроенными.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbFaint,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        } else {
            items(state.customModules, key = { "cm_${it.id}" }) { module ->
                CustomModuleCard(
                    module = module,
                    checking = state.customChecking.contains(module.id),
                    check = state.customChecks[module.id],
                    onToggle = { vm.setCustomModuleEnabled(module.id, it) },
                    onCheck = { vm.checkCustomModule(module.id) },
                    onEdit = {
                        editing = module
                        editorOpen = true
                    },
                    onDelete = { vm.deleteCustomModule(module.id) }
                )
            }
        }

        item {
            Text(
                "Названия ваших модулей видны только на этом экране. В отчётах PDF, Excel и JSON источник не раскрывается: в документах указывается только марка «Поиск JB OSINT».",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }

    if (editorOpen) {
        CustomModuleDialog(
            initial = state.manualDraft ?: editing,
            initialKey = state.manualKey,
            startInQuick = state.manualDraft == null && editing == null,
            onDismiss = {
                editorOpen = false
                vm.clearManualDraft()
            },
            onQuick = { title, key, link ->
                editorOpen = false
                vm.createModuleQuick(title, key, link)
            },
            onSave = { title, url, auth, header, param, key, rules, method ->
                vm.saveCustomModule(
                    id = editing?.id.orEmpty(),
                    title = title,
                    urlTemplate = url,
                    auth = auth,
                    headerName = header,
                    paramName = param,
                    key = key,
                    fields = rules,
                    method = method
                )
                editorOpen = false
            }
        )
    }

    state.autoSetup?.let { auto ->
        if (auto.running) {
            AlertDialog(
                onDismissRequest = { vm.cancelAutoSetup() },
                containerColor = JbSurface,
                title = {
                    Text(
                        "Быстрая настройка",
                        style = MaterialTheme.typography.titleMedium,
                        color = JbText
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = JbAccent, modifier = Modifier.size(28.dp))
                        Text(
                            auto.stage.ifBlank { "Определяем способ подключения…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = JbMuted
                        )
                        Text(
                            "Приложение пробует адреса и способы передачи ключа: GET и POST, Bearer, X-Api-Key, параметр адреса, JSON-тело. Это занимает меньше минуты.",
                            style = MaterialTheme.typography.labelSmall,
                            color = JbFaint
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.cancelAutoSetup() }) {
                        Text("Отмена", color = JbMuted)
                    }
                }
            )
        } else if (auto.failed) {
            AlertDialog(
                onDismissRequest = { vm.cancelAutoSetup() },
                containerColor = JbSurface,
                title = {
                    Text(
                        "Не удалось настроить автоматически",
                        style = MaterialTheme.typography.titleMedium,
                        color = JbText
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            auto.stage,
                            style = MaterialTheme.typography.bodySmall,
                            color = JbMuted
                        )
                        Text(
                            if (auto.exhausted) {
                                "Перебраны все известные варианты адреса и передачи ключа. Задайте адрес вручную — самый вероятный вариант уже подставлен, останется указать путь из документации сервиса."
                            } else {
                                "Проверьте, что ключ действителен, а ссылка ведёт на страницу сервиса. Можно повторить подбор (продолжатся другие комбинации) или задать адрес вручную — самый вероятный вариант уже подставлен."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = JbFaint
                        )
                    }
                },
                confirmButton = {
                    if (auto.exhausted) {
                        TextButton(onClick = {
                            vm.openManualAfterAuto()
                            editorOpen = true
                        }) {
                            Text("Задать адрес вручную", color = JbAccent2)
                        }
                    } else {
                        TextButton(onClick = { vm.retryAutoSetup() }) {
                            Text("Повторить автонастройку", color = JbAccent2)
                        }
                    }
                },
                dismissButton = {
                    if (auto.exhausted) {
                        TextButton(onClick = { vm.cancelAutoSetup() }) {
                            Text("Закрыть", color = JbMuted)
                        }
                    } else {
                        TextButton(onClick = {
                            vm.openManualAfterAuto()
                            editorOpen = true
                        }) {
                            Text("Ручная настройка", color = JbMuted)
                        }
                    }
                }
            )
        }
    }

    if (state.diagRunning) {
        AlertDialog(
            onDismissRequest = { vm.clearDiagnostics() },
            title = { Text("Диагностика модулей") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = JbAccent2)
                    Spacer(Modifier.width(12.dp))
                    Text("Проверяем включённые модули и читаем их ответы…", style = MaterialTheme.typography.bodySmall, color = JbMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.clearDiagnostics() }) { Text("Отмена") }
            }
        )
    } else state.diagReport?.let { report ->
        AlertDialog(
            onDismissRequest = { vm.clearDiagnostics() },
            title = {
                Text("Отчёт диагностики", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            report,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = JbText
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("JB OSINT · диагностика", report))
                    vm.notify("Отчёт скопирован — вставьте его в чат поддержки")
                    vm.clearDiagnostics()
                }) {
                    Text("Скопировать", color = JbAccent2)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.clearDiagnostics() }) { Text("Закрыть") }
            }
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderUi,
    onToggle: (Boolean) -> Unit,
    onSave: (String) -> Unit,
    onSaveSecond: (String) -> Unit,
    onClear: () -> Unit,
    onCheck: () -> Unit,
    onOpenDocs: () -> Unit
) {
    var keyText by rememberSaveable(provider.spec.id, provider.hasKey) { mutableStateOf("") }
    var secondText by rememberSaveable(provider.spec.id + "_2", provider.hasSecond) { mutableStateOf("") }
    var revealed by rememberSaveable(provider.spec.id + "_rev") { mutableStateOf(false) }
    val secondLabel = provider.spec.requiresSecondField

    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(provider.spec.title, style = MaterialTheme.typography.titleMedium, color = JbText)
                Spacer(Modifier.height(2.dp))
                Text(provider.spec.subtitle, style = MaterialTheme.typography.bodySmall, color = JbMuted)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = provider.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = JbAccent,
                    uncheckedThumbColor = JbMuted,
                    uncheckedTrackColor = JbSurfaceHigh,
                    uncheckedBorderColor = JbBorder
                )
            )
        }

        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatePill(if (provider.checking) KeyState.CHECKING else provider.state)
            Spacer(Modifier.width(8.dp))
            if (provider.spec.keyless) {
                Text("публичный API, ключ не нужен", style = MaterialTheme.typography.labelSmall, color = JbFaint)
            } else if (provider.hasKey) {
                Text(
                    if (provider.checkedAt > 0) "проверен ${dateShort(provider.checkedAt)}" else "ключ сохранён",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            } else {
                Text("ключ не задан", style = MaterialTheme.typography.labelSmall, color = JbFaint)
            }
            Spacer(Modifier.weight(1f))
            if (provider.checking) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = JbAccent, strokeWidth = 1.6.dp)
            }
        }

        if (provider.spec.keyless) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Модуль работает без регистрации и ключа — включите тумблер и проверьте связь.",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        } else {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        if (provider.hasKey) "Ключ сохранён — введите новый, чтобы заменить"
                        else provider.spec.hint,
                        color = JbFaint,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealed) "Скрыть" else "Показать",
                            tint = JbFaint
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }

        if (secondLabel != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = secondText,
                onValueChange = { secondText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        if (provider.hasSecond) "$secondLabel сохранён"
                        else "Дополнительно: $secondLabel",
                        color = JbFaint,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenDocs) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = JbMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text("Где взять ключ", style = MaterialTheme.typography.labelMedium, color = JbMuted)
            }
            Spacer(Modifier.weight(1f))
            if (provider.hasKey && !provider.spec.keyless) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить ключ", tint = JbFaint, modifier = Modifier.size(18.dp))
                }
            }
            TextButton(
                onClick = onCheck,
                enabled = provider.hasKey && !provider.checking
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = if (provider.hasKey) JbAccent2 else JbFaint,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "Проверить",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (provider.hasKey) JbAccent2 else JbFaint
                )
            }
            if (keyText.isNotBlank() || (secondLabel != null && secondText.isNotBlank())) {
                TextButton(onClick = {
                    if (keyText.isNotBlank()) {
                        onSave(keyText)
                        keyText = ""
                    }
                    if (secondText.isNotBlank()) {
                        onSaveSecond(secondText)
                        secondText = ""
                    }
                }) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JbGreen, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Сохранить", style = MaterialTheme.typography.labelMedium, color = JbGreen)
                }
            }
        }
    }
}

private fun dateShort(timestamp: Long): String =
    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru")).format(java.util.Date(timestamp))

@Composable
private fun CustomModuleCard(
    module: CustomModule,
    checking: Boolean,
    check: KeyCheck?,
    onToggle: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(module.title, style = MaterialTheme.typography.titleMedium, color = JbText)
                Spacer(Modifier.height(2.dp))
                Text(
                    CustomModuleSpec.hostOf(module.urlTemplate),
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = module.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = JbAccent,
                    uncheckedThumbColor = JbMuted,
                    uncheckedTrackColor = JbSurfaceHigh,
                    uncheckedBorderColor = JbBorder
                )
            )
        }

        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (module.auth == CustomAuth.NONE) {
                Pill("без ключа", JbSlate)
            } else if (module.hasKey) {
                Pill("ключ сохранён", JbGreen)
            } else {
                Pill("ключ не задан", JbAmber)
            }
            Spacer(Modifier.width(8.dp))
            if (module.auth != CustomAuth.AUTO) {
                Pill(module.method, JbSlate)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                module.auth.title,
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = JbAccent, strokeWidth = 1.6.dp)
                Spacer(Modifier.width(6.dp))
                Text("Проверяется…", style = MaterialTheme.typography.labelSmall, color = JbAccent)
            }
        }

        if (check != null && !checking) {
            Spacer(Modifier.height(4.dp))
            val (symbol, tint) = when (check.state) {
                KeyState.VALID -> "✅" to JbGreen
                KeyState.OFFLINE -> "🚫" to JbRed
                else -> "⚠️" to JbAmber
            }
            Text(
                "$symbol ${check.detail}",
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
        }
        if (module.fields.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Правил разбора: ${module.fields.size}",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCheck, enabled = !checking) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = JbAccent2, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Проверить", style = MaterialTheme.typography.labelMedium, color = JbAccent2)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Изменить модуль", tint = JbMuted, modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить модуль", tint = JbFaint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CustomModuleDialog(
    initial: CustomModule?,
    initialKey: String = "",
    startInQuick: Boolean,
    onDismiss: () -> Unit,
    onQuick: (String, String, String) -> Unit,
    onSave: (String, String, CustomAuth, String, String, String?, List<FieldRule>, String) -> Unit
) {
    var quick by remember { mutableStateOf(startInQuick) }
    var sourceLink by remember { mutableStateOf("") }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var url by remember { mutableStateOf(initial?.urlTemplate.orEmpty()) }
    var auth by remember { mutableStateOf(initial?.auth ?: CustomAuth.AUTO) }
    var header by remember { mutableStateOf(initial?.headerName ?: "X-API-Key") }
    var param by remember { mutableStateOf(initial?.paramName ?: "api_key") }
    var key by remember { mutableStateOf(initialKey) }
    var revealed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasStoredKey = initial?.hasKey == true
    val rules = remember {
        mutableStateListOf<FieldRule>().apply { addAll(initial?.fields.orEmpty()) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JbSurface,
        title = {
            Text(
                when {
                    initial != null -> "Модуль: ${initial.title}"
                    quick -> "Новый модуль · быстрая настройка"
                    else -> "Новый модуль"
                },
                style = MaterialTheme.typography.titleMedium,
                color = JbText,
                maxLines = 1
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (initial == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(JbSurfaceHigh)
                            .clickable { quick = !quick }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = quick,
                            onCheckedChange = { quick = it },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = JbAccent,
                                uncheckedColor = JbFaint
                            )
                        )
                        Column {
                            Text(
                                "Режим быстрой настройки",
                                style = MaterialTheme.typography.bodySmall,
                                color = JbText
                            )
                            Text(
                                "Приложение само определит адрес, метод и способ передачи ключа",
                                style = MaterialTheme.typography.labelSmall,
                                color = JbFaint
                            )
                        }
                    }
                }

                if (quick && initial == null) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(CustomModuleSpec.MAX_TITLE) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Название модуля", color = JbFaint) },
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = sourceLink,
                        onValueChange = { sourceLink = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Ссылка на источник (сайт или @бот)", color = JbFaint) },
                        placeholder = { Text("https://service.com или @telesint_bot", color = JbFaint, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("API-ключ", color = JbFaint) },
                        placeholder = { Text("вставьте ключ сервиса", color = JbFaint, style = MaterialTheme.typography.labelSmall) },
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealed = !revealed }) {
                                Icon(
                                    if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null,
                                    tint = JbFaint
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Кнопка «Создать» запустит автоподбор: приложение изучит страницу сервиса, попробует стандартные эндпоинты поиска и способы передачи ключа (Bearer, X-Api-Key, параметр адреса, JSON-тело). Название видно только вам.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                    error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = JbRed) }
                }

                if (!quick || initial != null) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(CustomModuleSpec.MAX_TITLE) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Название модуля", color = JbFaint) },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("URL-шаблон с {query}", color = JbFaint) },
                    placeholder = { Text("https://api.example.com/search?q={query}", color = JbFaint, style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Маркер {query} заменяется на запрос. Только HTTPS.",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )

                Text("Как передавать ключ", style = MaterialTheme.typography.labelMedium, color = JbMuted)
                CustomAuth.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { auth = option }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = auth == option,
                            onClick = { auth = option },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                selectedColor = JbAccent,
                                unselectedColor = JbFaint
                            )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(option.title, style = MaterialTheme.typography.bodySmall, color = JbText)
                            Text(option.hint, style = MaterialTheme.typography.labelSmall, color = JbFaint)
                        }
                    }
                }

                when (auth) {
                    CustomAuth.HEADER -> OutlinedTextField(
                        value = header,
                        onValueChange = { header = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Имя заголовка", color = JbFaint) },
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    CustomAuth.PARAM -> OutlinedTextField(
                        value = param,
                        onValueChange = { param = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Имя параметра", color = JbFaint) },
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    else -> Unit
                }

                if (auth != CustomAuth.NONE) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("API-ключ", color = JbFaint) },
                        placeholder = {
                            Text(
                                if (hasStoredKey) "Ключ сохранён — введите новый, чтобы заменить" else "вставьте ключ сервиса",
                                color = JbFaint,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealed = !revealed }) {
                                Icon(
                                    if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null,
                                    tint = JbFaint
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }

                Text("Правила разбора ответа", style = MaterialTheme.typography.labelMedium, color = JbMuted)
                Text(
                    "JSON-путь → поле отчёта. Пример: data.username, .data.items[].email. Без правил используется автоматический разбор.",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
                rules.forEachIndexed { index, rule ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(JbSurfaceHigh)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = rule.path,
                                onValueChange = { rules[index] = rule.copy(path = it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("Путь", color = JbFaint) },
                                placeholder = { Text("data.email", color = JbFaint, style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(10.dp),
                                colors = fieldColors(),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = { rules.removeAt(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Удалить правило", tint = JbFaint, modifier = Modifier.size(16.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { menu = true }) {
                                    Text(rule.field.kind, style = MaterialTheme.typography.labelSmall, color = JbAccent2)
                                    Text(" ▾", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    FactField.entries.forEach { field ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "${field.kind} (${field.token})",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (field == rule.field) JbAccent2 else JbText
                                                )
                                            },
                                            onClick = {
                                                rules[index] = rule.copy(field = field)
                                                menu = false
                                            }
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = rule.regex,
                                onValueChange = { rules[index] = rule.copy(regex = it) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("Regex (необязательно)", color = JbFaint) },
                                shape = RoundedCornerShape(10.dp),
                                colors = fieldColors(),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                TextButton(onClick = {
                    if (rules.size < 24) rules.add(FieldRule(path = "", field = FactField.OTHER))
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = JbAccent2, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Добавить правило", style = MaterialTheme.typography.labelSmall, color = JbAccent2)
                }

                Text(
                    "Название модуля видно только вам и никогда не попадает в отчёты — в документах источник не раскрывается.",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = JbRed)
                }
                }

                if (quick && initial == null) Unit
            }
        },
        confirmButton = {
            if (quick && initial == null) {
                TextButton(onClick = {
                    when {
                        title.isBlank() -> error = "Укажите название модуля"
                        key.isBlank() -> error = "Укажите API-ключ"
                        ru.jbosint.osint.data.SourceLink.parse(sourceLink) == null ->
                            error = "Укажите ссылку на сайт или Telegram-бота сервиса"
                        else -> {
                            error = null
                            onQuick(title.trim(), key, sourceLink.trim())
                        }
                    }
                }) {
                    Text("Создать", color = JbAccent2)
                }
            } else {
            TextButton(onClick = {
                val problem = CustomModuleSpec.validate(title, url)
                    ?: CustomModuleSpec.validateRules(rules.toList())
                when {
                    problem != null -> error = problem
                    auth != CustomAuth.NONE && key.isBlank() && !hasStoredKey -> error = "Укажите API-ключ"
                    else -> {
                        error = null
                        onSave(
                            title, url, auth, header, param, key.ifBlank { null },
                            rules.toList(), initial?.method ?: "GET"
                        )
                    }
                }
            }) {
                Text("Сохранить", color = JbAccent2)
            }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = JbMuted)
            }
        }
    )
}
