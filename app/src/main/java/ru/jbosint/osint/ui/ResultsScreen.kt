package ru.jbosint.osint.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.data.AssistantClassification
import ru.jbosint.osint.core.report.ReportNaming
import ru.jbosint.osint.report.ReportExporter

private fun categoryColor(category: FactCategory): Color = when (category) {
    FactCategory.PERSON -> Color(0xFF6E7BFF)
    FactCategory.CONTACT -> Color(0xFF38BDF8)
    FactCategory.ORGANIZATION -> Color(0xFFA78BFA)
    FactCategory.LOCATION -> Color(0xFF34D399)
    FactCategory.TECHNICAL -> Color(0xFF7C8AA5)
    FactCategory.DOCUMENT -> Color(0xFFFBBF24)
    FactCategory.OTHER -> Color(0xFF94A3B8)
}

@Composable
fun ResultsScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ReportExporter.Format?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val format = pending
        pending = null
        if (granted && format != null) vm.export(format)
        else if (format != null) vm.export(format) // экспорт продолжит работу с приватной папкой
    }

    fun requestExport(format: ReportExporter.Format) {
        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = format
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.export(format)
        }
    }

    val data = state.result
    if (data == null) {
        Backdrop {
            Column(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyHint(
                    icon = Icons.Filled.Info,
                    title = "Нет данных",
                    subtitle = "Вернитесь к поиску и выполните запрос."
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.backHome() },
                    colors = ButtonDefaults.buttonColors(containerColor = JbAccent),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("К поиску") }
            }
        }
        return
    }

    var selected by remember(data.query) { mutableStateOf<FactCategory?>(null) }
    val visibleFacts = remember(data.facts, selected) {
        if (selected == null) data.facts
        else data.facts.filter { it.category == selected }
    }
    val reportId = remember(state.resultEntry?.id) {
        ReportNaming.reportId(data.query, state.resultEntry?.createdAt ?: 0L)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.backHome() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = JbText)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Отчёт об исследовании", style = MaterialTheme.typography.titleLarge, color = JbText)
                    Text("Поиск JB OSINT", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                }
            }
        }

        item { HeroCard(data, reportId, state.analyst, state.resultEntry?.createdAt ?: System.currentTimeMillis()) }

        val photo = state.photo
        if (photo != null || !state.resultEntry?.photoNote.isNullOrBlank()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(JbSurface)
                        .border(1.dp, JbBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PhotoCamera, contentDescription = null,
                            tint = JbAccent2, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Исследование по фотографии",
                            style = MaterialTheme.typography.labelMedium, color = JbAccent2
                        )
                        Spacer(Modifier.weight(1f))
                        if (photo != null && photo.hits > 0) Pill("совпадений: ${photo.hits}", JbGreen)
                    }
                    if (photo != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(photo.fileName, style = MaterialTheme.typography.titleSmall, color = JbText)
                        Text(photo.summary, style = MaterialTheme.typography.bodySmall, color = JbMuted)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "SHA-256: ${photo.sha256.take(32)}…",
                            style = MaterialTheme.typography.labelSmall, color = JbFaint
                        )
                    }
                    val note = state.resultEntry?.photoNote.orEmpty()
                    if (note.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(note, style = MaterialTheme.typography.bodySmall, color = JbMuted)
                    }
                }
            }
        }

        item {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("фактов", data.facts.size.toString(), Modifier.weight(1f))
                    StatTile("связей", data.links.size.toString(), Modifier.weight(1f))
                    StatTile(
                        "подтверждено",
                        (data.confidenceCounts[Confidence.CONFIRMED] ?: 0).toString(),
                        Modifier.weight(1f),
                        JbGreen
                    )
                    StatTile(
                        "модулей",
                        "${data.stats.sourcesResponded}/${data.stats.sourcesAttempted}",
                        Modifier.weight(1f),
                        JbAccent2
                    )
                }
            }
        }

        item { ConfidenceCard(data) }

        item { AssistantResultCard(state, vm) }

        item {
            Column {
                SectionHeader("Разделы")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selected == null,
                        onClick = { selected = null },
                        label = { Text("Все · ${data.facts.size}") },
                        colors = chipColors()
                    )
                    data.categoryCounts.entries
                        .sortedBy { it.key.order }
                        .forEach { (category, count) ->
                            FilterChip(
                                selected = selected == category,
                                onClick = { selected = if (selected == category) null else category },
                                label = { Text("${category.short} · $count") },
                                colors = chipColors()
                            )
                        }
                }
            }
        }

        if (visibleFacts.isEmpty()) {
            item {
                EmptyHint(
                    icon = Icons.Filled.Info,
                    title = "Сведений не найдено",
                    subtitle = "Попробуйте другой формат запроса или подключите дополнительные модули."
                )
            }
        } else {
            items(visibleFacts, key = { "${it.category}-${it.kind}-${it.value}-${it.subject}" }) { fact ->
                FactCard(fact, state.classifications[fact.value])
            }
        }

        if (data.links.isNotEmpty()) {
            item { LinksSection(data) }
        }

        item { ExportCard(state.exporting, onExport = ::requestExport) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(JbSurface)
                    .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = JbFaint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Сведения получены из открытых источников и могут содержать неточности. " +
                        "Оценка достоверности отражает число независимых подтверждений, а не юридическую значимость. " +
                        "Используйте материал в рамках применимого законодательства.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbFaint
                )
            }
        }
    }
}

@Composable
private fun HeroCard(data: AggregatedData, reportId: String, analyst: String, createdAt: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFF1B2350), Color(0xFF141A2C))
                )
            )
            .border(1.dp, JbAccent.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) {
        Text("ОБЪЕКТ ИССЛЕДОВАНИЯ", style = MaterialTheme.typography.labelSmall, color = JbAccent2)
        Spacer(Modifier.height(6.dp))
        Text(
            data.query,
            style = MaterialTheme.typography.headlineMedium,
            color = JbText,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(reportId, JbAccent)
            Pill(data.stats.durationMs.let { "${it / 1000.0}".take(4) + " с" }, JbMuted)
        }
        Spacer(Modifier.height(12.dp))
        GradientDivider()
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Аналитик", analyst.ifBlank { "Не указан" })
        Spacer(Modifier.height(3.dp))
        KeyValueRow("Сформирован", dateText(createdAt))
        Spacer(Modifier.height(3.dp))
        KeyValueRow("Классификация", "Для внутреннего использования")
    }
}

@Composable
private fun ConfidenceCard(data: AggregatedData) {
    val total = data.facts.size.coerceAtLeast(1)
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Text("ДОСТОВЕРНОСТЬ", style = MaterialTheme.typography.labelSmall, color = JbFaint)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
        ) {
            Confidence.entries.reversed().forEach { level ->
                val count = data.confidenceCounts[level] ?: 0
                if (count > 0) {
                    Box(
                        modifier = Modifier
                            .weight(count.toFloat())
                            .height(8.dp)
                            .background(confidenceColor(level))
                    )
                }
            }
            if (data.facts.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(8.dp).background(JbSurfaceHigh))
            }
        }
        Spacer(Modifier.height(12.dp))
        Confidence.entries.reversed().forEach { level ->
            val count = data.confidenceCounts[level] ?: 0
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(confidenceColor(level)))
                Spacer(Modifier.width(8.dp))
                Text(level.title, style = MaterialTheme.typography.bodySmall, color = JbText)
                Spacer(Modifier.width(6.dp))
                Text(level.note, style = MaterialTheme.typography.labelSmall, color = JbFaint, modifier = Modifier.weight(1f))
                Text(
                    "$count · ${count * 100 / total}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = confidenceColor(level)
                )
            }
        }
    }
}

@Composable
private fun FactCard(fact: Fact, classification: AssistantClassification? = null) {
    val color = categoryColor(fact.category)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(JbSurface)
            .border(1.dp, JbBorderSoft, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            }
            Spacer(Modifier.width(9.dp))
            Text(
                "${fact.category.short} · ${fact.kind}",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ConfidencePill(fact.confidence)
        }
        Spacer(Modifier.height(9.dp))
        Text(fact.value, style = MaterialTheme.typography.titleMedium, color = JbText)
        if (fact.subject.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text("Субъект: ${fact.subject}", style = MaterialTheme.typography.bodySmall, color = JbMuted)
        }
        if (classification != null) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("ИИ: ${classification.typeLabel}", JbAccent2)
                Pill("релевантность: ${classification.relevanceLabel}", JbSlate)
            }
        }
        if (fact.attributes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            fact.attributes.entries.take(8).forEach { (key, value) ->
                KeyValueRow(key, value)
                Spacer(Modifier.height(2.dp))
            }
            if (fact.attributes.size > 8) {
                Text(
                    "и ещё ${fact.attributes.size - 8} полей — полностью в отчёте",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            "независимых подтверждений: ${fact.sourceCount}",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
    }
}

@Composable
private fun LinksSection(data: AggregatedData) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        SectionHeader("Граф связей")
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
            data.links.take(12).forEachIndexed { index, link ->
                if (index > 0) Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        link.from,
                        style = MaterialTheme.typography.bodySmall,
                        color = JbText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        " —${link.relation}→ ",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbAccent2,
                        maxLines = 1
                    )
                    Text(
                        link.to,
                        style = MaterialTheme.typography.bodySmall,
                        color = JbText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "×${link.sourceCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint
                    )
                }
            }
            if (data.links.size > 12) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "и ещё ${data.links.size - 12} связей — в полном отчёте",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
        }
    }
}

@Composable
private fun ExportCard(exporting: Boolean, onExport: (ReportExporter.Format) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        SectionHeader("Экспорт")
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
            Text("Отчёт с оценкой достоверности, методологией и графом связей.", style = MaterialTheme.typography.bodySmall, color = JbMuted)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportButton("PDF", Icons.Filled.PictureAsPdf, Modifier.weight(1f), exporting) {
                    onExport(ReportExporter.Format.PDF)
                }
                ExportButton("Excel", Icons.Filled.TableChart, Modifier.weight(1f), exporting) {
                    onExport(ReportExporter.Format.XLSX)
                }
                ExportButton("JSON", Icons.Filled.DataObject, Modifier.weight(1f), exporting) {
                    onExport(ReportExporter.Format.JSON)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (exporting) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), color = JbAccent2, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(7.dp))
                }
                Icon(Icons.Filled.Share, contentDescription = null, tint = JbFaint, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (exporting) "Формирование файла…"
                    else "Файл сохраняется в JB_OSINT_Reports и открывается меню «Поделиться»",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
        }
    }
}

@Composable
private fun ExportButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        enabled = !enabled,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, JbAccent.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC7CCFF))
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = JbSurface,
    labelColor = JbMuted,
    selectedContainerColor = JbAccent.copy(alpha = 0.22f),
    selectedLabelColor = Color(0xFFD5D9FF)
)

/** Блок ассистента в результатах: анализ, веб-исследование и резюме для отчёта. */
@Composable
private fun AssistantResultCard(state: UiState, vm: AppViewModel) {
    val entry = state.resultEntry
    val analysis = entry?.aiAnalysis.orEmpty()
    val narrative = entry?.aiNarrative.orEmpty()
    val busy = state.analysisRunning || state.researchRunning

    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        SectionHeader("Интеллектуальный ассистент")
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Анализ открытых источников",
                    style = MaterialTheme.typography.titleSmall,
                    color = JbText,
                    modifier = Modifier.weight(1f)
                )
                if (state.assistantBusy.isNotBlank()) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = JbAccent2, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(state.assistantBusy, style = MaterialTheme.typography.labelSmall, color = JbAccent2)
                } else if (state.assistantConfigured) {
                    Pill(
                        if (analysis.isNotBlank()) "Вывод готов" else "Готов",
                        if (analysis.isNotBlank()) JbGreen else JbFaint
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (!state.assistantConfigured) {
                Text(
                    "Подключите ключ Hugging Face, чтобы получать аналитические выводы, разметку типов " +
                        "и краткую выжимку из открытого веба.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.navigate(Screen.SETTINGS) },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JbAccent.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC7CCFF))
                ) { Text("Настроить ассистента", style = MaterialTheme.typography.labelMedium) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = vm::analyzeResult,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JbAccent.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC7CCFF))
                    ) {
                        Text(
                            if (state.analysisRunning) "Анализ…" else if (analysis.isBlank()) "Проанализировать" else "Обновить анализ",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    OutlinedButton(
                        onClick = vm::researchWeb,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JbAccent.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC7CCFF))
                    ) {
                        Text(
                            if (state.researchRunning) "Поиск…" else "Интернет-поиск",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::synthesizeReport,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JbBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JbMuted)
                ) {
                    Text(
                        if (state.analysisRunning && state.assistantBusy == "Синтезатор отчёта") "Оформление…"
                        else if (narrative.isBlank()) "Оформить резюме для отчёта" else "Переоформить резюме",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (state.assistantError != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.assistantError, style = MaterialTheme.typography.bodySmall, color = JbAmber)
            }
            if (state.researchError != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.researchError, style = MaterialTheme.typography.bodySmall, color = JbAmber)
            }

            if (analysis.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                Text("АНАЛИТИЧЕСКИЙ ВЫВОД", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                Spacer(Modifier.height(6.dp))
                Text(analysis, style = MaterialTheme.typography.bodySmall, color = JbText)
            }

            val research = state.research.ifBlank { entry?.aiResearch.orEmpty() }
            if (research.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ИНТЕРНЕТ-ИССЛЕДОВАНИЕ", style = MaterialTheme.typography.labelSmall, color = JbFaint,
                        modifier = Modifier.weight(1f))
                    Text(
                        "Скрыть",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbAccent2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { vm.clearResearch() }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(research, style = MaterialTheme.typography.bodySmall, color = JbText)
            }

            if (narrative.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                Text("РЕЗЮМЕ ДЛЯ ОТЧЁТА", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                Spacer(Modifier.height(6.dp))
                Text(narrative, style = MaterialTheme.typography.bodySmall, color = JbText)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Раздел добавлен в PDF, Excel и JSON при экспорте.",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            }
        }
    }
}
