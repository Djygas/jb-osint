package ru.jbosint.osint.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.data.KeyState
import ru.jbosint.osint.data.ProviderGroup

@Composable
fun KeysScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    ScreenHeader(
                        eyebrow = "Доступ",
                        title = "API-ключи",
                        subtitle = "Подключите сервисы и включите нужные модули. Ключи хранятся только на устройстве."
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(JbSurface)
                    .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = JbGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Шифрование AES-256/GCM, мастер-ключ в Android Keystore",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.checkAll() }) {
                    Text("Проверить все", color = JbAccent2, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        ProviderGroup.entries.forEach { group ->
            val groupProviders = state.providers.filter { it.spec.group == group }
            if (groupProviders.isNotEmpty()) {
                item(key = "group_${group.name}") {
                    Text(
                        group.title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = JbFaint,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }
                items(groupProviders, key = { it.spec.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        onToggle = { enabled -> vm.toggleProvider(provider.spec.id, enabled) },
                        onSave = { value -> vm.saveKey(provider.spec.id, value) },
                        onSaveSecond = { value -> vm.saveSecondField(provider.spec.id, value) },
                        onClear = { vm.clearKey(provider.spec.id) },
                        onCheck = { vm.checkKey(provider.spec.id) },
                        onOpenDocs = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.spec.docsUrl)))
                            }
                        }
                    )
                }
            }
        }

        item {
            Text(
                "Подключение модуля не раскрывает его название в отчётах: в документах указывается только марка «Поиск JB OSINT».",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderUi,
    onToggle: (Boolean) -> Unit,
    onSave: (String) -> Unit,
    onSaveSecond: (String) -> Unit,
    onClear: () -> Unit,
    onCheck: () -> Unit,
    onOpenDocs: () -> Unit
) {
    var keyText by rememberSaveable(provider.spec.id, provider.hasKey) { mutableStateOf("") }
    var secondText by rememberSaveable(provider.spec.id + "_2", provider.hasSecond) { mutableStateOf("") }
    var revealed by rememberSaveable(provider.spec.id + "_rev") { mutableStateOf(false) }
    val secondLabel = provider.spec.requiresSecondField

    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(provider.spec.title, style = MaterialTheme.typography.titleMedium, color = JbText)
                Spacer(Modifier.height(2.dp))
                Text(provider.spec.subtitle, style = MaterialTheme.typography.bodySmall, color = JbMuted)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = provider.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = JbAccent,
                    uncheckedThumbColor = JbMuted,
                    uncheckedTrackColor = JbSurfaceHigh,
                    uncheckedBorderColor = JbBorder
                )
            )
        }

        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatePill(if (provider.checking) KeyState.CHECKING else provider.state)
            Spacer(Modifier.width(8.dp))
            if (provider.hasKey) {
                Text(
                    if (provider.checkedAt > 0) "проверен ${dateShort(provider.checkedAt)}" else "ключ сохранён",
                    style = MaterialTheme.typography.labelSmall,
                    color = JbFaint
                )
            } else {
                Text("ключ не задан", style = MaterialTheme.typography.labelSmall, color = JbFaint)
            }
            Spacer(Modifier.weight(1f))
            if (provider.checking) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = JbAccent, strokeWidth = 1.6.dp)
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text(
                    if (provider.hasKey) "Ключ сохранён — введите новый, чтобы заменить"
                    else provider.spec.hint,
                    color = JbFaint,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (revealed) "Скрыть" else "Показать",
                        tint = JbFaint
                    )
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
            textStyle = MaterialTheme.typography.bodyMedium
        )

        if (secondLabel != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = secondText,
                onValueChange = { secondText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        if (provider.hasSecond) "$secondLabel сохранён"
                        else "Дополнительно: $secondLabel",
                        color = JbFaint,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenDocs) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = JbMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text("Где взять ключ", style = MaterialTheme.typography.labelMedium, color = JbMuted)
            }
            Spacer(Modifier.weight(1f))
            if (provider.hasKey) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить ключ", tint = JbFaint, modifier = Modifier.size(18.dp))
                }
            }
            TextButton(
                onClick = onCheck,
                enabled = provider.hasKey && !provider.checking
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = if (provider.hasKey) JbAccent2 else JbFaint,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "Проверить",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (provider.hasKey) JbAccent2 else JbFaint
                )
            }
            if (keyText.isNotBlank() || (secondLabel != null && secondText.isNotBlank())) {
                TextButton(onClick = {
                    if (keyText.isNotBlank()) {
                        onSave(keyText)
                        keyText = ""
                    }
                    if (secondText.isNotBlank()) {
                        onSaveSecond(secondText)
                        secondText = ""
                    }
                }) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JbGreen, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Сохранить", style = MaterialTheme.typography.labelMedium, color = JbGreen)
                }
            }
        }
    }
}

private fun dateShort(timestamp: Long): String =
    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru")).format(java.util.Date(timestamp))
