package ru.jbosint.osint.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.core.model.Fact

/**
 * Отдельная вкладка «Утечки»: email/телефон → официальные API утечек.
 * Пароль не вводится и не сохраняется; показываются только метаданные утечки.
 */
@Composable
fun LeaksScreen(state: UiState, vm: AppViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 18.dp, bottom = 28.dp)
    ) {
        item { ScreenHeader("Проверка утечек", "Утечки логинов", "Email или телефон · официальные API") }

        item {
            GlowCard {
                OutlinedTextField(
                    value = state.leaksQuery,
                    onValueChange = vm::setLeaksQuery,
                    label = { Text("Email или телефон") },
                    placeholder = { Text("user@example.com или +7 900 000-00-00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(onSearch = { vm.runLeaksCheck() }),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = vm::runLeaksCheck,
                        enabled = !state.leaksRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = JbAccent),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.leaksRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = JbText,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(if (state.leaksRunning) "Проверяем…" else "Проверить")
                    }
                    TextButton(onClick = vm::clearLeaks, enabled = !state.leaksRunning) {
                        Text("Очистить", color = JbMuted)
                    }
                }
            }
        }

        item {
            GlowCard(padding = 14) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = JbAccent2)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Пароли не собираются", style = MaterialTheme.typography.titleSmall, color = JbText)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Модули обращаются к официальным API Have I Been Pwned и LeakCheck. " +
                                "В отчёт попадают только сервис, дата, набор данных и типы полей — без логинов, " +
                                "паролей и хешей. Проверяйте только свои данные или данные с согласия владельца.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JbMuted
                        )
                    }
                }
            }
        }

        if (state.leaksError != null) {
            item {
                Text(
                    state.leaksError,
                    style = MaterialTheme.typography.bodySmall,
                    color = JbRed
                )
            }
        }

        if (state.leaksRunning) {
            item {
                Column {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = JbAccent2,
                        trackColor = JbBorder
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Опрос источников утечек…", style = MaterialTheme.typography.bodySmall, color = JbMuted)
                }
            }
        }

        if (!state.leaksRunning && state.leaksFacts.isNotEmpty()) {
            item {
                Text(
                    "Найдено фактов: ${state.leaksFacts.size} · ответили источников: ${state.leaksSources}",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
            items(state.leaksFacts) { fact ->
                LeakFactCard(fact)
            }
        }

        if (!state.leaksRunning && state.leaksDone && state.leaksFacts.isEmpty()) {
            item {
                EmptyHint(
                    icon = Icons.Filled.Lock,
                    title = "Совпадений не найдено",
                    subtitle = "Значение не встречается в доступных источниках — это не гарантирует отсутствие утечки."
                )
            }
        }
    }
}

@Composable
private fun LeakFactCard(fact: Fact) {
    val uri = LocalUriHandler.current
    val link = fact.urls.firstOrNull()
    GlowCard(padding = 14) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(fact.kind, style = MaterialTheme.typography.labelSmall, color = JbFaint)
                Spacer(Modifier.height(2.dp))
                Text(fact.value, style = MaterialTheme.typography.titleMedium, color = JbText)
            }
            ConfidencePill(fact.confidence, compact = true)
        }
        if (fact.attributes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            fact.attributes.forEach { (k, v) ->
                KeyValueRow(k, v)
            }
        }
        if (link != null) {
            TextButton(
                onClick = { runCatching { uri.openUri(link) } },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("Открыть источник", color = JbAccent2)
            }
        }
    }
}
