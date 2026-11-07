package ru.jbosint.osint.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.data.HistoryEntry
import ru.jbosint.osint.data.QueryDetector
import ru.jbosint.osint.data.QueryKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val kindLabel = mapOf(
    QueryKind.EMAIL to "Почта",
    QueryKind.PHONE to "Телефон",
    QueryKind.DOMAIN to "Домен",
    QueryKind.IP to "IP-адрес",
    QueryKind.USERNAME to "Никнейм",
    QueryKind.NAME to "Имя"
)

@Composable
fun HomeScreen(state: UiState, vm: AppViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 28.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandMark(44)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("JB OSINT", style = MaterialTheme.typography.headlineMedium, color = JbText)
                    Text(
                        "Единый поиск по открытым источникам",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                }
                IconButton(onClick = { vm.navigate(Screen.KEYS) }) {
                    Icon(Icons.Filled.Tune, contentDescription = "API-ключи", tint = JbMuted)
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        item {
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 18) {
                val kind = remember(state.query) {
                    if (state.query.trim().length >= 2) QueryDetector.detect(state.query) else null
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("НОВОЕ ИССЛЕДОВАНИЕ", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                    Spacer(Modifier.weight(1f))
                    if (kind != null) Pill(kindLabel[kind] ?: "Запрос", JbAccent2)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.searching,
                    placeholder = {
                        Text("Имя, mail@domain.ru, +7…, domain.ru, 8.8.8.8, @nick", color = JbFaint)
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = JbMuted)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.startSearch() })
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = vm::startSearch,
                    enabled = !state.searching,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JbAccent,
                        disabledContainerColor = JbAccent.copy(alpha = 0.4f)
                    )
                ) {
                    if (state.searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Идёт сбор сведений…", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Начать поиск", style = MaterialTheme.typography.titleMedium)
                    }
                }

                if (state.searching) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = JbAccent,
                        trackColor = JbSurfaceHigh
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.stage,
                            style = MaterialTheme.typography.bodySmall,
                            color = JbMuted,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${state.progress}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = JbAccent2
                        )
                    }
                }

                if (state.searchError != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.searchError,
                        style = MaterialTheme.typography.bodySmall,
                        color = JbAmber
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        if (state.activeModules > 0) "Модулей: ${state.activeModules}" else "Модули не подключены",
                        if (state.activeModules > 0) JbGreen else JbAmber
                    )
                    if (state.demoMode) Pill("Демо-режим", JbAccent2)
                }
            }
        }

        if (state.demoMode) {
            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(JbAccent2.copy(alpha = 0.10f))
                        .border(1.dp, JbAccent2.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Демонстрационный набор: результаты формируются без обращения к внешним сервисам.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC9B8FF),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.setDemoMode(false) }) {
                        Text("Выключить", color = JbAccent2, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (state.history.isNotEmpty()) {
            item {
                Spacer(Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ПОСЛЕДНИЕ ОТЧЁТЫ", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${state.history.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            items(state.history, key = { it.id }) { entry ->
                HistoryRow(entry, onClick = { vm.openHistory(entry) })
                Spacer(Modifier.height(8.dp))
            }
        } else {
            item {
                Spacer(Modifier.height(26.dp))
                EmptyHint(
                    icon = Icons.Filled.History,
                    title = "История пуста",
                    subtitle = "Выполненные исследования сохраняются здесь\nи доступны для повторного экспорта."
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(JbSurface)
            .border(1.dp, JbBorderSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(JbSurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = JbAccent2, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.query,
                style = MaterialTheme.typography.titleSmall,
                color = JbText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${dateText(entry.createdAt)} · фактов ${entry.facts} · связей ${entry.links}",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint,
                maxLines = 1
            )
        }
        Text(
            "ПОДТВ. ${entry.confirmed}",
            style = MaterialTheme.typography.labelSmall,
            color = JbGreen
        )
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = JbFaint, modifier = Modifier.size(18.dp))
    }
}

fun dateText(timestamp: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(timestamp))
