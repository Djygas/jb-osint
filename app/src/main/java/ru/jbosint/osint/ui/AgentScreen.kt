package ru.jbosint.osint.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.jbosint.osint.agent.AgentReport
import ru.jbosint.osint.agent.AgentTemplate
import ru.jbosint.osint.agent.CostTier
import ru.jbosint.osint.agent.StepState
import ru.jbosint.osint.agent.WatchItem
import ru.jbosint.osint.data.PhotoMatch
import ru.jbosint.osint.data.Notify
import ru.jbosint.osint.data.PhotoSearchResult
import ru.jbosint.osint.report.ReportExporter

/** Вкладки раздела «Агент». */
private enum class AgentTab(val title: String) {
    AGENT("Агент"),
    AUTO("Авто"),
    PHOTO("Поиск по фото"),
    WATCH("Объявления")
}

@Composable
fun AgentScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val tab = AgentTab.entries[state.agent.section.coerceIn(0, 3)]
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        ScreenHeader(
            eyebrow = "Автономный режим",
            title = "AI-агент",
            subtitle = "Сам выбирает модули: сначала бесплатные, затем платные — и не тратит токены зря"
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            AgentTab.entries.forEach { entry ->
                SectionChip(entry.title, tab == entry) { vm.setAgentSection(entry.ordinal) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            AgentTab.AGENT -> AgentTabContent(state, vm, context)
            AgentTab.AUTO -> AutoTabContent(state, vm, context)
            AgentTab.PHOTO -> PhotoTabContent(state, vm, context)
            AgentTab.WATCH -> WatchTabContent(state, vm)
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ------------------------------------------------------------------ агент

@Composable
private fun AgentTabContent(state: UiState, vm: AppViewModel, context: android.content.Context) {
    val agent = state.agent
    val clipboard = LocalClipboardManager.current
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = agent.query,
            onValueChange = vm::setAgentQuery,
            label = { Text("ФИО, телефон, email, ник, домен, VIN") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Тип: ${agent.target.title}", JbAccent2)
            Spacer(Modifier.width(8.dp))
            if (agent.history.isNotEmpty()) {
                Text(
                    "последние: ${agent.history.take(2).joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Глубина", style = MaterialTheme.typography.labelSmall, color = JbFaint)
            Spacer(Modifier.width(8.dp))
            listOf(1 to "1 проход", 2 to "2 прохода", 3 to "глубоко").forEach { (value, label) ->
                SectionChip(label, agent.depth == value) { vm.setAgentDepth(value) }
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::runAgent,
                enabled = !agent.running,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = JbAccent2.copy(alpha = 0.92f))
            ) {
                if (agent.running) {
                    CircularProgressIndicator(Modifier.size(15.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(7.dp))
                } else {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (agent.running) "Работаю…" else "Запустить агента")
            }
            if (agent.report != null) {
                OutlinedButton(onClick = vm::clearAgent) { Text("Сброс") }
            }
        }
        if (agent.running) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { agent.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = JbAccent2,
                trackColor = JbBorderSoft
            )
            Spacer(Modifier.height(4.dp))
            Text(agent.stage, style = MaterialTheme.typography.labelSmall, color = JbMuted)
        }
        agent.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = JbRed)
        }
    }

    val report = agent.report
    if (report == null) {
        if (!agent.running) {
            Spacer(Modifier.height(12.dp))
            DataStub(
                "Агент ещё не запускался. Введите запрос и нажмите «Запустить агента»: " +
                    "он сам определит тип, соберёт данные и сформулирует утверждения."
            )
        }
        return
    }

    Spacer(Modifier.height(12.dp))
    AgentPlanCard(report)
    Spacer(Modifier.height(12.dp))
    AgentFactsCard(report, context, clipboard)
    if (report.assumptions.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AgentAssumptionsCard(report)
    }
    Spacer(Modifier.height(12.dp))
    AgentModulesCard(report)
    Spacer(Modifier.height(12.dp))
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Отчёт")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.exportAgent(ReportExporter.Format.PDF) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Download, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("PDF", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { vm.exportAgent(ReportExporter.Format.XLSX) }, modifier = Modifier.weight(1f)) {
                Text("Excel", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { vm.exportAgent(ReportExporter.Format.JSON) }, modifier = Modifier.weight(1f)) {
                Text("JSON", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Шаблон отчёта", style = MaterialTheme.typography.labelSmall, color = JbFaint)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            AgentTemplate.entries.forEach { template ->
                SectionChip(template.title, false) { vm.exportAgentHtml(template) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = vm::openAgentProfile, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть профиль объекта")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "В документе источники заменены на «Модуль N», реальные названия и ссылки — в приложении B.",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
    }
}

@Composable
private fun AgentPlanCard(report: AgentReport) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("План и расход")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(report.target.title, JbAccent2)
            Spacer(Modifier.width(8.dp))
            Pill(
                if (report.freeOnly) "без платных API" else "платных: ${report.paidRequests}",
                if (report.freeOnly) JbGreen else JbAmber
            )
        }
        Spacer(Modifier.height(10.dp))
        report.plan.forEach { step ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val color = when (step.tier) {
                    CostTier.FREE -> JbGreen
                    CostTier.KEYED -> JbAccent
                    CostTier.PAID -> JbAmber
                }
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(color))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.bodyMedium, color = JbText)
                    Text(
                        buildString {
                            append(step.tier.title)
                            if (step.facts > 0) append(" · фактов: ${step.facts}")
                            if (step.note.isNotBlank()) append(" · ${step.note}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = JbMuted
                    )
                }
                Text(
                    step.state.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (step.state) {
                        StepState.DONE -> JbGreen
                        StepState.FAILED -> JbRed
                        StepState.SKIPPED -> JbFaint
                        StepState.RUNNING -> JbAccent2
                        StepState.PENDING -> JbMuted
                    }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${report.note} Время: ${report.durationMs / 1000} с.",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
    }
}

@Composable
private fun AgentFactsCard(
    report: AgentReport,
    context: android.content.Context,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Установленные факты")
        if (report.findings.isEmpty()) {
            DataStub("Агент не нашёл подтверждённых сведений. Проверьте запрос или добавьте ключи модулей.")
        }
        report.findings.take(30).forEach { finding ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(finding.text, style = MaterialTheme.typography.bodyMedium, color = JbText)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConfidencePill(finding.status, compact = true)
                    Spacer(Modifier.width(8.dp))
                    Text(finding.moduleLabel, style = MaterialTheme.typography.labelSmall, color = JbFaint)
                    Spacer(Modifier.weight(1f))
                    if (finding.evidence.isNotBlank()) {
                        Icon(
                            Icons.Filled.OpenInNew,
                            "Источник",
                            tint = JbFaint,
                            modifier = Modifier.size(14.dp).clickable {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(finding.evidence))) }
                            }
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Icon(
                        Icons.Filled.ContentCopy,
                        "Копировать",
                        tint = JbFaint,
                        modifier = Modifier.size(14.dp).clickable { clipboard.setText(AnnotatedString(finding.value)) }
                    )
                }
            }
        }
        if (report.findings.size > 30) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Показаны первые 30 из ${report.findings.size}. Полный список — в PDF/Excel.",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }
    }
}

@Composable
private fun AgentAssumptionsCard(report: AgentReport) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Аналитические предположения")
        Text(
            "Это не установленные факты — выводы, которые стоит проверить.",
            style = MaterialTheme.typography.labelSmall,
            color = JbAmber
        )
        Spacer(Modifier.height(8.dp))
        report.assumptions.forEach { assumption ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("• ${assumption.text}", style = MaterialTheme.typography.bodySmall, color = JbText)
                Text(
                    "основание: ${assumption.basis}",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
        }
    }
}

@Composable
private fun AgentModulesCard(report: AgentReport) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    GlowCard(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionHeader("Приложение B — реальные источники")
                Text(
                    if (expanded) "Нажмите, чтобы свернуть" else "Нажмите, чтобы раскрыть таблицу модулей",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge, color = JbAccent2)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            if (report.modules.isEmpty()) {
                Text("Источники не зафиксированы.", style = MaterialTheme.typography.bodySmall, color = JbMuted)
            }
            report.modules.forEach { module ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(module.label, style = MaterialTheme.typography.bodySmall, color = JbAccent2, modifier = Modifier.width(84.dp))
                    Text(
                        module.realName,
                        style = MaterialTheme.typography.bodySmall,
                        color = JbText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (module.url.isNotBlank()) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            "Копировать ссылку",
                            tint = JbFaint,
                            modifier = Modifier.size(14.dp).clickable { clipboard.setText(AnnotatedString(module.url)) }
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ авто

@Composable
private fun AutoTabContent(state: UiState, vm: AppViewModel, context: android.content.Context) {
    val auto = state.auto
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = auto.query,
            onValueChange = vm::setAutoQuery,
            label = { Text("VIN, номер кузова или госномер") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.providers.any { it.spec.id == "tronk" && it.hasKey }) "TRONK подключён"
                else "TRONK не подключён — только открытые данные",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.providers.any { it.spec.id == "tronk" && it.hasKey }) JbGreen else JbAmber
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::runAutoCheck, enabled = !auto.running) {
                if (auto.running) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Проверить")
            }
        }
        auto.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = JbMuted)
        }
    }

    val report = auto.report
    if (report == null) {
        Spacer(Modifier.height(12.dp))
        DataStub("Введите VIN или госномер: покажем локальный разбор, историю из TRONK (если ключ задан) и ссылки на официальные сервисы.")
        return
    }

    Spacer(Modifier.height(12.dp))
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Отчёт о проверке авто")
        if (report.summary.isEmpty()) {
            DataStub("Данных по этому VIN нет.")
        }
        report.summary.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(key, style = MaterialTheme.typography.bodySmall, color = JbMuted, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodySmall, color = JbText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (report.findings.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SectionHeader("Утверждения")
            report.findings.take(12).forEach { finding ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text(finding.text, style = MaterialTheme.typography.bodySmall, color = JbText)
                    Text(
                        "${finding.status.title} · ${finding.moduleLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
            }
        }
        if (report.assumptions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SectionHeader("Предположения")
            report.assumptions.forEach {
                Text("• ${it.text}", style = MaterialTheme.typography.bodySmall, color = JbAmber)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Проверить вручную")
        report.manualLinks.forEach { (title, url) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = JbText, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.OpenInNew, null, tint = JbFaint, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Официальные сервисы (ГИБДД, реестр залогов) не имеют публичного API — открываются вручную.",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
    }
    Spacer(Modifier.height(12.dp))
    SellerCard(state, vm)
}

/** Анализ продавца: телефон → каскадный поиск (Dyxless, TeleSINT, Numverify, Hunter). */
@Composable
private fun SellerCard(state: UiState, vm: AppViewModel) {
    val auto = state.auto
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Анализ продавца")
        Text(
            "Телефон уходит в общий каскад модулей: утечки, Telegram, оператор, корпоративные адреса.",
            style = MaterialTheme.typography.labelSmall,
            color = JbMuted
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = auto.sellerPhone,
            onValueChange = vm::setSellerPhone,
            label = { Text("Телефон продавца") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = vm::checkSeller, enabled = !auto.sellerRunning, modifier = Modifier.fillMaxWidth()) {
            if (auto.sellerRunning) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("Проверить продавца")
        }
        auto.sellerError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = JbRed)
        }
        val report = auto.sellerReport
        if (report != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "${report.note} Фактов: ${report.findings.size}",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
            report.findings.take(10).forEach { finding ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text(finding.text, style = MaterialTheme.typography.bodySmall, color = JbText)
                    Text(
                        "${finding.status.title} · ${finding.moduleLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ фото

@Composable
private fun PhotoTabContent(state: UiState, vm: AppViewModel, context: android.content.Context) {
    val photoUi = state.photoSearch
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let(vm::runPhotoSearch)
    }
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhotoCamera, null, tint = JbAccent2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Поиск по фотографии", style = MaterialTheme.typography.titleSmall, color = JbText)
                Text(
                    "Соцсети, мессенджеры, форумы и агрегаторы: совпадения со ссылками и статусом",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbMuted
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { picker.launch("image/*") },
            enabled = !photoUi.running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (photoUi.running) "Обрабатываю…" else "Выбрать фото")
        }
        if (photoUi.running) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { photoUi.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = JbAccent2,
                trackColor = JbBorderSoft
            )
            Spacer(Modifier.height(4.dp))
            Text(photoUi.stage, style = MaterialTheme.typography.labelSmall, color = JbMuted)
        }
        photoUi.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = JbRed)
        }
    }

    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Поиск по ссылке на изображение")
        Text(
            "Если фото уже есть в сети — аватар из профиля или картинка из объявления — вставьте ссылку: " +
                "Google Lens и Bing Visual Search ищут по адресу и не требуют загрузки файла.",
            style = MaterialTheme.typography.labelSmall,
            color = JbMuted
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = photoUi.urlQuery,
            onValueChange = vm::setPhotoUrlQuery,
            label = { Text("https://… ссылка на изображение") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = vm::runPhotoUrlSearch, enabled = !photoUi.urlBusy, modifier = Modifier.fillMaxWidth()) {
            if (photoUi.urlBusy) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("Искать по ссылке")
        }
        photoUi.urlError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = JbRed)
        }
        if (photoUi.urlMatches.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            photoUi.urlMatches.forEach { match ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.clip(RoundedCornerShape(7.dp)).background(JbAccent.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) { Text(match.platform, style = MaterialTheme.typography.labelSmall, color = JbAccent2) }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            match.title.ifBlank { match.url },
                            style = MaterialTheme.typography.bodySmall,
                            color = JbText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(match.url, style = MaterialTheme.typography.labelSmall, color = JbFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        Icons.Filled.OpenInNew,
                        "Открыть",
                        tint = JbMuted,
                        modifier = Modifier.size(16.dp).clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(match.url))) }
                        }
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    val result = photoUi.result
    if (result == null) {
        Spacer(Modifier.height(12.dp))
        DataStub(
            "Выберите фотографию: приложение посчитает SHA-256 и перцептивный хеш, разберёт EXIF, " +
                "запустит доступные сервисы обратного поиска и покажет совпадения со ссылками."
        )
        return
    }

    Spacer(Modifier.height(12.dp))
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarBadge(result.photo.fileName, 48)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    result.photo.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = JbText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(photoSummary(result), style = MaterialTheme.typography.labelSmall, color = JbMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(result.note, style = MaterialTheme.typography.labelSmall, color = JbFaint)
        if (result.found.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = vm::addPhotoToDossier, modifier = Modifier.fillMaxWidth()) {
                Text("Добавить в досье")
            }
            if (result.handles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.runAgentForHandle(result.handles.first()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Передать @${result.handles.first()} агенту")
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        SectionChip("Все (${result.matches.size})", photoUi.platform == null) { vm.setPhotoPlatform(null) }
        result.platforms.forEach { platform ->
            val count = result.matches.count { it.platform == platform }
            SectionChip("$platform ($count)", photoUi.platform == platform) { vm.setPhotoPlatform(platform) }
        }
    }
    Spacer(Modifier.height(8.dp))
    result.matches
        .filter { photoUi.platform == null || it.platform == photoUi.platform }
        .forEach { match ->
            Spacer(Modifier.height(8.dp))
            PhotoMatchCard(match) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(match.url))) }
            }
        }

    Spacer(Modifier.height(12.dp))
    Spacer(Modifier.height(12.dp))
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Проверить вручную")
        result.manualLinks.forEach { (title, url) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = JbText, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.OpenInNew, null, tint = JbFaint, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Эти сервисы не имеют публичного API: ссылка открывается в браузере, туда нужно загрузить фото вручную.",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
    }
}

private fun photoSummary(result: PhotoSearchResult): String = buildString {
    append("${result.photo.width}×${result.photo.height}")
    if (result.photo.faces > 0) append(" · лиц: ${result.photo.faces}")
    append(" · совпадений: ${result.matches.size}")
    if (result.handles.isNotEmpty()) append(" · ники: ${result.handles.take(3).joinToString(", ")}")
}

@Composable
private fun PhotoMatchCard(match: PhotoMatch, onOpen: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(JbAccent.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(match.platform, style = MaterialTheme.typography.labelSmall, color = JbAccent2)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${match.similarity}%",
                style = MaterialTheme.typography.labelMedium,
                color = if (match.similarity >= 90) JbGreen else if (match.similarity >= 60) JbAmber else JbMuted
            )
            Spacer(Modifier.weight(1f))
            ConfidencePill(match.status, compact = true)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            match.title.ifBlank { match.url },
            style = MaterialTheme.typography.bodyMedium,
            color = JbText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            match.url,
            style = MaterialTheme.typography.labelSmall,
            color = JbMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.OpenInNew, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Открыть", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(match.url)) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Ссылка", fontSize = 12.sp)
            }
        }
        if (match.handle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("Никнейм: @${match.handle}", style = MaterialTheme.typography.labelSmall, color = JbGreen)
        }
    }
}

// ------------------------------------------------------------------ мониторинг

@Composable
private fun WatchTabContent(state: UiState, vm: AppViewModel) {
    var title by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notify.permissionGranted(context)) {
        TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text("Включить уведомления о новых объявлениях", fontSize = 12.sp)
        }
    }

    GlowCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Новый критерий")
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Название (например, Camry 2021)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Что искать: марка, модель, объект") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = price,
            onValueChange = { price = it.filter { ch -> ch.isDigit() } },
            label = { Text("Максимальная цена, ₽ (необязательно)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                vm.addWatch(title, query, price.toIntOrNull() ?: 0)
                title = ""
                query = ""
                price = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Добавить в мониторинг")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Проверка идёт по открытому веб-индексу, без ключей. Фоновая работа без открытого приложения не выполняется.",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
    }

    if (state.watches.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "МОИ КРИТЕРИИ (${state.watches.size})",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = vm::checkAllWatches) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Проверить все", fontSize = 12.sp)
            }
        }
    }
    state.watches.forEach { watch ->
        Spacer(Modifier.height(8.dp))
        WatchCard(watch, state.watchNotes[watch.id], vm)
    }
    if (state.watches.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        DataStub("Критериев пока нет. Добавьте марку и модель — приложение будет проверять новые объявления по кнопке.")
    }
}

@Composable
private fun WatchCard(watch: WatchItem, note: String?, vm: AppViewModel) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, null, tint = JbAccent2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(watch.title, style = MaterialTheme.typography.titleSmall, color = JbText)
                Text(
                    buildString {
                        append(watch.query)
                        if (watch.priceMax > 0) append(" · до ${watch.priceMax} ₽")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = JbMuted
                )
            }
            SmallIconButton({ vm.checkWatch(watch.id) }) {
                Icon(Icons.Filled.Refresh, "Проверить", tint = JbMuted, modifier = Modifier.size(16.dp))
            }
            SmallIconButton({ vm.removeWatch(watch.id) }) {
                Icon(Icons.Filled.Delete, "Удалить", tint = JbFaint, modifier = Modifier.size(16.dp))
            }
        }
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(note, style = MaterialTheme.typography.labelSmall, color = JbAccent2)
        }
    }
}

@Composable
private fun SmallIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}
