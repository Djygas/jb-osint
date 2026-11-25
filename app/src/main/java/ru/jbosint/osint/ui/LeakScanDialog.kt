package ru.jbosint.osint.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.jbosint.osint.data.LeakRules

/**
 * «Утечки»: глубокий поиск по локальным дампам и каскаду API.
 *
 * Пароли не показываются и не хранятся: результат отвечает на вопрос
 * «учётная запись засветилась?», а не «какой у неё пароль».
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
                    "Индекс локальных файлов + каскад API. Пароли не показываются",
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
                        items(leak.sources, key = { it.id }) { source -> SourceBlock(source) }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = vm::exportLeakJson) {
                                    Icon(Icons.Filled.Share, null, tint = JbAccent2, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Экспорт JSON", color = JbAccent2)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${leak.rowCount} строк в индексе",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = JbFaint
                                )
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Пароли в отчёт не попадают. Индекс хранит только SHA-256 адреса, " +
                                "маску и место в файле: сама пара остаётся в вашем дампе. " +
                                "Кэш ответов API живёт 24 часа.",
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
            title = "Полная переиндексация",
            subtitle = "Перед поиском заново разобрать все файлы. Долго и заметно по батарее",
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

@Composable
private fun SourceBlock(source: LeakSourceUi) {
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
        source.note?.let { note ->
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.labelSmall, color = JbMuted)
        }
        source.rows.forEach { row ->
            Spacer(Modifier.height(6.dp))
            Text(row.value, style = MaterialTheme.typography.bodySmall, color = JbText)
            val meta = buildString {
                append(row.where)
                if (row.secret) append(" · в строке был пароль")
            }
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = if (row.secret) JbAmber else JbFaint)
            }
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

/** Подсказка для экрана настроек: где лежат файлы и что попадает в индекс. */
fun leakIndexHint(): String =
    "Поддерживаются ${"CSV, TSV, TXT, SQL, JSON"}. Лимит строки — ${LeakRules.MAX_LINE} символов."
