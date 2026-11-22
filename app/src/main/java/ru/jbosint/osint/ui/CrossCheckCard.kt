package ru.jbosint.osint.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.jbosint.osint.data.Contradiction

/**
 * «Сверка фактов»: сервер ищет разные значения одного поля у одного объекта.
 * PII маскируется на сервере до анализа; внешняя LLM по умолчанию выключена.
 */
@Composable
fun CrossCheckCard(state: UiState, vm: AppViewModel) {
    Spacer(Modifier.height(12.dp))
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("СВЕРКА ФАКТОВ", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Противоречия по одному полю",
                    style = MaterialTheme.typography.titleMedium,
                    color = JbText
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Сервер находит разные значения одного поля у одного объекта. " +
                        "Почта, телефоны и токены маскируются до анализа.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            TextButton(
                onClick = vm::openCrossCheck,
                enabled = state.result != null
            ) {
                Text("Открыть", color = if (state.result != null) JbAccent2 else JbFaint)
            }
        }
    }
}

@Composable
fun CrossCheckDialog(state: UiState, vm: AppViewModel) {
    val check = state.crossCheck
    Dialog(
        onDismissRequest = vm::closeCrossCheck,
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
                    Text("СВЕРКА ФАКТОВ", style = MaterialTheme.typography.labelSmall, color = JbAccent2)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = vm::closeCrossCheck) {
                        Icon(Icons.Filled.Close, "Закрыть", tint = JbMuted)
                    }
                }

                Text(
                    "Фактов к сверке: ${check.facts}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JbText
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Факты уходят на ваш сервер по HTTPS. Он маскирует почту, телефоны, IP и токены " +
                        "до любого анализа; внешняя LLM по умолчанию выключена.",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::runCrossCheck, enabled = !check.busy) {
                        Text(if (check.busy) "Сверяю…" else "Проверить")
                    }
                    if (check.busy) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = JbAccent2
                        )
                    }
                }

                check.error?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = JbAmber)
                }

                Spacer(Modifier.height(12.dp))
                val result = check.result
                if (result == null) {
                    Text(
                        "Нажмите «Проверить». Отчёт и история не меняются — это отдельный разбор.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                } else {
                    Text(
                        "Провайдер: ${result.provider}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                    Spacer(Modifier.height(8.dp))
                    if (result.contradictions.isEmpty()) {
                        Text(
                            "Противоречий не найдено",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JbGreen
                        )
                    } else {
                        Text(
                            "Противоречий: ${result.contradictions.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JbAmber
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(result.contradictions) { item -> ContradictionRow(item) }
                        }
                    }
                    result.notes.forEach { note ->
                        Spacer(Modifier.height(6.dp))
                        Text(note, style = MaterialTheme.typography.labelSmall, color = JbFaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContradictionRow(item: Contradiction) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JbSurface)
            .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            item.subject.ifBlank { "Объект не указан" },
            style = MaterialTheme.typography.bodyMedium,
            color = JbText
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "поле: ${item.field}",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
        Spacer(Modifier.height(6.dp))
        item.values.forEach { value ->
            Text(
                "• $value",
                style = MaterialTheme.typography.bodySmall,
                color = JbMuted
            )
        }
    }
}
