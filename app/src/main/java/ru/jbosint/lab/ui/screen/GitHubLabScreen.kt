package ru.jbosint.lab.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.jbosint.lab.LabLaunch
import ru.jbosint.lab.LabViewModel
import ru.jbosint.lab.model.LabLogLine
import ru.jbosint.lab.model.LabProject
import ru.jbosint.lab.model.LabProjectType
import ru.jbosint.lab.model.LabResultExport
import ru.jbosint.lab.model.LabResultParser
import ru.jbosint.lab.model.LabRuntime
import ru.jbosint.lab.model.LabScript
import ru.jbosint.lab.repository.GitHubLabRepository
import ru.jbosint.osint.ui.GlowCard
import ru.jbosint.osint.ui.JbAccent
import ru.jbosint.osint.ui.JbAccent2
import ru.jbosint.osint.ui.JbAmber
import ru.jbosint.osint.ui.JbBorderSoft
import ru.jbosint.osint.ui.JbFaint
import ru.jbosint.osint.ui.JbGreen
import ru.jbosint.osint.ui.JbMuted
import ru.jbosint.osint.ui.JbRed
import ru.jbosint.osint.ui.JbText
import ru.jbosint.osint.ui.Pill
import ru.jbosint.osint.ui.ScreenHeader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class LabHistoryItem(val command: String, val at: Long, val exitCode: Int)

/**
 * Встроенная GitHub-лаборатория: сервер в foreground service, языки по расширению
 * и shebang, ручной терминал с историей, JSON-результаты и экспорт.
 */
@Composable
fun GitHubLabScreen() {
    val context = LocalContext.current
    val repository = remember { GitHubLabRepository(context) }
    val labVm: LabViewModel = viewModel()
    val server by labVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var repoUrl by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf<List<LabLogLine>>(emptyList()) }
    var project by remember { mutableStateOf<LabProject?>(null) }
    var command by remember { mutableStateOf("") }
    var timeoutSec by remember { mutableStateOf("300") }
    var params by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var history by remember { mutableStateOf<List<LabHistoryItem>>(emptyList()) }
    var runtimes by remember { mutableStateOf<List<LabRuntime>>(emptyList()) }
    var lastOutput by remember { mutableStateOf("") }

    val cards = remember(lastOutput) { LabResultParser.parse(lastOutput) }

    val addLog: (String, Boolean) -> Unit = { text, isError ->
        logs = (logs + LabLogLine(text = text, error = isError)).takeLast(400)
    }
    val copy: (String) -> Unit = { text -> clipboard.setText(AnnotatedString(text)) }

    LaunchedEffect(server.running) {
        if (server.running) {
            runCatching { repository.runtimes() }.onSuccess { runtimes = it }
        }
    }

    LaunchedEffect(Unit) {
        LabLaunch.consume()?.let { request ->
            project = LabProject(
                url = request.title,
                ref = "",
                dir = request.dir,
                type = LabProjectType.OTHER
            )
            command = ""
            lastOutput = ""
            note = "Открыт дамп в лаборатории: ${request.title}"
        }
    }

    fun refreshRuntimes() {
        if (!server.running) {
            note = "Сначала включите лабораторию"
            return
        }
        scope.launch {
            runCatching { repository.runtimes() }
                .onSuccess { runtimes = it; note = "Рантаймы обновлены" }
                .onFailure { note = it.message ?: "Не удалось получить рантаймы" }
        }
    }

    val clone: () -> Unit = {
        when {
            busy -> Unit
            repoUrl.isBlank() -> note = "Укажите ссылку https://github.com/owner/repo"
            else -> {
                busy = true
                stage = "Скачиваю репозиторий…"
                note = null
                logs = emptyList()
                project = null
                lastOutput = ""
                scope.launch {
                    try {
                        val result = repository.clone(repoUrl, ref)
                        project = result
                        command = result.scripts.firstOrNull { it.available }?.command()
                            ?: result.scripts.firstOrNull()?.command().orEmpty()
                        note = buildString {
                            append("Готово: ").append(result.type.title)
                            if (result.scripts.isNotEmpty()) append(" · скриптов: ").append(result.scripts.size)
                        }
                        addLog("Репозиторий готов: ${result.dir}", false)
                        result.scripts.forEach {
                            addLog(
                                "• ${it.path} (${it.type.title}${if (!it.available) ", нет рантайма" else ""})",
                                !it.available
                            )
                        }
                        runCatching { repository.runtimes() }.onSuccess { runtimes = it }
                    } catch (t: Throwable) {
                        note = t.message ?: "Ошибка загрузки"
                        addLog(t.message ?: "Ошибка загрузки", true)
                    } finally {
                        busy = false
                        stage = ""
                    }
                }
            }
        }
    }

    val runCommand: (String) -> Unit = { raw ->
        val target = project
        val text = raw.trim()
        when {
            busy -> Unit
            target == null -> note = "Сначала скачайте репозиторий"
            text.isBlank() -> note = "Введите команду"
            else -> {
                val timeout = timeoutSec.toIntOrNull()?.coerceIn(30, 1800) ?: 300
                busy = true
                stage = "Выполняю…"
                note = null
                addLog("$ $text", false)
                scope.launch {
                    try {
                        val result = repository.run(target.dir, text, timeout)
                        lastOutput = result.stdout
                        if (result.stdout.isNotBlank()) addLog(result.stdout.trimEnd(), result.exitCode != 0)
                        if (result.stderr.isNotBlank()) addLog(result.stderr.trimEnd(), true)
                        note = "Код ${result.exitCode} · ${result.tookMs} мс"
                        history = (listOf(LabHistoryItem(text, System.currentTimeMillis(), result.exitCode)) + history)
                            .take(50)
                    } catch (t: Throwable) {
                        note = t.message ?: "Ошибка выполнения"
                        addLog(t.message ?: "Ошибка выполнения", true)
                    } finally {
                        busy = false
                        stage = ""
                    }
                }
            }
        }
    }

    val installDeps: () -> Unit = {
        val target = project
        if (!busy && target != null) {
            busy = true
            stage = "Устанавливаю зависимости…"
            addLog("$ dependencies", false)
            scope.launch {
                try {
                    val result = repository.dependencies(target.dir)
                    lastOutput = result.stdout
                    if (result.stdout.isNotBlank()) addLog(result.stdout.trimEnd(), result.exitCode != 0)
                    note = if (result.exitCode == 0) "Зависимости: готово" else "Зависимости: код ${result.exitCode}"
                    history = (listOf(LabHistoryItem("dependencies", System.currentTimeMillis(), result.exitCode)) + history)
                        .take(50)
                } catch (t: Throwable) {
                    note = t.message ?: "Ошибка установки"
                    addLog(t.message ?: "Ошибка установки", true)
                } finally {
                    busy = false
                    stage = ""
                }
            }
        }
    }

    val clearEnv: () -> Unit = {
        val target = project
        if (!busy && target != null) {
            busy = true
            stage = "Удаляю среду…"
            scope.launch {
                try {
                    repository.clear(target.dir)
                    addLog("Среда удалена: ${target.dir}", false)
                    project = null
                    lastOutput = ""
                    note = "Среда удалена"
                } catch (t: Throwable) {
                    note = t.message ?: "Ошибка"
                    addLog(t.message ?: "Ошибка", true)
                } finally {
                    busy = false
                    stage = ""
                }
            }
        }
    }

    val exportEnv: () -> Unit = {
        val target = project
        if (!busy && target != null) {
            busy = true
            stage = "Экспорт…"
            scope.launch {
                try {
                    val path = repository.export(target.dir)
                    note = if (path.isBlank()) "Экспорт не удался" else "Экспорт: $path"
                    addLog(note ?: "", false)
                } catch (t: Throwable) {
                    note = t.message ?: "Ошибка экспорта"
                    addLog(t.message ?: "Ошибка экспорта", true)
                } finally {
                    busy = false
                    stage = ""
                }
            }
        }
    }

    val exportResults: (String) -> Unit = { kind ->
        val parsed = LabResultParser.parse(lastOutput)
        if (parsed.isNullOrEmpty()) {
            note = "Нет JSON-результатов для экспорта"
        } else {
            val payload = when (kind) {
                "JSON" -> LabResultExport.toJson(parsed)
                "CSV" -> "\uFEFF" + LabResultExport.toCsv(parsed)
                else -> LabResultExport.toHtml(parsed)
            }
            val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "JB-Osint/reports")
                .apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val file = File(folder, "lab-results-$stamp.${kind.lowercase()}")
            runCatching {
                file.writeBytes(payload.toByteArray(Charsets.UTF_8))
                note = "Экспорт: ${file.absolutePath}"
            }.onFailure { note = "Экспорт не удался: ${it.message}" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        ScreenHeader(
            eyebrow = "Инструменты",
            title = "GitHub-лаборатория",
            subtitle = "Встроенный сервер · языки, терминал и JSON-результаты"
        )
        Spacer(Modifier.height(12.dp))

        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Встроенная лаборатория",
                    style = MaterialTheme.typography.titleSmall,
                    color = JbText,
                    modifier = Modifier.weight(1f)
                )
                Pill(
                    when {
                        server.starting -> "запуск…"
                        server.running -> "запущена"
                        else -> "выключена"
                    },
                    when {
                        server.starting -> JbAmber
                        server.running -> JbGreen
                        else -> JbFaint
                    }
                )
            }
            Text(
                "Сервер работает внутри приложения на 127.0.0.1:8081. Termux не нужен.",
                style = MaterialTheme.typography.bodySmall,
                color = JbMuted
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Включить лабораторию",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JbText,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = server.running, onCheckedChange = labVm::setEnabled, enabled = !server.starting)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = labVm::check, enabled = !server.starting) {
                    if (server.starting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = JbText
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Проверить сервер")
                }
                Spacer(Modifier.width(8.dp))
                server.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = JbMuted) }
            }
        }

        Spacer(Modifier.height(10.dp))
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Языки и рантаймы",
                    style = MaterialTheme.typography.titleSmall,
                    color = JbText,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { refreshRuntimes() }, enabled = !busy) {
                    Text("Обновить", color = JbAccent2)
                }
            }
            if (runtimes.isEmpty()) {
                Text(
                    if (server.running) "Загрузка…" else "Включите лабораторию, чтобы проверить рантаймы",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbFaint
                )
            } else {
                runtimes.forEach { runtime ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            runtime.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = JbText,
                            modifier = Modifier.weight(1f)
                        )
                        if (runtime.available) {
                            Pill("есть", JbGreen)
                        } else {
                            Pill("нет", JbFaint)
                            runtime.install?.let { install ->
                                TextButton(onClick = { copy(install) }) {
                                    Text("Команда", color = JbAccent2)
                                }
                            }
                        }
                    }
                }
            }
            Text(
                "Python/Node не встроены в APK: Android не даёт запускать бинарники из filesDir. " +
                    "Установите интерпретатор через Termux/систему и нажмите «Обновить».",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }

        Spacer(Modifier.height(10.dp))
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
            Text("Репозиторий", style = MaterialTheme.typography.titleSmall, color = JbText)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = repoUrl,
                onValueChange = { repoUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("https://github.com/owner/repo") }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ref,
                onValueChange = { ref = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Ветка или тег (необязательно)") }
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = clone,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = JbAccent)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = JbText
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Загрузить и просканировать")
            }
        }

        project?.let { current ->
            Spacer(Modifier.height(10.dp))
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Проект",
                        style = MaterialTheme.typography.titleSmall,
                        color = JbText,
                        modifier = Modifier.weight(1f)
                    )
                    Pill(current.type.title, JbGreen)
                }
                Spacer(Modifier.height(6.dp))
                Text(current.url, style = MaterialTheme.typography.bodySmall, color = JbText)
                Text(current.dir, style = MaterialTheme.typography.labelSmall, color = JbFaint)
                Spacer(Modifier.height(8.dp))
                if (current.scripts.isEmpty()) {
                    Text(
                        "Скрипты (.py/.js/.sh/.go/.php/.pl/.rb) не найдены",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbFaint
                    )
                } else {
                    current.scripts.forEach { script ->
                        ScriptRow(
                            script = script,
                            value = params[script.path].orEmpty(),
                            onValue = { value -> params = params + (script.path to value) },
                            enabled = !busy,
                            onRun = { value ->
                                val suffix = if (script.param != null && value.isNotBlank()) {
                                    " " + quoteArg(value)
                                } else ""
                                runCommand(script.command() + suffix)
                            },
                            onInstall = { script.installHint?.let(copy) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = installDeps, enabled = !busy && current.scripts.isNotEmpty()) {
                    Text("Установить зависимости", color = JbAccent2)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ручной терминал",
                    style = MaterialTheme.typography.titleSmall,
                    color = JbText,
                    modifier = Modifier.weight(1f)
                )
                if (stage.isNotBlank()) {
                    Text(stage, style = MaterialTheme.typography.labelSmall, color = JbMuted)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { logs = emptyList() }) { Text("Очистить", color = JbMuted) }
            }
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = JbAccent2,
                    trackColor = JbBorderSoft
                )
                Spacer(Modifier.height(6.dp))
            }
            project?.let { current ->
                if (current.scripts.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        current.scripts.take(8).forEach { script ->
                            TextButton(onClick = { command = script.command() }) {
                                Text(script.path, style = MaterialTheme.typography.labelSmall, color = JbAccent2)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Команда в каталоге проекта") },
                    placeholder = { Text("например: sh script.sh | head") }
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = timeoutSec,
                    onValueChange = { timeoutSec = it.filter { ch -> ch.isDigit() }.take(4) },
                    modifier = Modifier.width(96.dp),
                    singleLine = true,
                    label = { Text("Таймаут, с") }
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { runCommand(command) },
                enabled = !busy && project != null && command.isNotBlank()
            ) { Text("Выполнить") }
            if (logs.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Вывод появится здесь. Поддерживаются пайпы, редиректы и переменные.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbFaint
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    logs.takeLast(200).forEach { line ->
                        Text(
                            line.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (line.error) JbRed else JbMuted,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            note?.let { text ->
                Spacer(Modifier.height(8.dp))
                val problem = text.contains("Ошибка", true) || text.contains("не ", true) ||
                    text.contains("Сначала", true) || text.contains("нет ", true)
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem) JbAmber else JbGreen
                )
            }
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("История", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                history.take(10).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.command,
                                style = MaterialTheme.typography.bodySmall,
                                color = JbText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${time(item.at)} · код ${item.exitCode}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.exitCode == 0) JbMuted else JbRed
                            )
                        }
                        TextButton(onClick = { runCommand(item.command) }, enabled = !busy) {
                            Text("Повторить", color = JbAccent2)
                        }
                    }
                }
            }
        }

        if (!cards.isNullOrEmpty()) {
            Spacer(Modifier.height(10.dp))
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Результаты (${cards.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = JbText,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { exportResults("JSON") }) { Text("JSON", color = JbAccent2) }
                    TextButton(onClick = { exportResults("CSV") }) { Text("CSV", color = JbAccent2) }
                    TextButton(onClick = { exportResults("HTML") }) { Text("HTML", color = JbAccent2) }
                }
                cards.forEach { card ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        if (card.service.isNotBlank()) {
                            Text(card.service, style = MaterialTheme.typography.bodyMedium, color = JbText)
                        }
                        if (card.password.isNotBlank()) {
                            Text("Пароль/хеш: ${card.password}", style = MaterialTheme.typography.bodySmall, color = JbAmber)
                        }
                        if (card.date.isNotBlank()) {
                            Text("Дата: ${card.date}", style = MaterialTheme.typography.labelSmall, color = JbMuted)
                        }
                        if (card.url.isNotBlank()) {
                            Text(
                                card.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = JbAccent2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        card.extra.forEach { (key, value) ->
                            Text("$key: $value", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 12) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = clearEnv, enabled = !busy && project != null) {
                    Text("Очистить среду", color = JbRed)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = exportEnv, enabled = !busy && project != null) {
                    Text("Экспорт лога и артефактов", color = JbAccent2)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Сервер слушает только 127.0.0.1. Опасные команды (rm -rf /, mkfs, dd if=/dev/zero, sudo/su/doas) " +
                "блокируются, таймаут по умолчанию 300 с, память ограничена ulimit. " +
                "Скрипты запускаются только по кнопке; вывод может содержать чувствительные данные.",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ScriptRow(
    script: LabScript,
    value: String,
    onValue: (String) -> Unit,
    enabled: Boolean,
    onRun: (String) -> Unit,
    onInstall: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    script.button ?: script.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = JbText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    script.type.title + if (script.available) "" else " · нет интерпретатора",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (script.available) JbFaint else JbAmber
                )
                script.description?.let { description ->
                    Text(
                        description,
                        style = MaterialTheme.typography.labelSmall,
                        color = JbMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!script.available && script.installHint != null) {
                TextButton(onClick = onInstall) { Text("Установить", color = JbAccent2) }
            }
            TextButton(onClick = { onRun(value) }, enabled = enabled && script.available) {
                Text("Запустить", color = if (script.available) JbAccent2 else JbFaint)
            }
        }
        script.param?.let { label ->
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(label) }
            )
        }
    }
}

private fun quoteArg(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

private fun time(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))
