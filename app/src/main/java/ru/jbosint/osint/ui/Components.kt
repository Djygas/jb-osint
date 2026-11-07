package ru.jbosint.osint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.data.KeyState

/** Фоновый градиент всех экранов. */
fun backdropBrush(): Brush = Brush.verticalGradient(
    listOf(Color(0xFF10162A), JbBg, JbBg)
)

@Composable
fun Backdrop(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdropBrush())
    ) {
        content()
    }
}

/** Стеклянная карточка с мягкой обводкой. */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    padding: Int = 18,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var base = modifier
        .clip(RoundedCornerShape(20.dp))
        .background(
            Brush.linearGradient(listOf(Color(0xFF161D2B), Color(0xFF11161F)))
        )
        .border(1.dp, JbBorder, RoundedCornerShape(20.dp))
    if (onClick != null) base = base.clickable { onClick() }
    Column(modifier = base.padding(padding.dp), content = content)
}

@Composable
fun ScreenHeader(eyebrow: String, title: String, subtitle: String? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = eyebrow.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = JbAccent2
        )
        Spacer(Modifier.height(6.dp))
        Text(text = title, style = MaterialTheme.typography.headlineLarge, color = JbText)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = JbMuted)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = JbFaint,
        modifier = modifier.padding(top = 4.dp, bottom = 8.dp)
    )
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = JbText
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(JbSurfaceHigh)
            .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = accent, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = JbMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun confidenceColor(confidence: Confidence): Color = when (confidence) {
    Confidence.CONFIRMED -> JbGreen
    Confidence.PROBABLE -> JbAmber
    Confidence.UNVERIFIED -> JbSlate
}

@Composable
fun ConfidencePill(confidence: Confidence, compact: Boolean = false) {
    val color = confidenceColor(confidence)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = 3.dp)
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (compact) confidence.title else confidence.title,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

fun keyStateColor(state: KeyState): Color = when (state) {
    KeyState.VALID -> JbGreen
    KeyState.INVALID -> JbRed
    KeyState.OFFLINE -> JbAmber
    KeyState.CHECKING -> JbAccent
    KeyState.EMPTY, KeyState.UNCHECKED -> JbSlate
}

@Composable
fun StatePill(state: KeyState) {
    val color = keyStateColor(state)
    Text(
        text = state.title,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
fun Pill(text: String, color: Color = JbMuted, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = JbFaint,
            modifier = Modifier.width(112.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = JbMuted,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Небольшой разделитель-градиент. */
@Composable
fun GradientDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, JbBorder, Color.Transparent)
                )
            )
    )
}

/** Логотип-гексагон с инициалами. */
@Composable
fun BrandMark(size: Int = 64) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.3f).dp))
            .background(Brush.linearGradient(listOf(JbAccent, JbAccent2))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "JB",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
fun EmptyHint(icon: ImageVector, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = JbFaint, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = JbText)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = JbMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun RowSpaceBetween(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
