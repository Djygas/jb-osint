package ru.jbosint.osint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.ui.AppViewModel

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var analyst by remember { mutableStateOf("") }
    Backdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            BrandMark(64)
            Spacer(Modifier.height(22.dp))
            Text(
                "JB OSINT",
                style = MaterialTheme.typography.displaySmall,
                color = JbText
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Единое рабочее место аналитика: сбор сведений из открытых источников, триангуляция и подготовка отчётов.",
                style = MaterialTheme.typography.bodyLarge,
                color = JbMuted
            )

            Spacer(Modifier.height(26.dp))
            GlowCard(modifier = Modifier.fillMaxWidth(), padding = 18) {
                FeatureRow(Icons.Filled.Hub, "Многофакторный поиск", "Почта, телефон, домен, IP, профили и организации — в одном запросе.")
                Spacer(Modifier.height(14.dp))
                FeatureRow(Icons.Filled.PictureAsPdf, "Отчёты PDF, Excel, JSON", "Готовый документ с оценкой достоверности и графом связей.")
                Spacer(Modifier.height(14.dp))
                FeatureRow(Icons.Filled.Lock, "Локальное хранение", "Ключи и история шифруются аппаратным хранилищем Android.")
            }

            Spacer(Modifier.height(24.dp))
            Text("ИМЯ АНАЛИТИКА", style = MaterialTheme.typography.labelSmall, color = JbFaint)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = analyst,
                onValueChange = { if (it.length <= 80) analyst = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Например, А. Смирнов", color = JbFaint) },
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Имя указывается на титульном листе отчёта. Можно изменить позже в настройках.",
                style = MaterialTheme.typography.bodySmall,
                color = JbFaint
            )

            Spacer(Modifier.height(26.dp))
            Button(
                onClick = { vm.completeOnboarding(analyst) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JbAccent)
            ) {
                Text("Начать работу", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Работа с персональными данными ведётся в рамках применимого законодательства и внутренних регламентов.",
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    Brush.linearGradient(
                        listOf(JbAccent.copy(alpha = 0.28f), JbAccent2.copy(alpha = 0.22f))
                    ),
                    RoundedCornerShape(12.dp)
                )
                .border(1.dp, JbAccent.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFFAEB6FF), modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = JbText)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = JbMuted)
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = JbText,
    unfocusedTextColor = JbText,
    disabledTextColor = JbFaint,
    cursorColor = JbAccent,
    focusedBorderColor = JbAccent,
    unfocusedBorderColor = JbBorder,
    focusedContainerColor = JbSurface,
    unfocusedContainerColor = JbSurface,
    disabledContainerColor = JbSurface,
    focusedPlaceholderColor = JbFaint,
    unfocusedPlaceholderColor = JbFaint
)
