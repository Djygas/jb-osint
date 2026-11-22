package ru.jbosint.osint.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.jbosint.osint.data.CustomAuth
import ru.jbosint.osint.data.CustomModule
import ru.jbosint.osint.data.CustomModuleSpec
import ru.jbosint.osint.data.RaeClient
import ru.jbosint.osint.data.RaeParser

/**
 * «Создать модуль из трафика» (Reverse API Engineer, локальный режим).
 * Запуск — только доверенный скрипт Termux; импорт — через SAF; код не исполняется.
 */
@Composable
fun RaeDialog(
    state: UiState,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onCreateModule: (CustomModule) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var parsed by remember { mutableStateOf<RaeClient?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val termux = state.termux
    var targetUrl by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    val canRunLocal = termux.enabled &&
        termux.trusted.contains("rae_manual.sh") &&
        termux.running == null
    val server = state.server
    val remote = state.raeServer

    LaunchedEffect(Unit) { vm.clearRaeServer() }

    fun toModule(client: RaeClient): CustomModule = CustomModule(
        id = "",
        title = client.host.take(CustomModuleSpec.MAX_TITLE),
        urlTemplate = client.urlTemplate(),
        auth = CustomAuth.AUTO,
        method = client.functions.firstOrNull()?.method ?: "GET",
        enabled = true,
        hasKey = false
    )

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            val outcome = runCatching {
                val text = withContext(Dispatchers.IO) { readText(context, uri) }
                RaeParser.parse(text) ?: error("В файле нет base_url — это не клиент RAE")
            }
            busy = false
            parsed = outcome.getOrNull()
            error = outcome.exceptionOrNull()?.message
        }
    }

    AlertDialog(
        onDismissRequest = {
            vm.clearRaeServer()
            onDismiss()
        },
        containerColor = JbSurfaceHigh,
        titleContentColor = JbText,
        textContentColor = JbMuted,
        title = { Text("Создать модуль из трафика") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Reverse API Engineer открывает браузер, записывает реальные запросы сайта и " +
                        "генерирует клиент api_client.py. Приложение разбирает клиент и предлагает " +
                        "готовый «Свой модуль» — без ручного копирования эндпоинтов.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(10.dp))
                Text("Серверный режим", style = MaterialTheme.typography.titleSmall, color = JbText)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Укажите адрес сайта и что собрать. Сервер запишет трафик, замаскирует PII " +
                        "и вернёт готовый клиент. Если сервер недоступен — работает локальный путь.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = targetUrl,
                    onValueChange = { targetUrl = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Адрес сайта, https", color = JbFaint) },
                    placeholder = { Text("https://example.com", color = JbFaint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(300) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Что собрать", color = JbFaint) },
                    placeholder = { Text("список вакансий: название, город, ссылка", color = JbFaint) }
                )
                TextButton(
                    onClick = { vm.raeGenerateRemote(targetUrl, prompt) },
                    enabled = server.enabled && !remote.busy
                ) {
                    Text(
                        when {
                            remote.busy -> "Генерируем… до 15 минут"
                            !server.enabled -> "Сервер выключен"
                            else -> "Сгенерировать на сервере"
                        },
                        color = if (server.enabled && !remote.busy) JbAccent2 else JbFaint,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (!server.enabled) {
                    Text(
                        "Включите сервер в «Настройках → Серверный ускоритель».",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbAmber
                    )
                }
                remote.error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = JbRed)
                }
                remote.result?.let { result ->
                    Spacer(Modifier.height(6.dp))
                    MetaRow("script_path", result.scriptPath.takeLast(70))
                    if (result.usage.isNotBlank()) MetaRow("usage", result.usage)
                    if (result.masked.isNotBlank()) MetaRow("NDA", result.masked)
                    result.client.functions.take(4).forEach { function ->
                        MetaRow(
                            "${function.method} ${function.path.ifBlank { "—" }}",
                            function.name
                        )
                    }
                    TextButton(onClick = { onCreateModule(toModule(result.client)) }) {
                        Text(
                            "Открыть в «Своих модулях»",
                            color = JbAccent2,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Локально (Termux)", style = MaterialTheme.typography.titleSmall, color = JbText)
                Spacer(Modifier.height(4.dp))
                Text(
                    "1. Установите окружение скриптом настройки в «Настройках».\n" +
                        "2. Запустите RAE — откроется браузер. Выполните действия на сайте и закройте браузер.\n" +
                        "3. RAE сгенерирует api_client.py и скопирует его в общую папку.\n" +
                        "4. Импортируйте файл кнопкой ниже.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { vm.runTermuxScript("rae_manual.sh") },
                        enabled = canRunLocal
                    ) {
                        Text(
                            when {
                                !termux.enabled -> "Termux выключен"
                                termux.running == "rae_manual.sh" -> "RAE запущен…"
                                else -> "Запустить RAE"
                            },
                            color = if (canRunLocal) JbAccent2 else JbFaint,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    TextButton(onClick = { picker.launch(arrayOf("text/x-python", "text/plain", "*/*")) }, enabled = !busy) {
                        Text(
                            if (busy) "Читаем…" else "Импортировать api_client.py",
                            color = if (busy) JbFaint else JbAccent2,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                if (!termux.enabled) {
                    Text(
                        "Termux-мост выключен: включите его в «Настройках → Локальный бэкенд».",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbAmber
                    )
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = JbRed)
                }

                parsed?.let { client ->
                    Spacer(Modifier.height(12.dp))
                    Text("Найдено в клиенте", style = MaterialTheme.typography.titleSmall, color = JbText)
                    Spacer(Modifier.height(4.dp))
                    MetaRow("base_url", client.baseUrl)
                    MetaRow("Функций", client.functions.size.toString())
                    client.headers.entries.take(6).forEach { (name, value) ->
                        MetaRow(name, value)
                    }
                    client.functions.take(6).forEach { function ->
                        MetaRow(
                            "${function.method} ${function.path.ifBlank { "—" }}",
                            function.params.joinToString(", ").ifBlank { function.name }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onCreateModule(toModule(client)) }) {
                        Text("Открыть в «Своих модулях»", color = JbAccent2, style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Внимание: код сгенерирован ИИ. Проверьте скрипт на наличие вредоносных команд " +
                        "(rm -rf, запросы на подозрительные домены) перед сохранением. Приложение " +
                        "разбирает только метаданные и не исполняет сгенерированный код.",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbAmber
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = JbMuted) }
        }
    )
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = JbSlate
        )
    }
}

/** Чтение выбранного через SAF файла с ограничением размера. */
private fun readText(context: Context, uri: Uri): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: error("Не удалось открыть файл")
    return stream.use { input ->
        val buffer = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > 600_000) break
            out.write(buffer, 0, read)
        }
        out.toString(Charsets.UTF_8.name())
    }
}
