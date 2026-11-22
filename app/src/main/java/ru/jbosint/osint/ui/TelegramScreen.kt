package ru.jbosint.osint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.data.TgDossier

@Composable
fun TelegramScreen(state: UiState, vm: AppViewModel) {
    val tg = state.tg
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ScreenHeader(
                eyebrow = "Анализ",
                title = "Telegram-парсер",
                subtitle = "Анализ через ключи TeleSINT/Telelog/Funstat: аккаунты, чаты, сообщения, пересылки, статистика и приватность."
            )
        }

        item {
            GlowCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Тип входа", style = MaterialTheme.typography.labelMedium, color = JbMuted)
                    ModeChips(tg.mode, enabled = !tg.running, onSelect = vm::setTgMode)
                    OutlinedTextField(
                        value = tg.channel,
                        onValueChange = vm::setTgChannel,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !tg.running,
                        minLines = if (tg.mode == ru.jbosint.osint.data.TelegramRules.Kind.CONTACT_OR_RECENT ||
                            tg.mode == ru.jbosint.osint.data.TelegramRules.Kind.FORWARDED_GROUP_MESSAGE ||
                            tg.mode == ru.jbosint.osint.data.TelegramRules.Kind.CHANNEL_FORWARD_POST) 3 else 1,
                        placeholder = { Text(inputHint(tg.mode), color = JbFaint) },
                        label = { Text("Входные данные") },
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Страниц участников", style = MaterialTheme.typography.labelMedium, color = JbMuted)
                        Spacer(Modifier.weight(1f))
                        StepButton("−") { vm.setTgPages(tg.pages - 1) }
                        Text(
                            tg.pages.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = JbText,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                        StepButton("+") { vm.setTgPages(tg.pages + 1) }
                    }
                    Text(
                        "TeleSINT — платный API: запросы списывают баланс по тарифу сервиса. Telelog/Funstat: JWT Bearer, запросы платные по tech.request_cost; неподдерживаемые публичным API возможности помечаются предупреждением.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
            }
        }

        item {
            Button(
                onClick = vm::runTelegramParse,
                enabled = !tg.running,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = JbAccent,
                    disabledContainerColor = JbAccent.copy(alpha = 0.4f)
                )
            ) {
                if (tg.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(tg.stage.ifBlank { "Собираем аудиторию…" }, style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Анализировать", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        tg.error?.let { message ->
            item {
                Box(
                    Modifier.fillMaxWidth()
                        .background(JbSurfaceHigh, RoundedCornerShape(12.dp))
                        .border(1.dp, JbBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = JbAccent2)
                }
            }
        }

        tg.dossier?.let { dossier ->
            item { DossierCard(dossier) }
            if (dossier.warnings.isNotEmpty()) {
                item { WarningCard(dossier.warnings) }
            }
            item { Text("Выгрузка", style = MaterialTheme.typography.labelMedium, color = JbMuted) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportButton("HTML", tg.exporting) { vm.exportTelegram(TgFormat.HTML) }
                    ExportButton("CSV", tg.exporting) { vm.exportTelegram(TgFormat.CSV) }
                    ExportButton("Excel", tg.exporting) { vm.exportTelegram(TgFormat.XLSX) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportButton("Raw JSON", tg.exporting) { vm.exportTelegram(TgFormat.RAW_JSON) }
                }
            }
            item {
                OutlinedButton(onClick = { vm.openProfileFromTelegram() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Открыть профиль результата")
                }
            }
            if (dossier.members.isNotEmpty()) {
                item { AudienceHeader(dossier) }
                items(dossier.members.take(50).size) { index -> MemberRow(index + 1, dossier.members[index]) }
                if (dossier.members.size > 50) {
                    item { Text("Показаны первые 50 из ${dossier.members.size}. Полный список — в выгрузке CSV/Excel.", style = MaterialTheme.typography.labelSmall, color = JbFaint) }
                }
            } else {
                item { Text("ФАКТЫ · ${dossier.facts.size}", style = MaterialTheme.typography.labelSmall, color = JbMuted) }
                items(dossier.facts.take(80).size) { index -> FactRow(dossier.facts[index]) }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = JbText)
    }
}

@Composable
private fun ExportButton(label: String, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Filled.Download, contentDescription = null, tint = JbAccent2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DossierCard(dossier: TgDossier) {
    GlowCard {
        Column(Modifier.padding(16.dp)) {
            Text(dossier.title, style = MaterialTheme.typography.titleMedium, color = JbText)
            Spacer(Modifier.height(2.dp))
            Text(dossier.link, style = MaterialTheme.typography.labelSmall, color = JbAccent2)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Фактов", dossier.facts.size.toString())
                StatTile("Участников", dossier.members.size.toString())
                StatTile("Бэкенды", dossier.backends.size.toString())
            }
            if (dossier.backends.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Источники: ${dossier.backends.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = JbMuted)
            }
            if (dossier.hasMore) {
                Spacer(Modifier.height(10.dp))
                Text("Данные выгружены не полностью: увеличьте число страниц и повторите сбор.", style = MaterialTheme.typography.labelSmall, color = JbFaint)
            }
        }
    }
}

@Composable
private fun AudienceHeader(dossier: TgDossier) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text("АУДИТОРИЯ · ${dossier.members.size}", style = MaterialTheme.typography.labelSmall, color = JbMuted)
    }
}

@Composable
private fun MemberRow(index: Int, member: ru.jbosint.osint.data.TeleSint.Member) {
    GlowCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                index.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint,
                modifier = Modifier.width(26.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(member.displayName, style = MaterialTheme.typography.bodyMedium, color = JbText)
                val sub = buildList {
                    if (member.username.isNotBlank()) add("@${member.username}")
                    if (member.phone.isNotBlank()) add(member.phone)
                    if (member.bot) add("бот")
                    if (member.lastOnline.isNotBlank()) add(member.lastOnline)
                }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = JbMuted)
                }
            }
        }
    }
}


@Composable
private fun ModeChips(selected: ru.jbosint.osint.data.TelegramRules.Kind, enabled: Boolean, onSelect: (ru.jbosint.osint.data.TelegramRules.Kind) -> Unit) {
    val modes = listOf(
        ru.jbosint.osint.data.TelegramRules.Kind.USERNAME to "Аккаунт",
        ru.jbosint.osint.data.TelegramRules.Kind.CONTACT_OR_RECENT to "Контакты",
        ru.jbosint.osint.data.TelegramRules.Kind.STICKER to "Стикер",
        ru.jbosint.osint.data.TelegramRules.Kind.GROUP to "Группа/канал",
        ru.jbosint.osint.data.TelegramRules.Kind.MESSAGE_LINK to "Ссылка на сообщение",
        ru.jbosint.osint.data.TelegramRules.Kind.FORWARDED_GROUP_MESSAGE to "Пересланное из группы",
        ru.jbosint.osint.data.TelegramRules.Kind.GROUP_STATS to "Статистика чата",
        ru.jbosint.osint.data.TelegramRules.Kind.CHANNEL_FORWARD_POST to "Пересылки поста"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        modes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (kind, label) ->
                    OutlinedButton(
                        onClick = { onSelect(kind) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected == kind) JbAccent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent
                        )
                    ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun inputHint(kind: ru.jbosint.osint.data.TelegramRules.Kind): String = when (kind) {
    ru.jbosint.osint.data.TelegramRules.Kind.USERNAME -> "@username, id или телефон"
    ru.jbosint.osint.data.TelegramRules.Kind.CONTACT_OR_RECENT -> "Контакт/недавние: номер, username, ID или список"
    ru.jbosint.osint.data.TelegramRules.Kind.STICKER -> "Стикер: ссылка, file_id или описание"
    ru.jbosint.osint.data.TelegramRules.Kind.GROUP -> "@группа/канал, ID или ссылка"
    ru.jbosint.osint.data.TelegramRules.Kind.MESSAGE_LINK -> "https://t.me/group/123"
    ru.jbosint.osint.data.TelegramRules.Kind.FORWARDED_GROUP_MESSAGE -> "Ссылка/текст пересланного сообщения из группы"
    ru.jbosint.osint.data.TelegramRules.Kind.GROUP_STATS -> "Ссылка/ID группы для статистики"
    ru.jbosint.osint.data.TelegramRules.Kind.CHANNEL_FORWARD_POST -> "Ссылка/ID пересланного поста канала"
    else -> "Входные данные"
}

@Composable
private fun WarningCard(warnings: List<String>) {
    GlowCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ограничения", style = MaterialTheme.typography.labelMedium, color = JbAccent2)
            warnings.take(6).forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = JbMuted) }
        }
    }
}

@Composable
private fun FactRow(fact: ru.jbosint.osint.core.model.RawFact) {
    GlowCard {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fact.kind, style = MaterialTheme.typography.labelSmall, color = JbAccent2, modifier = Modifier.weight(1f))
                Text(if (fact.apiConfirmed) "API" else "не подтверждено", style = MaterialTheme.typography.labelSmall,
                    color = if (fact.apiConfirmed) JbMuted else JbAccent2)
            }
            Text(fact.value, style = MaterialTheme.typography.bodyMedium, color = JbText)
            val details = (fact.attributes.entries.take(4).map { "${it.key}: ${it.value}" } + "Источник: ${fact.sourceId}").joinToString(" · ")
            Text(details, style = MaterialTheme.typography.labelSmall, color = JbMuted)
            if (fact.note.isNotBlank()) {
                Text(fact.note, style = MaterialTheme.typography.labelSmall, color = JbFaint)
            }
        }
    }
}
