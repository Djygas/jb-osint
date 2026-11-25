package ru.jbosint.osint.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.jbosint.osint.data.InstalledPlugin
import ru.jbosint.osint.data.PluginInstaller
import ru.jbosint.osint.data.PluginModule
import ru.jbosint.osint.data.PluginStore

/**
 * Маркетплейс модулей. Подключение = скачивание архива и разбор скриптов.
 * Запуск скрипта не происходит: у каждого модуля видна команда, тумблер только
 * помечает модуль включённым, а исполнение идёт отдельным подтверждённым шагом.
 */
@Composable
fun PluginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PluginStore(context) }
    var plugins by remember { mutableStateOf(store.load()) }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
        }
        ScreenHeader(
            eyebrow = "Расширения",
            title = "Маркетплейс модулей",
            subtitle = "Скачиваем архив репозитория, берём из него только скрипты и заводим модули. " +
                "Код не запускается: команду видно, запуск — отдельным подтверждением."
        )
        Spacer(Modifier.height(12.dp))

        GlowCard {
            Text("Подключить по ссылке", fontWeight = FontWeight.SemiBold, color = JbText)
            Spacer(Modifier.height(6.dp))
            Text(
                "Только https://github.com/owner/repo. Из архива извлекаются .py, .sh и .js — " +
                    "не больше 40 МБ, без бинарников.",
                color = JbMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://github.com/owner/repo", color = JbFaint, fontSize = 13.sp) },
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !busy && link.isNotBlank(),
                    onClick = {
                        error = null
                        busy = true
                        stage = "Подготовка"
                        scope.launch {
                            val result = PluginInstaller(context).installFromGit(link) { text, percent ->
                                main.post { stage = "$text · $percent%" }
                            }
                            main.post {
                                busy = false
                                result.onSuccess {
                                    plugins = store.load()
                                    link = ""
                                    stage = "Подключено: ${it.title}"
                                }.onFailure {
                                    error = it.message ?: "Не удалось подключить"
                                    stage = ""
                                }
                            }
                        }
                    }
                ) { Text(if (busy) "Подключаем…" else "Подключить") }
                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterVertically).weight(1f),
                        color = JbAccent
                    )
                }
            }
            if (stage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(stage, color = JbMuted, fontSize = 12.sp)
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = JbRed, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionHeader("Подключённые репозитории")
        if (plugins.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Пока ничего не подключено. Вставьте ссылку — модули появятся здесь с тумблерами.",
                color = JbMuted, fontSize = 13.sp
            )
        }
        plugins.forEach { plugin ->
            Spacer(Modifier.height(12.dp))
            PluginCard(
                plugin = plugin,
                onToggle = { module, enabled ->
                    plugins = store.toggle(plugin.slug, module.id, enabled)
                },
                onAll = { enabled ->
                    plugins = store.setEnabled(plugin.slug, emptySet(), enabled)
                },
                onRemove = {
                    store.remove(plugin.slug)
                    plugins = store.load()
                }
            )
        }

        Spacer(Modifier.height(18.dp))
        GlowCard {
            Text("Чего здесь нет", fontWeight = FontWeight.SemiBold, color = JbText)
            Spacer(Modifier.height(6.dp))
            Text(
                "Автообновления и тихого запуска. Обновление — повторное «Подключить» по той же " +
                    "ссылке с показом нового списка модулей. Скрипты запускаются только в Termux, " +
                    "по видимой вам команде, с таймаутом 60 секунд.",
                color = JbMuted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun PluginCard(
    plugin: InstalledPlugin,
    onToggle: (PluginModule, Boolean) -> Unit,
    onAll: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    GlowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(plugin.title, fontWeight = FontWeight.SemiBold, color = JbText)
                Text(plugin.source, color = JbFaint, fontSize = 11.sp)
                Text("${plugin.modules.size} модулей", color = JbMuted, fontSize = 12.sp)
            }
            TextButton(onClick = { onAll(true) }) { Text("Все", fontSize = 12.sp) }
            TextButton(onClick = onRemove) { Text("Удалить", color = JbRed, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(6.dp))
        plugin.modules.forEach { module ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(module.title, color = JbText, fontSize = 14.sp)
                    Text(
                        "${module.input.title} · ${module.parser.title}",
                        color = JbMuted, fontSize = 11.sp
                    )
                    Text(module.command, color = JbFaint, fontSize = 11.sp)
                    if (!module.known) {
                        Text(
                            "Шаблон не найден — проверьте команду перед запуском",
                            color = JbAmber, fontSize = 11.sp
                        )
                    }
                }
                Switch(checked = module.enabled, onCheckedChange = { onToggle(module, it) })
            }
        }
    }
}

/** Короткая карточка-вход в маркетплейс для экрана настроек. */
@Composable
fun PluginSection(onOpen: () -> Unit) {
    GlowCard(onClick = onOpen) {
        Text("Маркетплейс модулей", fontWeight = FontWeight.SemiBold, color = JbText)
        Spacer(Modifier.height(4.dp))
        Text(
            "Подключить внешний репозиторий со скриптами: скачивание, разбор и модули с тумблерами.",
            color = JbMuted, fontSize = 12.sp
        )
    }
}
