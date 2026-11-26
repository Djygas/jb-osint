package ru.jbosint.lab.ui.screen

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.jbosint.lab.LabViewModel
import ru.jbosint.lab.model.LabLogLine
import ru.jbosint.lab.model.LabProject
import ru.jbosint.lab.model.LabScript
import ru.jbosint.lab.repository.GitHubLabRepository
import ru.jbosint.osint.ui.GlowCard
import ru.jbosint.osint.ui.JbAccent
import ru.jbosint.osint.ui.JbAccent2
import ru.jbosint.osint.ui.JbAmber
import ru.jbosint.osint.ui.JbFaint
import ru.jbosint.osint.ui.JbGreen
import ru.jbosint.osint.ui.JbMuted
import ru.jbosint.osint.ui.JbRed
import ru.jbosint.osint.ui.JbText
import ru.jbosint.osint.ui.Pill
import ru.jbosint.osint.ui.ScreenHeader

/**
 * Встроенная GitHub-лаборатория. Сервер работает внутри приложения
 * (Foreground Service, 127.0.0.1:8081) — Termux и ручная настройка не нужны.
 */
@Composable
fun GitHubLabScreen() {
    val context = LocalContext.current
    val repository = remember { GitHubLabRepository(context) }
    val labVm: LabViewModel = viewModel()
    val server by labVm.state.collectAsState()
    val scope = rememberCoroutineScope()

    var repoUrl by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf<List<LabLogLine>>(emptyList()) }
    var project by remember { mutableStateOf<LabProject?>(null) }
    var command by remember { mutableStateOf("") }
    var params by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val addLog: (String, Boolean) -> Unit = { text, isError ->
        logs = (logs + LabLogLine(text = text, error = isError)).takeLast(400)
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
                scope.launch {
                    try {
                        val result = repository.clone(repoUrl, ref)
                        project = result
                        command = result.scripts.firstOrNull()?.command().orEmpty()
                        note = buildString {
                            append("Готово: ").append(result.type.title)
                            if (result.scripts.isNotEmpty()) append(" · скриптов: ").append(result.scripts.size)
                        }
                        addLog("Репозиторий готов: ${result.dir}", false)
                        result.scripts.forEach { addLog("• ${it.path} (${it.type.title})", false) }
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
                busy = true
                stage = "Выполняю…"
                note = null
                addLog("$ $text", false)
                scope.launch {
                    try {
                        val result = repository.run(target.dir, text)
                        if (result.stdout.isNotBlank()) addLog(result.stdout.trimEnd(), false)
                        if (result.stderr.isNotBlank()) addLog(result.stderr.trimEnd(), true)
                        note = "Код ${result.exitCode} · ${result.tookMs} мс"
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
            subtitle = "Встроенный сервер · без Termux и ручной настройки"
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
            Spacer(Modifier.height(4.dp))
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
                Switch(
                    checked = server.running,
                    onCheckedChange = labVm::setEnabled,
                    enabled = !server.starting
                )
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
                server.note?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = JbMuted)
                }
            }
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
                        "Скрипты (.sh/.py) не найдены",
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
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Терминал",
                    style = MaterialTheme.typography.titleSmall,
                    color = JbText,
                    modifier = Modifier.weight(1f)
                )
                if (stage.isNotBlank()) {
                    Text(stage, style = MaterialTheme.typography.labelSmall, color = JbMuted)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { logs = emptyList() }) {
                    Text("Очистить", color = JbMuted)
                }
            }
            if (logs.isEmpty()) {
                Text(
                    "Вывод появится здесь. Команды выполняются в каталоге проекта.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbFaint
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    logs.takeLast(150).forEach { line ->
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
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Команда в каталоге проекта") },
                placeholder = { Text("например: sh script.sh") }
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { runCommand(command) },
                enabled = !busy && project != null && command.isNotBlank()
            ) { Text("Выполнить") }
        }

        Spacer(Modifier.height(10.dp))
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 12) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = clearEnv, enabled = !busy && project != null) {
                    Text("Очистить среду", color = JbRed)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = exportEnv, enabled = !busy && project != null) {
                    Text("Экспорт → JB-Osint/reports", color = JbAccent2)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Сервер слушает только 127.0.0.1 и работает внутри приложения (foreground service). " +
                "Скрипты запускаются по кнопке, с таймаутом 10 минут и белым списком команд. " +
                "Android-shell (.sh) работает нативно; .py требует установленный Python — обычно его нет.",
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
    onRun: (String) -> Unit
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
                Text(script.path, style = MaterialTheme.typography.labelSmall, color = JbFaint)
            }
            TextButton(onClick = { onRun(value) }, enabled = enabled) {
                Text("Запустить", color = JbAccent2)
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
