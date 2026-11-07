package ru.jbosint.osint.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

@Composable
fun AppRoot(state: UiState, vm: AppViewModel) {
    if (!state.onboarding) {
        OnboardingScreen(vm)
        return
    }

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // FLAG_SECURE: запрет скриншотов и превью в списке задач.
    LaunchedEffect(state.secureScreen) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        if (state.secureScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(Unit) {
        vm.shareEvents.collectLatest { request ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = request.mime
                putExtra(Intent.EXTRA_STREAM, request.uri)
                putExtra(Intent.EXTRA_SUBJECT, request.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Отправить отчёт").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(chooser) }
        }
    }

    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.consumeToast()
    }

    BackHandler(enabled = state.screen != Screen.HOME) {
        if (state.screen == Screen.RESULTS) vm.backHome() else vm.navigate(Screen.HOME)
    }

    Box(modifier = Modifier.fillMaxSize().background(backdropBrush())) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { data ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(JbSurfaceHigh)
                            .border(1.dp, JbBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            data.visuals.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = JbText
                        )
                    }
                }
            },
            bottomBar = {
                if (state.screen != Screen.RESULTS) {
                    JbBottomBar(current = state.screen, onNavigate = vm::navigate)
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .statusBarsPadding()
            ) {
                when (state.screen) {
                    Screen.HOME -> HomeScreen(state, vm)
                    Screen.RESULTS -> ResultsScreen(state, vm)
                    Screen.KEYS -> KeysScreen(state, vm)
                    Screen.SETTINGS -> SettingsScreen(state, vm)
                }
            }
        }
    }
}

@Composable
private fun JbBottomBar(current: Screen, onNavigate: (Screen) -> Unit) {
    val items = listOf(
        Triple(Screen.HOME, "Поиск", Icons.Filled.Search),
        Triple(Screen.KEYS, "Ключи", Icons.Filled.VpnKey),
        Triple(Screen.SETTINGS, "Настройки", Icons.Filled.Settings)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF20B0E14))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(listOf(Color.Transparent, JbBorder, Color.Transparent))
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (screen, label, icon) ->
                BottomItem(
                    screen = screen,
                    label = label,
                    icon = icon,
                    selected = current == screen,
                    onClick = { onNavigate(screen) }
                )
            }
        }
    }
}

@Composable
private fun BottomItem(
    screen: Screen,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) JbAccent2 else JbFaint,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) JbAccent2 else JbFaint
        )
    }
}
