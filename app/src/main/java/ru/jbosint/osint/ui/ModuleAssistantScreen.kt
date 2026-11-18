package ru.jbosint.osint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.data.AdviceOrigin
import ru.jbosint.osint.data.AiCallEntry
import ru.jbosint.osint.data.AssistantStats
import ru.jbosint.osint.data.ModuleAdvice
import ru.jbosint.osint.data.ModuleIssue
import ru.jbosint.osint.report.ReportExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран ИИ-ассистента модулей: проверка, локальный разбор, рекомендации и
 * наглядная статистика экономии токенов.
 */
@Composable
fun ModuleAssistantScreen(state: UiState, vm: AppViewModel) {
    var journalOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.navigate(Screen.KEYS) }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = JbMuted)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "ИИ-ассистент модулей",
                        style = MaterialTheme.typography.titleLarge,
                        color = JbText
                    )
                    Text(
                        "Проверка, локальный анализ и рекомендации с экономией токенов",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                }
            }
        }

        item(key = "how") {
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
                Text("КАК ЭКОНОМЯТСЯ ТОКЕНЫ", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                Spacer(Modifier.height(8.dp))
                EconomyLine("1", "Локально", "коды ответов, структура JSON и число фактов разбираются на телефоне — без модели")
                EconomyLine("2", "Кэш", "совпал отпечаток проблемы — рекомендация берётся из кэша, запрос не оплачивается")
                EconomyLine("3", "Одной пачкой", "непонятные случаи уходят в модель вместе, до пяти модулей за запрос")
                EconomyLine("4", "Аудит", "каждое обращение к ИИ пишется в журнал: время, модули, токены")
            }
        }

        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    title = if (state.moduleAdvices.isEmpty()) "Проверить модули" else "Проверить снова",
                    icon = Icons.Filled.BugReport,
                    enabled = !state.moduleBusy,
                    modifier = Modifier.weight(1f),
                    onClick = { vm.runModuleAssistant() }
                )
                ActionButton(
                    title = "Журнал ИИ",
                    icon = Icons.Filled.History,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        vm.refreshModuleJournal()
                        journalOpen = true
                    }
                )
            }
        }

        item(key = "stats") {
            StatsCard(state.moduleStats)
        }

        state.moduleError?.let { error ->
            item(key = "error") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(JbSurface)
                        .border(1.dp, JbAmber.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = JbAmber)
                }
            }
        }

        if (state.moduleAdvices.isEmpty() && !state.moduleBusy) {
            item(key = "empty") {
                Text(
                    "Нажмите «Проверить модули»: приложение опросит включённые источники, само разберёт " +
                        "простые ошибки и спросит ИИ только о нестандартных ответах.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
            }
        }

        items(state.moduleAdvices, key = { it.moduleId + it.fingerprint }) { advice ->
            ModuleAdviceCard(advice)
        }

        if (state.moduleAdvices.isNotEmpty()) {
            item(key = "export") {
                Column {
                    Text("ОТЧЁТ", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportChip("JSON", state.moduleExporting) { vm.exportModuleReport(ReportExporter.Format.JSON) }
                        ExportChip("Excel", state.moduleExporting) { vm.exportModuleReport(ReportExporter.Format.XLSX) }
                        ExportChip("PDF", state.moduleExporting) { vm.exportModuleReport(ReportExporter.Format.PDF) }
                        ExportChip("HTML", state.moduleExporting) { vm.exportModuleReport(ReportExporter.Format.HTML) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "В отчёт попадают состояние модулей, рекомендации и статистика экономии. " +
                            "Ключи и токены не сохраняются: значения заменяются на ***.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
            }
        }

        item(key = "footer") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.clearModuleCache() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = JbMuted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Очистить кэш", color = JbMuted, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { vm.clearModuleAssistant() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = JbMuted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Сбросить", color = JbMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (state.moduleBusy) {
        AlertDialog(
            onDismissRequest = { },
            containerColor = JbSurface,
            title = { Text("Разбор модулей", color = JbText) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = JbAccent2)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        state.moduleStage.ifBlank { "Проверяем модули…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { }) { Text("Идёт проверка", color = JbFaint) }
            }
        )
    }

    if (journalOpen) {
        AiJournalDialog(state.moduleJournal, state.moduleStats, onDismiss = { journalOpen = false }, onClear = {
            vm.clearModuleJournal()
            journalOpen = false
        })
    }
}

@Composable
private fun EconomyLine(number: String, title: String, text: String) {
    Row(modifier = Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(JbAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, style = MaterialTheme.typography.labelSmall, color = JbAccent2)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = JbText)
            Text(text, style = MaterialTheme.typography.labelSmall, color = JbMuted)
        }
    }
}

@Composable
private fun ActionButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(JbSurfaceHigh)
            .border(1.dp, JbBorder, RoundedCornerShape(14.dp))
            .padding(vertical = 14.dp, horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) JbAccent2 else JbFaint,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) JbText else JbFaint
            )
        }
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Transparent)
                    .clickable { onClick() }
            )
        }
    }
}

@Composable
private fun StatsCard(stats: AssistantStats) {
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Text("ЭКОНОМИЯ", style = MaterialTheme.typography.labelSmall, color = JbFaint)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCell("Запросов к ИИ", stats.aiCalls.toString(), JbText, Modifier.weight(1f))
            StatCell("Из кэша", stats.cacheHits.toString(), JbGreen, Modifier.weight(1f))
            StatCell("Локально", stats.localOnly.toString(), JbAccent2, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCell("Токенов потрачено", "≈ ${stats.tokensSpent}", JbAmber, Modifier.weight(1f))
            StatCell("Сэкономлено", "≈ ${stats.tokensSaved}", JbGreen, Modifier.weight(1f))
            StatCell("Доля экономии", "${stats.savedShare}%", JbText, Modifier.weight(1f))
        }
        if (stats.lastRunAt > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Последний разбор: ${dateTime(stats.lastRunAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = JbFaint, maxLines = 2)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun ModuleAdviceCard(advice: ModuleAdvice) {
    val color = statusColor(advice)
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 15) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(advice.title, style = MaterialTheme.typography.titleMedium, color = JbText)
                Text(
                    "${advice.kind} · ${if (advice.code == 0) "нет ответа" else "код ${advice.code}"} · фактов ${advice.facts}",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
            Pill(advice.origin.label, originColor(advice.origin))
        }
        Spacer(Modifier.height(10.dp))
        Text(advice.status, style = MaterialTheme.typography.labelMedium, color = color)
        Spacer(Modifier.height(6.dp))
        Text(advice.advice, style = MaterialTheme.typography.bodySmall, color = JbText)
        advice.steps.forEach { step ->
            Spacer(Modifier.height(4.dp))
            Text("• $step", style = MaterialTheme.typography.bodySmall, color = JbMuted)
        }
        if (advice.issues.count { it != ModuleIssue.OK } > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Замечания: ${advice.issuesText}",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }
    }
}

@Composable
private fun ExportChip(label: String, busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(JbSurface)
            .border(1.dp, JbBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = !busy) { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Text(
            if (busy) "…" else label,
            style = MaterialTheme.typography.labelLarge,
            color = if (busy) JbFaint else JbAccent2
        )
    }
}

@Composable
private fun AiJournalDialog(
    journal: List<AiCallEntry>,
    stats: AssistantStats,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JbSurface,
        title = { Text("Журнал обращений к ИИ", color = JbText) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Запросов: ${stats.aiCalls} · из кэша: ${stats.cacheHits} · локально: ${stats.localOnly}\n" +
                        "Потрачено ≈ ${stats.tokensSpent} токенов, сэкономлено ≈ ${stats.tokensSaved}",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(10.dp))
                if (journal.isEmpty()) {
                    Text(
                        "Записей нет: ИИ ещё не вызывался. Сюда попадает каждый запрос — время, модули, " +
                            "объём промта и ответа.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
                journal.forEach { entry ->
                    Text(
                        "${dateTime(entry.at)} · ${entry.provider}/${entry.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                    Text(
                        "${if (entry.ok) "✓" else "✕"} ${entry.modules.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.ok) JbText else JbAmber
                    )
                    Text(
                        "промт ${entry.promptChars} симв. · ответ ${entry.answerChars} симв. · " +
                            "≈ ${entry.tokensIn + entry.tokensOut} токенов" +
                            (if (entry.note.isNotBlank()) " · ${entry.note}" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = JbMuted,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = JbAccent2) }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text("Очистить", color = JbMuted) }
        }
    )
}

private fun statusColor(advice: ModuleAdvice): Color = when {
    advice.issues.contains(ModuleIssue.OK) -> JbGreen
    advice.issues.contains(ModuleIssue.EMPTY) -> JbSlate
    advice.issues.contains(ModuleIssue.NO_KEY) -> JbMuted
    advice.issues.contains(ModuleIssue.OFFLINE) -> JbSlate
    else -> JbAmber
}

private fun originColor(origin: AdviceOrigin): Color = when (origin) {
    AdviceOrigin.AI -> JbAccent2
    AdviceOrigin.CACHE -> JbGreen
    AdviceOrigin.LOCAL -> JbMuted
}

private fun dateTime(ts: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(ts))
