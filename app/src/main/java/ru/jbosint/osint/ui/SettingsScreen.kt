package ru.jbosint.osint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.BuildConfig

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmHistory by remember { mutableStateOf(false) }

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
private fun ActionRow(
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
