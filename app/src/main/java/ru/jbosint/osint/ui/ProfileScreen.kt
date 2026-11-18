package ru.jbosint.osint.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.jbosint.osint.profile.MsgType
import ru.jbosint.osint.profile.ProfileBuilder
import ru.jbosint.osint.profile.ProfileContact
import ru.jbosint.osint.profile.ProfileData
import ru.jbosint.osint.profile.ProfileExport
import ru.jbosint.osint.profile.ProfileIdentity
import ru.jbosint.osint.profile.ProfileStatus

private enum class ProfileSection(val title: String, val index: Int) {
    SUMMARY("Сводка", 2),
    IDENTIFIERS("Идентификаторы", 3),
    CIRCLE("Круг общения", 4),
    BEHAVIOR("Поведение", 5),
    CONTENT("Контент", 6),
    EXTERNAL("OSINT", 7),
    REPUTATION("Репутация", 8),
    MESSAGES("Сообщения", 11)
}

@Composable
fun ProfileScreen(state: UiState, vm: AppViewModel) {
    val profile = state.profile
    if (state.profilePinSet && !state.profileUnlocked) {
        ProfileLockScreen(vm)
        return
    }
    if (profile == null) {
        EmptyProfile(vm, state)
        return
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var section by remember(profile.id) { mutableStateOf(ProfileSection.SUMMARY) }
    var filter by remember(profile.id) { mutableStateOf<MsgType?>(null) }
    var visible by remember(profile.id) { mutableIntStateOf(60) }
    var showImport by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importProfileMessages(it) } }

    fun open(url: String) {
        if (url.isBlank()) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    val filtered = remember(profile, filter) {
        if (filter == null) profile.messages else profile.messages.filter { it.type == filter }
    }
    val shown = filtered.takeLast(visible)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            item {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.backHome() }) {
                            Icon(Icons.Filled.ArrowBack, "Назад", tint = JbMuted)
                        }
                        Text(
                            "ПРОФИЛЬ ОБЪЕКТА",
                            style = MaterialTheme.typography.labelSmall,
                            color = JbAccent2
                        )
                        Spacer(Modifier.weight(1f))
                        if (profile.demo) Pill("демо", JbAmber)
                    }
                    Spacer(Modifier.height(6.dp))
                    SubjectCard(profile, onOpen = ::open, onCopy = { clipboard.setText(AnnotatedString(it)) })
                    Spacer(Modifier.height(12.dp))
                }
            }

            item {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        ProfileSection.entries.forEach { entry ->
                            SectionChip(entry.title, section == entry) {
                                section = entry
                                scope.launch { listState.animateScrollToItem(entry.index) }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            item {
                SummarySection(profile)
            }

            item {
                IdentifiersSection(
                    profile,
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                    onOpen = ::open
                )
            }

            item {
                CircleSection(
                    profile,
                    onOpen = ::open,
                    onCopy = { clipboard.setText(AnnotatedString(it)) }
                )
            }

            item {
                BehaviorSection(profile)
            }

            item {
                ContentSection(profile, onOpen = ::open)
            }

            item {
                ExternalSection(profile, onOpen = ::open)
            }

            item {
                ReputationSection(profile)
            }

            item {
                if (state.profileChanges.isNotEmpty()) {
                    ChangesCard(state.profileChanges)
                }
            }

            item {
                ToolsSection(
                    state = state,
                    profile = profile,
                    anonymize = state.profileAnonymize,
                    onAnonymize = vm::setProfileAnonymize,
                    onImport = { importLauncher.launch(arrayOf("text/*", "application/json", "text/csv")) },
                    onExport = vm::exportProfile,
                    onCompare = vm::compareProfiles,
                    onReport = vm::generateProfileReport,
                    onPin = vm::setProfilePin,
                    onClear = vm::clearProfile
                )
            }

            item {
                Column {
                    Spacer(Modifier.height(16.dp))
                    SectionHeader("Медиа и сообщения")
                    if (profile.messages.isEmpty()) {
                        DataStub(
                            "Данные по этому параметру не найдены. Загрузите выгрузку сообщений (CSV/JSON) " +
                                "или соберите её Telegram-парсером."
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showImport = !showImport }) {
                                Icon(Icons.Filled.UploadFile, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Загрузить выгрузку")
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            SectionChip("Все (${profile.messages.size})", filter == null) { filter = null; visible = 60 }
                            MsgType.entries.filter { type ->
                                profile.messages.any { it.type == type }
                            }.forEach { type ->
                                val count = profile.messages.count { it.type == type }
                                SectionChip("${type.title} ($count)", filter == type) { filter = type; visible = 60 }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.exportProfile(ProfileExport.MESSAGES_CSV) }) {
                                Icon(Icons.Filled.Download, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Сообщения CSV", fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { vm.exportProfile(ProfileExport.AUDIENCE_CSV) }) {
                                Icon(Icons.Filled.Download, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Круг CSV", fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Показано ${shown.size} из ${filtered.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = JbFaint
                        )
                    }
                }
            }

            items(shown) { message ->
                MessageRow(message)
            }

            if (shown.size < filtered.size) {
                item {
                    TextButton(
                        onClick = { visible += 100 },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Text("Загрузить ещё ${minOf(100, filtered.size - shown.size)} сообщений")
                    }
                }
            }

            item {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Данные собраны из открытых источников. Отчёт носит информационный характер.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (showImport && profile.messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(JbBg.copy(alpha = 0.85f))
                    .clickable { showImport = false },
                contentAlignment = Alignment.Center
            ) {
                GlowCard(modifier = Modifier.padding(24.dp)) {
                    Text("Выгрузка сообщений", style = MaterialTheme.typography.titleMedium, color = JbText)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Поддерживаются CSV из Telegram Desktop (колонки «Дата», «Время», " +
                            "«Отправитель», «Текст») и JSON-массив сообщений.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showImport = false; importLauncher.launch(arrayOf("text/*", "application/json", "text/csv")) }) {
                        Text("Выбрать файл")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyProfile(vm: AppViewModel, state: UiState) {
    Backdrop {
        Column(
            modifier = Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EmptyHint(
                icon = Icons.Filled.Lock,
                title = "Профиль не открыт",
                subtitle = "Откройте профиль из результата поиска, из аудитории Telegram-канала " +
                    "или посмотрите демонстрационный набор."
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = { vm.openDemoProfile() }) {
                Text("Демо-профиль")
            }
            Spacer(Modifier.height(8.dp))
            if (state.history.isNotEmpty()) {
                TextButton(onClick = { vm.openProfileFromLastResult() }) {
                    Text("Профиль последнего поиска")
                }
            }
        }
    }
}

@Composable
private fun SubjectCard(
    profile: ProfileData,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    val statusColor = when (profile.subject.status) {
        ProfileStatus.ACTIVE -> JbGreen
        ProfileStatus.BLOCKED -> JbAmber
        ProfileStatus.DELETED -> JbRed
        ProfileStatus.UNKNOWN -> JbSlate
    }
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarBadge(profile.subject.title, 62)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.subject.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = JbText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (profile.subject.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        profile.subject.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(5.dp))
                    Text(profile.subject.status.title, style = MaterialTheme.typography.labelMedium, color = statusColor)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "полнота ${profile.completeness}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
            }
        }
        if (profile.subject.link.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(JbAccent.copy(alpha = 0.12f))
                    .clickable { onOpen(profile.subject.link) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    profile.subject.link,
                    style = MaterialTheme.typography.bodySmall,
                    color = JbAccent2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Filled.OpenInNew, null, tint = JbAccent2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Filled.ContentCopy,
                    "Копировать",
                    tint = JbMuted,
                    modifier = Modifier.size(16.dp).clickable { onCopy(profile.subject.link) }
                )
            }
        }
        if (profile.sources.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Источники: " + profile.sources.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }
    }
}

@Composable
private fun SummarySection(profile: ProfileData) {
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeader("Сводка")
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("сообщений", profile.summary.messages.toString(), Modifier.weight(1f), JbText)
                StatTile("медиа", profile.summary.media.toString(), Modifier.weight(1f), JbAccent2)
                StatTile("контактов", profile.summary.contacts.toString(), Modifier.weight(1f), JbGreen)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "период",
                    if (profile.summary.hasRange) {
                        "${ProfileBuilder.dateText(profile.summary.from)} — ${ProfileBuilder.dateText(profile.summary.to)}"
                    } else "нет данных",
                    Modifier.weight(1f),
                    JbText
                )
                StatTile("география", profile.summary.geo.ifBlank { "нет данных" }, Modifier.weight(1f), JbSlate)
                StatTile("оценка", "${profile.summary.score}", Modifier.weight(1f), JbAmber)
            }
            Spacer(Modifier.height(14.dp))
            SectionHeader("Типы контента")
            if (profile.messages.isEmpty()) {
                DataStub("Данные по этому параметру не найдены: нет выгрузки сообщений.")
            } else {
                MixBar(profile.mix)
            }
            if (profile.summary.groups > 0) {
                Spacer(Modifier.height(12.dp))
                SectionHeader("Группы и каналы")
                profile.groups.take(6).forEach { group ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(JbAccent))
                        Spacer(Modifier.width(7.dp))
                        Text(group, style = MaterialTheme.typography.bodySmall, color = JbText)
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentifiersSection(profile: ProfileData, onCopy: (String) -> Unit, onOpen: (String) -> Unit) {
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeader("Идентификаторы")
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            if (profile.identifiers.isEmpty() && profile.names.isEmpty()) {
                DataStub("Модули не вернули ников, телефонов и ID по этому запросу.")
            }
            profile.identifiers.groupBy { it.kind }.forEach { (kind, items) ->
                Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = JbFaint)
                Spacer(Modifier.height(4.dp))
                items.take(6).forEach { id -> IdentityRow(id, onCopy, onOpen) }
                Spacer(Modifier.height(10.dp))
            }
            if (profile.names.isNotEmpty()) {
                SectionHeader("История имён")
                profile.names.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            item.date.ifBlank { "—" },
                            style = MaterialTheme.typography.labelSmall,
                            color = JbFaint,
                            modifier = Modifier.width(84.dp)
                        )
                        Text("→", color = JbAccent2, modifier = Modifier.width(18.dp))
                        Text(item.name, style = MaterialTheme.typography.bodySmall, color = JbText)
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentityRow(id: ProfileIdentity, onCopy: (String) -> Unit, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(JbSurfaceHigh)
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(id.platform, style = MaterialTheme.typography.labelSmall, color = JbMuted)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            id.value,
            style = MaterialTheme.typography.bodyMedium,
            color = JbText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (id.confidence.name == "CONFIRMED") {
            Text("✓", color = JbGreen, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.Filled.ContentCopy,
            "Копировать",
            tint = JbFaint,
            modifier = Modifier.size(15.dp).clickable { onCopy(id.value) }
        )
        if (id.evidence.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.OpenInNew,
                "Источник",
                tint = JbFaint,
                modifier = Modifier.size(15.dp).clickable { onOpen(id.evidence) }
            )
        }
    }
}

@Composable
private fun CircleSection(profile: ProfileData, onOpen: (String) -> Unit, onCopy: (String) -> Unit) {
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeader("Круг общения")
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            if (profile.contacts.isEmpty()) {
                DataStub("Связи не найдены: модули не вернули участников и переписок.")
            } else {
                ConnectionGraph(
                    nodes = profile.nodes,
                    edges = profile.edges,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(Modifier.height(10.dp))
                SectionHeader("Кому отвечает")
                val replies = profile.contacts.filter { it.replies > 0 }.sortedByDescending { it.replies }
                if (replies.isEmpty()) {
                    DataStub("Цитирования не найдены: в выгрузке нет поля «кому ответ».")
                } else {
                    ContactRow(replies.first(), onOpen, onCopy, showReplies = true)
                }
                Spacer(Modifier.height(10.dp))
                SectionHeader("Топ контактов")
                profile.contacts.take(8).forEach { contact -> ContactRow(contact, onOpen, onCopy) }
                if (profile.contacts.size > 8) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ещё ${profile.contacts.size - 8} контактов — в выгрузке «Круг общения» (CSV/XLSX).",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: ProfileContact,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    showReplies: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarBadge(contact.name, 30)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.name.ifBlank { contact.username.ifBlank { contact.id } },
                style = MaterialTheme.typography.bodyMedium,
                color = JbText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = buildList {
                if (contact.username.isNotBlank()) add("@${contact.username}")
                if (contact.messages > 0) add("${contact.messages} сообщ.")
                if (contact.replies > 0) add("${contact.replies} репл.")
                if (contact.lastAt > 0) add(ProfileBuilder.dateText(contact.lastAt))
                if (contact.bot) add("бот")
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = JbMuted)
            }
        }
        if (contact.username.isNotBlank()) {
            Icon(
                Icons.Filled.OpenInNew,
                "Профиль",
                tint = JbFaint,
                modifier = Modifier.size(16.dp).clickable { onOpen(contact.link.ifBlank { "https://t.me/${contact.username}" }) }
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.ContentCopy,
                "Копировать",
                tint = JbFaint,
                modifier = Modifier.size(15.dp).clickable { onCopy("@${contact.username}") }
            )
        }
    }
}

@Composable
private fun BehaviorSection(profile: ProfileData) {
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeader("Поведенческий анализ")
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            if (!profile.behavior.hasData) {
                DataStub(
                    "Данные по этому параметру не найдены: нужна выгрузка сообщений " +
                        "(активность, паузы, тональность считаются по переписке)."
                )
            } else {
                Text("Активность по дням и часам", style = MaterialTheme.typography.bodySmall, color = JbMuted)
                Spacer(Modifier.height(8.dp))
                ActivityHeatmap(profile.behavior.heatmap)
                Spacer(Modifier.height(12.dp))
                SectionHeader("Эмоциональный окрас")
                val sentiment = profile.behavior.sentiment
                Text(
                    "${sentiment.tone} · позитив ${sentiment.positive}, негатив ${sentiment.negative}, " +
                        "нейтрально ${sentiment.neutral}",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    val total = sentiment.total.coerceAtLeast(1)
                    Box(Modifier.weight(sentiment.positive.toFloat().coerceAtLeast(0.01f)).height(10.dp).background(JbGreen))
                    Box(Modifier.weight(sentiment.neutral.toFloat().coerceAtLeast(0.01f)).height(10.dp).background(JbSlate))
                    Box(Modifier.weight(sentiment.negative.toFloat().coerceAtLeast(0.01f)).height(10.dp).background(JbRed))
                }
                Spacer(Modifier.height(12.dp))
                SectionHeader("Темы обсуждений")
                if (profile.behavior.topics.isEmpty()) {
                    DataStub("Темы не выделены: слишком мало текста.")
                } else {
                    profile.behavior.topics.forEach { topic ->
                        ShareRow(topic.name, topic.share, topic.count, JbAccent2)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentSection(profile: ProfileData, onOpen: (String) -> Unit) {
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeader("Контент-анализ")
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            if (!profile.content.hasData) {
                DataStub("Данные по этому параметру не найдены: нет текстов сообщений.")
            } else {
                SectionHeader("Облако слов")
                WordCloud(profile.content.words)
                if (profile.content.links.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SectionHeader("Ссылки из переписки")
                    profile.content.links.take(8).forEach { link ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(link.url) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                link.domain,
                                style = MaterialTheme.typography.bodySmall,
                                color = JbAccent2,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("×${link.count}", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                        }
                    }
                }
                if (profile.content.media.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SectionHeader("Медиа")
                    profile.content.media.forEach { media ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(media.type.title, style = MaterialTheme.typography.bodySmall, color = JbText, modifier = Modifier.weight(1f))
                            Text("${media.count}", style = MaterialTheme.typography.labelMedium, color = JbMuted)
                        }
                    }
                }
                if (profile.content.exif.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionHeader("Метаданные снимков")
                    profile.content.exif.take(5).forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = JbMuted)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Транскрибация голосовых и кружков выполняется, если модуль вернул аудиофайлы; " +
                        "в текущей выгрузке медиа-файлов нет.",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
        }
    }
}

@Composable
private fun ExternalSection(profile: ProfileData, onOpen: (String) -> Unit) {
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeader("Внешние связи (OSINT)")
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            if (profile.external.isEmpty()) {
                DataStub("Внешних профилей не найдено: модули не дали ников и ссылок.")
            } else {
                val found = profile.external.filter { it.found }
                val checks = profile.external.filterNot { it.found }
                if (found.isNotEmpty()) {
                    SectionHeader("Найдено")
                    found.forEach { hit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(hit.url) }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(JbGreen))
                            Spacer(Modifier.width(8.dp))
                            Text(hit.platform, style = MaterialTheme.typography.bodyMedium, color = JbText, modifier = Modifier.weight(1f))
                            Text(hit.handle, style = MaterialTheme.typography.labelSmall, color = JbAccent2, maxLines = 1)
                        }
                    }
                }
                if (checks.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionHeader("Проверить вручную")
                    checks.forEach { hit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(hit.url) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(hit.platform, style = MaterialTheme.typography.bodySmall, color = JbMuted, modifier = Modifier.weight(1f))
                            Text("открыть ↗", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ссылки открываются вручную: приложение не делает запросов к площадкам без вашего действия.",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
            }
        }
    }
}

@Composable
private fun ReputationSection(profile: ProfileData) {
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeader("Репутационный скоринг")
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            ScoreBar(
                "Индекс токсичности",
                profile.reputation.toxicity,
                if (profile.reputation.toxicity > 40) JbRed else JbAmber,
                "доля агрессивных и негативных сообщений"
            )
            ScoreBar(
                "Индекс спама",
                profile.reputation.spam,
                if (profile.reputation.spam > 30) JbRed else JbAmber,
                "повторяющиеся ссылки и фразы"
            )
            ScoreBar(
                "Доверие",
                profile.reputation.trust,
                if (profile.reputation.trust > 60) JbGreen else JbAmber,
                "возраст аккаунта, стабильность ника, отсутствие ботов"
            )
            if (profile.reputation.notes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                profile.reputation.notes.forEach { note ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text("· ", color = JbFaint)
                        Text(note, style = MaterialTheme.typography.labelSmall, color = JbMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangesCard(changes: List<String>) {
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeader("Алерты и изменения")
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            changes.forEach { change ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("▲ ", color = JbAmber)
                    Text(change, style = MaterialTheme.typography.bodySmall, color = JbText)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Сравнение с предыдущим сохранённым профилем этого объекта.",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }
    }
}

@Composable
private fun ToolsSection(
    state: UiState,
    profile: ProfileData,
    anonymize: Boolean,
    onAnonymize: (Boolean) -> Unit,
    onImport: () -> Unit,
    onExport: (ProfileExport) -> Unit,
    onCompare: () -> Unit,
    onReport: () -> Unit,
    onPin: (String) -> Unit,
    onClear: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }
    Column {
        Spacer(Modifier.height(14.dp))
        SectionHeader("Инструменты")
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            Text("Экспорт", style = MaterialTheme.typography.titleSmall, color = JbText)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onExport(ProfileExport.XLSX) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = JbAccent.copy(alpha = 0.85f))
                ) {
                    Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Excel", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { onExport(ProfileExport.MESSAGES_CSV) }, modifier = Modifier.weight(1f)) {
                    Text("Сообщения", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { onExport(ProfileExport.AUDIENCE_CSV) }, modifier = Modifier.weight(1f)) {
                    Text("Аудитория", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onExport(ProfileExport.GRAPHML) }, modifier = Modifier.weight(1f)) {
                    Text("Граф (GraphML)", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { onExport(ProfileExport.DOSSIER) }, modifier = Modifier.weight(1f)) {
                    Text("Досье HTML", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAnonymize(!anonymize) }) {
                    Text(if (anonymize) "Анонимизация: вкл" else "Анонимизация: выкл", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onImport) {
                    Icon(Icons.Filled.UploadFile, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Выгрузка", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader("Анализ")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onReport,
                    modifier = Modifier.weight(1f),
                    enabled = !state.analysisRunning
                ) {
                    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.analysisRunning) "Готовим…" else "ИИ-досье", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onCompare, modifier = Modifier.weight(1f)) {
                    Text("Сравнить", fontSize = 12.sp)
                }
            }
            if (state.profileDossier.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    state.profileDossier,
                    style = MaterialTheme.typography.bodySmall,
                    color = JbText
                )
            }
            if (state.assistantError != null) {
                Spacer(Modifier.height(6.dp))
                Text(state.assistantError, style = MaterialTheme.typography.labelSmall, color = JbRed)
            }
            if (state.compareText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                SectionHeader("Сравнение профилей")
                Text(state.compareText, style = MaterialTheme.typography.bodySmall, color = JbMuted)
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader("Доступ и приватность")
            if (!state.profilePinSet) {
                if (showPin) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text("PIN из 4–6 цифр") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (pin.length >= 4) { onPin(pin); pin = ""; showPin = false } }) {
                            Text("Сохранить")
                        }
                        TextButton(onClick = { showPin = false }) { Text("Отмена") }
                    }
                } else {
                    TextButton(onClick = { showPin = true }) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Поставить PIN на раздел анализа", fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onClear) {
                Text("Удалить кэшированные данные профиля", fontSize = 12.sp, color = JbRed)
            }
        }
    }
}

@Composable
private fun MessageRow(message: ru.jbosint.osint.profile.ProfileMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(JbSurface.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.author.ifBlank { "неизвестный" },
                    style = MaterialTheme.typography.labelMedium,
                    color = JbAccent2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${ProfileBuilder.dateText(message.at)} ${ProfileBuilder.timeText(message.at)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
                if (message.type != MsgType.TEXT) {
                    Spacer(Modifier.width(8.dp))
                    Text(message.type.title, style = MaterialTheme.typography.labelSmall, color = JbAmber)
                }
            }
            if (message.replyTo.isNotBlank()) {
                Text(
                    "↩ ${message.replyTo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbGreen
                )
            }
            if (message.text.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = JbText,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProfileLockScreen(vm: AppViewModel) {
    var pin by remember { mutableStateOf("") }
    Backdrop {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.Lock, null, tint = JbAccent2, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(12.dp))
            Text("Раздел анализа защищён", style = MaterialTheme.typography.titleLarge, color = JbText)
            Spacer(Modifier.height(6.dp))
            Text(
                "Введите PIN для доступа к профилям",
                style = MaterialTheme.typography.bodySmall,
                color = JbMuted
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(6) },
                singleLine = true,
                label = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.unlockProfile(pin); pin = "" },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Войти")
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { vm.backHome() }) { Text("На главную") }
        }
    }
}
