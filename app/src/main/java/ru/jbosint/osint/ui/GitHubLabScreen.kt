package ru.jbosint.osint.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.data.GitHubLabConfig
import ru.jbosint.osint.data.GitHubLabSpec
import ru.jbosint.osint.data.TermuxSpec

/**
 * GitHub-лаборатория: клонирование репозитория в Termux, запуск команд по явному
 * действию, логи, сборка артефактов и экспорт. Работает без root, в отдельной
 * папке с правами 700 и с таймаутом на выполнение.
 */
@Composable
fun GitHubLabScreen(state: UiState, vm: AppViewModel) {
    val lab = state.lab
    val project = lab.project
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var permGranted by remember { mutableStateOf(TermuxSpec.isPermissionGranted(context)) }
    var pendingClone by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permGranted = granted
        if (granted && pendingClone) {
            pendingClone = false
            vm.cloneLab()
        }
    }
    val installed = remember {
        runCatching { context.packageManager.getPackageInfo(TermuxSpec.PACKAGE, 0) }.isSuccess
    }
    val startClone = {
        if (TermuxSpec.isPermissionGranted(context)) {
            permGranted = true
            vm.cloneLab()
        } else {
            pendingClone = true
            permission.launch(TermuxSpec.PERMISSION)
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
            subtitle = "Клонирование, запуск и сборка в Termux"
        )
        Spacer(Modifier.height(12.dp))

        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    if (installed) "Termux установлен" else "Termux не найден",
                    if (installed) JbGreen else JbRed
                )
                Spacer(Modifier.weight(1f))
                Pill(
                    if (permGranted) "Разрешение есть" else "Нет разрешения",
                    if (permGranted) JbGreen else JbAmber
                )
            }
            if (!permGranted) {
                TextButton(onClick = { permission.launch(TermuxSpec.PERMISSION) }) {
                    Text("Разрешить RUN_COMMAND", color = JbAmber)
                }
            }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(GitHubLabSpec.setupScript(GitHubLabConfig())))
            }) {
                Text("Скопировать скрипт настройки", color = JbAccent2)
            }
            Text(
                "Если Termux не отвечает: откройте Termux, вставьте скрипт настройки, выполните его. " +
                    "Нужно allow-external-apps=true в ~/.termux/termux.properties.",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint
            )
        }
        Spacer(Modifier.height(10.dp))

        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
            Text("Репозиторий", style = MaterialTheme.typography.titleSmall, color = JbText)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = lab.repoUrl,
                onValueChange = vm::setLabRepo,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("https://github.com/owner/repo") }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = lab.ref,
                onValueChange = vm::setLabRef,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Ветка или тег (необязательно)") }
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = startClone,
                    enabled = !lab.busy,
                    modifier = Modifier.weight(1f)
                ) {
                    if (lab.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = JbText
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Клонировать и подготовить")
                }
                TextButton(onClick = vm::setupLab, enabled = !lab.busy) {
                    Text("Настроить Termux", color = JbAccent2)
                }
            }
            Spacer(Modifier.height(6.dp))
            LabToggle(
                title = "Устанавливать зависимости после клонирования",
                subtitle = "pip / npm / gradlew — выполняет сторонний код и обращается к сети",
                checked = lab.installDeps,
                onChange = vm::setLabInstallDeps
            )
            LabToggle(
                title = "Проверять GPG-подпись HEAD",
                subtitle = "git verify-commit после клонирования (если у коммита есть подпись)",
                checked = lab.verifyGpg,
                onChange = vm::setLabVerifyGpg
            )
        }

        if (project != null) {
            Spacer(Modifier.height(10.dp))
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Проект",
                        style = MaterialTheme.typography.titleSmall,
                        color = JbText,
                        modifier = Modifier.weight(1f)
                    )
                    Pill(project.type.title, JbGreen)
                }
                Spacer(Modifier.height(6.dp))
                LabField("URL", project.url)
                LabField("Ветка/тег", project.ref.ifBlank { "по умолчанию" })
                LabField("Каталог", project.dir)
                LabField("Hash", project.hash)
            }
        }

        if (project != null) {
            Spacer(Modifier.height(10.dp))
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 14) {
                Text(
                    "Поисковый модуль из репозитория",
                    style = MaterialTheme.typography.titleSmall,
                    color = JbText
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Осмотр только читает файлы репозитория и ищет HTTPS-адреса с {query}. " +
                        "Код репозитория не запускается; ключ и способ передачи задаёте вы сами.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = vm::inspectLabModule, enabled = !lab.busy) {
                    Text("Найти шаблон модуля", color = JbAccent2)
                }
                lab.moduleCandidates.forEach { candidate ->
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            candidate,
                            style = MaterialTheme.typography.labelSmall,
                            color = JbText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { vm.openLabModuleCandidate(candidate) }) {
                            Text("Настроить", color = JbAccent2)
                        }
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
                if (lab.busy) {
                    Pill("выполняется", JbAmber)
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = vm::resetLabBusy) {
                        Text("Сбросить", color = JbAmber)
                    }
                }
                TextButton(onClick = vm::clearLabLog) { Text("Очистить лог", color = JbMuted) }
            }
            if (lab.stage.isNotBlank()) {
                Text(lab.stage, style = MaterialTheme.typography.labelSmall, color = JbMuted)
                Spacer(Modifier.height(6.dp))
            }
            if (lab.log.isEmpty()) {
                Text(
                    "Логи появятся здесь. cd в папку проекта выполняется автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbFaint
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    lab.log.takeLast(120).forEach { line ->
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
            lab.note?.let { note ->
                Spacer(Modifier.height(8.dp))
                val problem = note.contains("не ", true) || note.contains("нет ", true) ||
                    note.contains("ошиб", true) || note.contains("Сначала", true)
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem) JbAmber else JbGreen
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = lab.command,
                onValueChange = vm::setLabCommand,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Команда в папке проекта") },
                placeholder = { Text("например: ./gradlew assembleDebug или python script.py") }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = vm::runLabCommand,
                    enabled = !lab.busy && project != null && lab.command.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = JbAccent)
                ) { Text("Выполнить") }
                TextButton(
                    onClick = vm::installLabDeps,
                    enabled = !lab.busy && project != null
                ) { Text("Зависимости", color = JbAccent2) }
            }
        }

        Spacer(Modifier.height(10.dp))
        GlowCard(modifier = Modifier.fillMaxWidth(), padding = 12) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = vm::clearLabEnv, enabled = !lab.busy && project != null) {
                    Text("Очистить среду", color = JbRed)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::exportLab, enabled = !lab.busy && project != null) {
                    Text("Экспорт → Download/JB-Osint/reports", color = JbAccent2)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Без root: всё выполняется от пользователя Termux. Рабочая папка " +
                "\$HOME/.jb_osint/lab/<hash> с правами 700. Код из репозитория не запускается при " +
                "открытии — только по кнопке «Выполнить». Таймаут 10 минут, sudo/su/doas запрещены. " +
                "Встроенные шаги ходят только на github.com; произвольные команды — ответственность пользователя.",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = JbText)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = JbFaint)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabField(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint,
            modifier = Modifier.width(84.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = JbText)
    }
}
