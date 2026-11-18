package ru.jbosint.osint.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ru.jbosint.osint.data.KeyBackupService
import ru.jbosint.osint.data.VaultCrypto
import ru.jbosint.osint.data.WorkMode

/** Действие, для которого запрашивается пароль. */
internal sealed interface PasswordAction {
    data object Create : PasswordAction
    data object Change : PasswordAction
    data object Unlock : PasswordAction
    data object Export : PasswordAction
    data object Build : PasswordAction
    data class Import(val uri: Uri) : PasswordAction
}

/**
 * Раздел «Режим работы»: администратор или пользователь, мастер-пароль,
 * резервный код, вход по биометрии.
 */
@Composable
fun AccessSection(state: UiState, vm: AppViewModel) {
    var action by remember { mutableStateOf<PasswordAction?>(null) }
    var shownCode by remember { mutableStateOf<String?>(null) }
    var showCodeConfirm by remember { mutableStateOf(false) }
    val biometric = biometricAvailable()

    SectionHeader("Режим работы")
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Text(
            "Режим: ${WorkMode.of(state.workMode).title}",
            style = MaterialTheme.typography.titleSmall,
            color = JbText
        )
        Spacer(Modifier.height(3.dp))
        Text(WorkMode.of(state.workMode).note, style = MaterialTheme.typography.bodySmall, color = JbMuted)
        Spacer(Modifier.height(12.dp))

        if (state.userMode) {
            ActionRow(
                icon = Icons.Filled.AdminPanelSettings,
                title = "Войти как администратор",
                subtitle = if (state.adminHasPassword) "Нужен мастер-пароль"
                else "Пароль ещё не задан — создайте его при входе",
                onClick = {
                    action = if (state.adminHasPassword) PasswordAction.Unlock else PasswordAction.Create
                }
            )
            if (state.adminHasPassword && state.adminBiometric && biometric) {
                Spacer(Modifier.height(8.dp))
                BiometricRow(onUnlocked = { vm.adminUnlockTrusted() })
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Администратор", Icons.Filled.AdminPanelSettings, selected = true, modifier = Modifier.weight(1f)) { }
                ModeChip("Пользователь", Icons.Filled.Person, selected = false, modifier = Modifier.weight(1f)) {
                    vm.switchMode(WorkMode.USER, null)
                }
            }
            Spacer(Modifier.height(12.dp))
            GradientDivider()
            Spacer(Modifier.height(10.dp))
            ActionRow(
                icon = Icons.Filled.Key,
                title = if (state.adminHasPassword) "Сменить мастер-пароль" else "Установить мастер-пароль",
                subtitle = if (state.adminHasPassword)
                    "Хранится только PBKDF2-хеш; старый пароль понадобится для смены"
                else "Минимум ${VaultCrypto.MIN_PASSWORD} символов — защита настроек и сборок",
                onClick = { action = if (state.adminHasPassword) PasswordAction.Change else PasswordAction.Create }
            )
            if (state.adminHasPassword) {
                Spacer(Modifier.height(10.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                ActionRow(
                    icon = Icons.Filled.Restore,
                    title = "Резервный код",
                    subtitle = if (state.adminRecovery) "Код создан: им можно сбросить пароль"
                    else "Одноразовый код на случай забытого пароля",
                    onClick = { showCodeConfirm = true }
                )
                Spacer(Modifier.height(10.dp))
                GradientDivider()
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = null,
                        tint = if (biometric) JbAccent2 else JbFaint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Вход по биометрии", style = MaterialTheme.typography.titleSmall, color = JbText)
                        Text(
                            if (biometric) "Отпечаток или лицо вместо пароля при разблокировке"
                            else "На этом устройстве биометрия недоступна",
                            style = MaterialTheme.typography.labelSmall,
                            color = JbMuted
                        )
                    }
                    Switch(
                        checked = state.adminBiometric && biometric,
                        enabled = biometric,
                        onCheckedChange = { vm.setAdminBiometric(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = JbAccent,
                            uncheckedThumbColor = JbMuted,
                            uncheckedTrackColor = JbSurfaceHigh,
                            uncheckedBorderColor = JbBorder
                        )
                    )
                }
                if (state.adminUnlocked) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { vm.adminLock() }) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = JbMuted, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Заблокировать доступ администратора",
                            color = JbMuted,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }

    action?.let { current ->
        PasswordDialog(
            action = current,
            hasPassword = state.adminHasPassword,
            onDismiss = { action = null },
            onSubmit = { currentPassword, password ->
                when (current) {
                    PasswordAction.Create -> {
                        val error = vm.adminSetPassword(password)
                        if (error == null && state.userMode) vm.switchMode(WorkMode.ADMIN, password)
                        error
                    }
                    PasswordAction.Change -> vm.adminSetPassword(password, currentPassword)
                    PasswordAction.Unlock -> {
                        val error = vm.adminUnlock(password)
                        if (error == null && state.userMode) vm.switchMode(WorkMode.ADMIN, password)
                        error
                    }
                    PasswordAction.Export -> { vm.exportKeys(password); null }
                    PasswordAction.Build -> { vm.createUserBuild(password); null }
                    is PasswordAction.Import -> vm.importKeysFromUri(current.uri, password)
                }
            }
        )
    }

    if (showCodeConfirm) {
        ConfirmDialog(
            title = "Новый резервный код",
            text = "Прежний код перестанет действовать. Новый покажем один раз — сохраните его в надёжном месте.",
            confirm = "Создать код",
            onDismiss = { showCodeConfirm = false },
            onConfirm = {
                showCodeConfirm = false
                shownCode = vm.generateRecoveryCode()
            }
        )
    }

    shownCode?.let { code ->
        AlertDialog(
            onDismissRequest = { shownCode = null },
            containerColor = JbSurface,
            title = { Text("Резервный код", color = JbText) },
            text = {
                Column {
                    Text(
                        "Запишите код: он показывается один раз. С его помощью можно сбросить мастер-пароль.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(JbSurfaceHigh)
                            .border(1.dp, JbBorder, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text(code, style = MaterialTheme.typography.titleMedium, color = JbText, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shownCode = null }) { Text("Я сохранил код", color = JbAccent2) }
            }
        )
    }
}

/** Раздел «Резервное копирование»: экспорт, импорт и пользовательская сборка. */
@Composable
fun BackupSection(state: UiState, vm: AppViewModel) {
    var action by remember { mutableStateOf<PasswordAction?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action = PasswordAction.Import(uri)
    }

    SectionHeader("Резервное копирование")
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Text(
            "Ключи живут только на устройстве: перед удалением приложения сделайте копию.",
            style = MaterialTheme.typography.bodySmall,
            color = JbMuted
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Файл шифруется паролем (PBKDF2 → AES-256-GCM); в открытом виде ключи не сохраняются.",
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
        if (state.storedKeys == 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Сейчас ключей нет — экспортировать нечего.",
                style = MaterialTheme.typography.labelSmall,
                color = JbAmber
            )
        }
        Spacer(Modifier.height(12.dp))
        ActionRow(
            icon = Icons.Filled.Save,
            title = "Экспортировать ключи",
            subtitle = "Файл ${KeyBackupService.KEYS_FILE} со всеми сохранёнными ключами",
            onClick = { action = PasswordAction.Export }
        )
        Spacer(Modifier.height(10.dp))
        GradientDivider()
        Spacer(Modifier.height(10.dp))
        ActionRow(
            icon = Icons.Filled.Upload,
            title = "Импортировать ключи",
            subtitle = "Выбрать файл копии и ввести пароль",
            onClick = { picker.launch(arrayOf("*/*")) }
        )
        Spacer(Modifier.height(10.dp))
        GradientDivider()
        Spacer(Modifier.height(10.dp))
        ActionRow(
            icon = Icons.Filled.Inventory2,
            title = "Создать пользовательскую сборку",
            subtitle = "Только включённые модули; после импорта приложение работает в режиме «Пользователь»",
            onClick = { action = PasswordAction.Build }
        )
        state.backupNote?.let { note ->
            Spacer(Modifier.height(10.dp))
            Text(note, style = MaterialTheme.typography.labelSmall, color = JbFaint)
        }
        if (state.backupBusy) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 1.8.dp, color = JbAccent2)
                Spacer(Modifier.width(8.dp))
                Text("Готовим файл…", style = MaterialTheme.typography.labelSmall, color = JbMuted)
            }
        }
    }

    action?.let { current ->
        PasswordDialog(
            action = current,
            hasPassword = state.adminHasPassword,
            onDismiss = { action = null },
            onSubmit = { _, password ->
                when (current) {
                    PasswordAction.Export -> { vm.exportKeys(password); null }
                    PasswordAction.Build -> { vm.createUserBuild(password); null }
                    is PasswordAction.Import -> vm.importKeysFromUri(current.uri, password)
                    else -> null
                }
            }
        )
    }
}

/** Предложение восстановить найденную на устройстве копию при запуске. */
@Composable
fun RestoreOfferDialog(state: UiState, vm: AppViewModel) {
    val offer = state.restoreOffer ?: return
    var password by remember(offer.path) { mutableStateOf("") }
    var error by remember(offer.path) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { vm.dismissRestoreOffer() },
        containerColor = JbSurface,
        title = {
            Text(
                if (offer.config) "Найдена пользовательская сборка" else "Найдена резервная копия ключей",
                color = JbText
            )
        },
        text = {
            Column {
                Text(
                    "Файл ${offer.name} уже есть на устройстве. Введите пароль, который задавали при экспорте, " +
                        "чтобы восстановить ключи.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Пароль", color = JbFaint) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = JbRed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = vm.restoreFromOffer(password) }) {
                Text("Восстановить", color = JbAccent2)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissRestoreOffer() }) { Text("Не сейчас", color = JbMuted) }
        }
    )
}

/** Компактная плашка «Режим пользователя» с быстрым входом администратора. */
@Composable
fun UserModeBadge(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    if (!state.userMode) return
    var dialog by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(JbSurfaceHigh)
            .border(1.dp, JbBorderSoft, RoundedCornerShape(12.dp))
            .clickable { dialog = true }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = JbAccent2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Режим пользователя", style = MaterialTheme.typography.labelSmall, color = JbMuted)
            Spacer(Modifier.width(8.dp))
            Text("Войти", style = MaterialTheme.typography.labelSmall, color = JbAccent2)
        }
    }
    if (dialog) {
        PasswordDialog(
            action = PasswordAction.Unlock,
            hasPassword = state.adminHasPassword,
            onDismiss = { dialog = false },
            onSubmit = { _, password ->
                val error = vm.adminUnlock(password)
                if (error == null) {
                    val switch = vm.switchMode(WorkMode.ADMIN, password)
                    if (switch == null) dialog = false
                    switch
                } else error
            }
        )
    }
}

/** Универсальный диалог пароля: возвращает текст ошибки, чтобы не закрываться при промахе. */
@Composable
internal fun PasswordDialog(
    action: PasswordAction,
    hasPassword: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (current: String, password: String) -> String?
) {
    val spec = when (action) {
        PasswordAction.Create -> DialogSpec(
            title = "Мастер-пароль",
            subtitle = "Минимум ${VaultCrypto.MIN_PASSWORD} символов. Пароль не хранится — только PBKDF2-хеш.",
            confirm = "Сохранить",
            repeat = true
        )
        PasswordAction.Change -> DialogSpec(
            title = "Смена мастер-пароля",
            subtitle = "Сначала текущий пароль, затем новый.",
            confirm = "Сменить",
            current = true,
            repeat = true
        )
        PasswordAction.Unlock -> DialogSpec(
            title = "Вход администратора",
            subtitle = if (hasPassword) "Введите мастер-пароль." else "Пароль не задан — вход свободный.",
            confirm = "Войти"
        )
        PasswordAction.Export -> DialogSpec(
            title = "Мастер-пароль",
            subtitle = "Подтвердите мастер-пароль: им же будет зашифрован файл копии.",
            confirm = "Экспортировать"
        )
        PasswordAction.Build -> DialogSpec(
            title = "Мастер-пароль",
            subtitle = "Подтвердите мастер-пароль: получатель введёт его при первом запуске сборки.",
            confirm = "Создать сборку"
        )
        is PasswordAction.Import -> DialogSpec(
            title = "Импорт ключей",
            subtitle = "Введите мастер-пароль, которым зашифрован файл.",
            confirm = "Импортировать"
        )
    }

    var current by remember(action) { mutableStateOf("") }
    var password by remember(action) { mutableStateOf("") }
    var repeat by remember(action) { mutableStateOf("") }
    var error by remember(action) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JbSurface,
        title = { Text(spec.title, color = JbText) },
        text = {
            Column {
                Text(spec.subtitle, style = MaterialTheme.typography.bodySmall, color = JbMuted)
                Spacer(Modifier.height(12.dp))
                if (spec.current) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(spec.currentLabel, color = JbFaint) },
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (spec.current) "Новый пароль" else "Пароль", color = JbFaint) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors()
                )
                if (spec.repeat) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Повторите пароль", color = JbFaint) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors()
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = JbRed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = if (spec.repeat && password != repeat) "Пароли не совпадают" else onSubmit(current, password)
                if (problem == null) onDismiss() else error = problem
            }) { Text(spec.confirm, color = JbAccent2) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = JbMuted) }
        }
    )
}

private data class DialogSpec(
    val title: String,
    val subtitle: String,
    val confirm: String,
    val current: Boolean = false,
    val currentLabel: String = "Текущий пароль",
    val repeat: Boolean = false
)

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JbSurface,
        title = { Text(title, color = JbText) },
        text = { Text(text, style = MaterialTheme.typography.bodySmall, color = JbMuted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = JbAccent2) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = JbMuted) } }
    )
}

@Composable
private fun ModeChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) JbAccent.copy(alpha = 0.18f) else JbSurfaceHigh)
            .border(1.dp, if (selected) JbAccent else JbBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (selected) JbAccent2 else JbMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = if (selected) JbText else JbMuted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BiometricRow(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    ActionRow(
        icon = Icons.Filled.Fingerprint,
        title = "Разблокировать по биометрии",
        subtitle = "Отпечаток или лицо вместо мастер-пароля",
        onClick = {
            val activity = context as? FragmentActivity ?: return@ActionRow
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onUnlocked()
                    }
                }
            )
            runCatching {
                prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Вход администратора")
                        .setSubtitle("Подтвердите личность")
                        .setNegativeButtonText("Отмена")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        .build()
                )
            }
        }
    )
}

@Composable
private fun biometricAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }
}
