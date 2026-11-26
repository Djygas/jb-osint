package ru.jbosint.osint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.data.module.ModuleInfo
import ru.jbosint.osint.data.module.ModuleKind
import ru.jbosint.osint.data.module.ModuleStatus
import ru.jbosint.osint.data.module.WizardFilter

/**
 * Мастер настройки модулей: выбор → проверка → ключи → итог.
 * Управление модулями (активность, скрытие, удаление) — в первом шаге и в итоге.
 */
@Composable
fun ModuleWizardScreen(state: UiState, vm: AppViewModel) {
    val wizard = state.wizard
    var keyVisible by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ModuleInfo?>(null) }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::wizardBack) {
                Icon(Icons.Filled.ArrowBack, "Назад", tint = JbText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("МАСТЕР НАСТРОЙКИ", style = MaterialTheme.typography.labelSmall, color = JbAccent2)
                Text(wizard.step.title, style = MaterialTheme.typography.titleMedium, color = JbText)
                Text(
                    "Шаг ${wizard.step.number} из 4",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
            TextButton(onClick = vm::wizardClose) { Text("Закрыть", color = JbMuted) }
        }
        LinearProgressIndicator(
            progress = { wizard.step.number / 4f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = JbAccent2,
            trackColor = JbBorderSoft
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (wizard.step) {
                WizardStep.SELECTION -> SelectionStep(state, vm, menuFor, onMenu = { menuFor = it }, onDelete = { deleteTarget = it })
                WizardStep.HEALTHCHECK -> HealthStep(state, vm)
                WizardStep.CONFIG -> ConfigStep(state, vm, keyVisible) { keyVisible = it }
                WizardStep.RESULT -> ResultStep(state, vm)
            }
        }
    }

    deleteTarget?.let { module ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = JbSurfaceHigh,
            titleContentColor = JbText,
            textContentColor = JbMuted,
            title = { Text("Удалить модуль?") },
            text = {
                Text("Удалить модуль «${module.name}»? Все настройки и ключи будут стёрты безвозвратно.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.wizardDelete(module.id)
                    deleteTarget = null
                }) { Text("Удалить", color = JbRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена", color = JbMuted) }
            }
        )
    }
}

@Composable
private fun SelectionStep(
    state: UiState,
    vm: AppViewModel,
    menuFor: String?,
    onMenu: (String?) -> Unit,
    onDelete: (ModuleInfo) -> Unit
) {
    val wizard = state.wizard
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WizardFilter.entries.forEach { filter ->
                FilterChip(
                    selected = wizard.filter == filter,
                    onClick = { vm.setWizardFilter(filter) },
                    label = { Text(filter.title) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Показать скрытые",
                style = MaterialTheme.typography.bodyMedium,
                color = JbText,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = wizard.showHidden, onCheckedChange = vm::setWizardShowHidden)
        }
        if (wizard.visibleModules.isEmpty()) {
            Text(
                "Нет модулей по выбранному фильтру",
                style = MaterialTheme.typography.bodySmall,
                color = JbFaint,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        wizard.visibleModules.forEach { module ->
            val status = wizard.statusOf(module.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(JbSurface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = module.id in wizard.selected,
                    onCheckedChange = { vm.toggleWizardModule(module.id) }
                )
                Column(
                    modifier = Modifier.weight(1f).clickable { vm.toggleWizardModule(module.id) }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            module.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = JbText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        StatusPill(status)
                    }
                    Text(
                        buildString {
                            append(module.kind.title)
                            if (!module.isActive) append(" · выключен")
                            if (module.isHidden) append(" · скрыт")
                            if (module.description.isNotBlank()) append(" · ").append(module.description)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (module.isHidden) JbAmber else JbFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconButton(onClick = { onMenu(module.id) }) {
                        Icon(Icons.Filled.MoreVert, "Действия", tint = JbMuted)
                    }
                    DropdownMenu(
                        expanded = menuFor == module.id,
                        onDismissRequest = { onMenu(null) }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (module.isActive) "Отключить" else "Включить") },
                            onClick = {
                                vm.wizardSetActive(module.id, !module.isActive)
                                onMenu(null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (module.isHidden) "Показать" else "Скрыть") },
                            onClick = {
                                vm.wizardSetHidden(module.id, !module.isHidden)
                                onMenu(null)
                            }
                        )
                        if (module.kind == ModuleKind.USER_CUSTOM) {
                            DropdownMenuItem(
                                text = { Text("Удалить", color = JbRed) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = JbRed) },
                                onClick = {
                                    onMenu(null)
                                    onDelete(module)
                                }
                            )
                        }
                    }
                }
            }
        }
        wizard.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = JbRed)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = vm::wizardNext,
            enabled = wizard.selected.isNotEmpty() && !wizard.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Далее (${wizard.selected.size})") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HealthStep(state: UiState, vm: AppViewModel) {
    val wizard = state.wizard
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Проверка доступности", style = MaterialTheme.typography.titleSmall, color = JbText)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { wizard.progress / 100f },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = JbAccent2,
            trackColor = JbBorderSoft
        )
        Spacer(Modifier.height(6.dp))
        Text(
            wizard.stage.ifBlank { if (wizard.busy) "Проверяю…" else "Готово" },
            style = MaterialTheme.typography.bodySmall,
            color = JbMuted
        )
        Spacer(Modifier.height(10.dp))
        wizard.selectedModules.forEach { module ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    module.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JbText,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(wizard.statusOf(module.id))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::wizardBack, enabled = !wizard.busy) { Text("Назад", color = JbMuted) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::wizardRecheck, enabled = !wizard.busy) { Text("Повторить", color = JbAccent2) }
            Spacer(Modifier.width(6.dp))
            Button(onClick = vm::wizardNext, enabled = !wizard.busy) { Text("Далее") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConfigStep(
    state: UiState,
    vm: AppViewModel,
    keyVisible: Boolean,
    onKeyVisible: (Boolean) -> Unit
) {
    val wizard = state.wizard
    val module = wizard.current
    val uri = LocalUriHandler.current
    val all = wizard.needsKeyModules
    val index = all.indexOfFirst { it.id == module?.id }.let { if (it < 0) 0 else it + 1 }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        if (module == null) {
            Text("Модуль не выбран", style = MaterialTheme.typography.bodyMedium, color = JbMuted)
            Spacer(Modifier.height(10.dp))
            Button(onClick = vm::wizardNext) { Text("К итогу") }
        } else {
            Text(
                if (all.isNotEmpty()) "Модуль $index из ${all.size}" else "Настройка модуля",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
            Spacer(Modifier.height(8.dp))
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        module.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = JbText,
                        modifier = Modifier.weight(1f)
                    )
                    StatusPill(wizard.statusOf(module.id))
                }
                if (module.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(module.description, style = MaterialTheme.typography.bodySmall, color = JbMuted)
                }
                if (module.docsUrl.isNotBlank()) {
                    TextButton(onClick = { runCatching { uri.openUri(module.docsUrl) } }) {
                        Icon(Icons.Filled.OpenInNew, null, tint = JbAccent2, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Где взять ключ", color = JbAccent2)
                    }
                }
                if (module.keyless) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Модуль работает без ключа — достаточно проверить доступность.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbGreen
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = wizard.keyInput,
                        onValueChange = vm::setWizardKeyInput,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (module.hasKey) "Новый API-ключ (необязательно)" else "API-ключ") },
                        visualTransformation = if (keyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { onKeyVisible(!keyVisible) }) {
                                Icon(
                                    if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    if (keyVisible) "Скрыть" else "Показать",
                                    tint = JbMuted
                                )
                            }
                        }
                    )
                    if (module.hint.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(module.hint, style = MaterialTheme.typography.labelSmall, color = JbFaint)
                    }
                    if (module.hasKey) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Ключ уже сохранён. Оставьте поле пустым, чтобы не менять.",
                            style = MaterialTheme.typography.labelSmall,
                            color = JbFaint
                        )
                    }
                    module.requiresSecondField?.let { label ->
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = wizard.secondInput,
                            onValueChange = vm::setWizardSecondInput,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = vm::wizardApplyKey,
                    enabled = !wizard.busy && (module.keyless || wizard.keyInput.isNotBlank() || module.hasKey)
                ) {
                    Text(if (module.keyless) "Проверить" else "Проверить и сохранить")
                }
                wizard.note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = JbGreen)
                }
                wizard.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = JbRed)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::wizardBack, enabled = !wizard.busy) { Text("Назад", color = JbMuted) }
                TextButton(onClick = vm::wizardNextModule, enabled = !wizard.busy) { Text("Пропустить", color = JbMuted) }
                Spacer(Modifier.weight(1f))
                Button(onClick = vm::wizardNext, enabled = !wizard.busy) { Text("Далее") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResultStep(state: UiState, vm: AppViewModel) {
    val wizard = state.wizard
    val selected = wizard.selectedModules
    val active = selected.count { wizard.statusOf(it.id) == ModuleStatus.ACTIVE }
    val needs = selected.count {
        wizard.statusOf(it.id) == ModuleStatus.NEEDS_KEY || it.needsKey
    }
    val failed = selected.count {
        wizard.statusOf(it.id) == ModuleStatus.NO_NETWORK || wizard.statusOf(it.id) == ModuleStatus.FAILED
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
            Text("Итог", style = MaterialTheme.typography.titleSmall, color = JbText)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Работают: $active", JbGreen)
                Pill("Нужен ключ: $needs", JbAmber)
                Pill("Проблемы: $failed", if (failed > 0) JbRed else JbFaint)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                wizard.stage.ifBlank { "Проверка завершена" },
                style = MaterialTheme.typography.bodySmall,
                color = JbMuted
            )
        }
        Spacer(Modifier.height(10.dp))
        if (selected.isEmpty()) {
            Text("Модули не выбраны", style = MaterialTheme.typography.bodySmall, color = JbFaint)
        } else {
            selected.forEach { module ->
                val status = wizard.statusOf(module.id)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(module.name, style = MaterialTheme.typography.bodyMedium, color = JbText)
                        Text(status.title, style = MaterialTheme.typography.labelSmall, color = JbFaint)
                    }
                    if (status != ModuleStatus.ACTIVE) {
                        TextButton(onClick = { vm.wizardConfigure(module.id) }) {
                            Text("Настроить", color = JbAccent2)
                        }
                    }
                    StatusPill(status)
                }
            }
        }
        wizard.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = JbRed)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::wizardRecheck, enabled = !wizard.busy) {
                Text("Перепроверить", color = JbAccent2)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::wizardRunDiagnostics, enabled = !wizard.busy) {
                Text("Запустить диагностику")
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = vm::wizardFinish, modifier = Modifier.fillMaxWidth()) {
            Text("Сохранить и закрыть")
        }
        TextButton(onClick = vm::wizardClose, modifier = Modifier.fillMaxWidth()) {
            Text("Настроить позже", color = JbMuted)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusPill(status: ModuleStatus) {
    val color = when (status) {
        ModuleStatus.ACTIVE -> JbGreen
        ModuleStatus.NEEDS_KEY -> JbAmber
        ModuleStatus.NO_NETWORK, ModuleStatus.FAILED -> JbRed
        ModuleStatus.UNKNOWN -> JbFaint
    }
    Pill(status.title, color)
}
