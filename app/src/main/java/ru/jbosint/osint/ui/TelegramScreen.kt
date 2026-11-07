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
                subtitle = "Карточка канала и выгрузка аудитории через TeleSINT: отчёт HTML, таблица CSV или Excel."
            )
        }

        item {
            GlowCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = tg.channel,
                        onValueChange = vm::setTgChannel,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !tg.running,
                        placeholder = { Text("@channel или https://t.me/channel", color = JbFaint) },
                        label = { Text("Канал или чат") },
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Spacer(Modifier.height(10.dp))
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "До 1000 участников на страницу. Первый запрос к каналу платный, " +
                            "повторные в течение часа — бесплатно.",
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
                    Text("Собрать аудиторию", style = MaterialTheme.typography.titleMedium)
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
            item {
                Text(
                    "Выгрузка",
                    style = MaterialTheme.typography.labelMedium,
                    color = JbMuted
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportButton("HTML", tg.exporting) { vm.exportTelegram(TgFormat.HTML) }
                    ExportButton("CSV", tg.exporting) { vm.exportTelegram(TgFormat.CSV) }
                    ExportButton("Excel", tg.exporting) { vm.exportTelegram(TgFormat.XLSX) }
                }
            }
            item { AudienceHeader(dossier) }
            items(dossier.members.take(50).size) { index ->
                val member = dossier.members[index]
                MemberRow(index + 1, member)
            }
            if (dossier.members.size > 50) {
                item {
                    Text(
                        "Показаны первые 50 из ${dossier.members.size}. Полный список — в выгрузке CSV или Excel.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
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
                StatTile("Участников", dossier.members.size.toString())
                StatTile("Всего в канале", dossier.membersCount.toString())
                StatTile("Страниц", dossier.pagesLoaded.toString())
            }
            if (dossier.hasMore) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Аудитория выгружена не полностью: увеличьте число страниц и повторите сбор " +
                        "(в течение часа запросы к этому каналу бесплатны).",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
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
