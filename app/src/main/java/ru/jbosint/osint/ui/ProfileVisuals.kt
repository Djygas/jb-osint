package ru.jbosint.osint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.jbosint.osint.profile.ContentMix
import ru.jbosint.osint.profile.GraphEdge
import ru.jbosint.osint.profile.GraphNode
import ru.jbosint.osint.profile.Heatmap
import ru.jbosint.osint.profile.WordWeight
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Граф связей: объект в центре, контакты по окружности, толщина линии = частота общения. */
@Composable
fun ConnectionGraph(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    modifier: Modifier = Modifier,
    onNodeClick: (GraphNode) -> Unit = {}
) {
    if (nodes.size <= 1) {
        DataStub("Связи не найдены: модули не вернули контактов объекта.")
        return
    }
    val center = nodes.firstOrNull { it.center } ?: nodes.first()
    val others = nodes.filter { it.id != center.id }
    val maxWeight = edges.maxOfOrNull { it.weight }?.coerceAtLeast(1) ?: 1
    Box(modifier = modifier.fillMaxWidth().height(300.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(300.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(size.width, size.height) / 2f - 34f
            val positions = LinkedHashMap<String, Offset>()
            positions[center.id] = Offset(cx, cy)
            others.forEachIndexed { index, node ->
                val angle = (2 * Math.PI * index / others.size) - Math.PI / 2
                positions[node.id] = Offset(
                    cx + (radius * cos(angle)).toFloat(),
                    cy + (radius * sin(angle)).toFloat()
                )
            }
            val labelPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 22f
                color = android.graphics.Color.parseColor("#C7CEE0")
                textAlign = android.graphics.Paint.Align.CENTER
            }
            edges.forEach { edge ->
                val from = positions[edge.from] ?: return@forEach
                val to = positions[edge.to] ?: return@forEach
                val strength = edge.weight.toFloat() / maxWeight
                drawLine(
                    color = if (edge.relation == "цитирование") JbAccent2.copy(alpha = 0.35f + strength * 0.5f)
                    else JbAccent.copy(alpha = 0.25f + strength * 0.45f),
                    start = from,
                    end = to,
                    strokeWidth = 1.2f + strength * 4f
                )
            }
            positions.forEach { (id, point) ->
                val node = nodes.first { it.id == id }
                val r = if (node.center) 24f else (7f + (node.weight.coerceAtMost(20) * 0.7f)).coerceAtMost(15f)
                if (node.center) {
                    drawCircle(JbAccent2.copy(alpha = 0.22f), r + 12f, point)
                    drawCircle(JbAccent2, r, point)
                    drawCircle(JbText.copy(alpha = 0.9f), r - 6f, point, style = Stroke(width = 2f))
                } else {
                    drawCircle(if (node.bot) JbAmber else JbAccent, r, point)
                }
                val label = node.label.take(14)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    point.x,
                    point.y + r + 22f,
                    labelPaint
                )
            }
        }
    }
}

/** Тепловая карта активности: 7 дней × 24 часа, цвет — плотность сообщений. */
@Composable
fun ActivityHeatmap(heat: Heatmap, modifier: Modifier = Modifier) {
    if (heat.max == 0) {
        DataStub("Нет выгрузки сообщений — построить карту активности не из чего.")
        return
    }
    val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            val labelWidth = 34f
            val cellW = (size.width - labelWidth) / 24f
            val cellH = (size.height - 18f) / 7f
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 20f
                color = android.graphics.Color.parseColor("#8A93A6")
            }
            for (day in 0..6) {
                drawContext.canvas.nativeCanvas.drawText(
                    days[day], 0f, 12f + day * cellH + cellH / 2f, paint
                )
                for (hour in 0..23) {
                    val value = heat.value(day, hour)
                    val intensity = if (heat.max == 0) 0f else value.toFloat() / heat.max
                    val color = when {
                        value == 0 -> JbBorderSoft
                        intensity > 0.66f -> JbAccent2
                        intensity > 0.33f -> JbAccent
                        else -> JbAccent.copy(alpha = 0.45f)
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(labelWidth + hour * cellW, day * cellH),
                        size = androidx.compose.ui.geometry.Size(cellW - 2f, cellH - 2f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
                    )
                }
            }
            for (hour in 0..23 step 6) {
                drawContext.canvas.nativeCanvas.drawText(
                    "${hour.toString().padStart(2, '0')}:00",
                    labelWidth + hour * cellW,
                    size.height - 2f,
                    paint
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniStat("Пик", if (heat.peakHour >= 0) "${heat.peakHour.toString().padStart(2, '0')}:00" else "—")
            MiniStat("Ночью", "${heat.nightShare}%")
            MiniStat("Средняя пауза", "${heat.avgGapMinutes} мин")
            MiniStat("Макс. пауза", "${heat.longestPauseHours} ч")
        }
    }
}

/** Облако слов: размер шрифта пропорционален частоте. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordCloud(words: List<WordWeight>, modifier: Modifier = Modifier) {
    if (words.isEmpty()) {
        DataStub("Слов не найдено: нет текстов сообщений или все слова — служебные.")
        return
    }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        words.forEach { word ->
            val size = (11 + word.share / 8f).coerceIn(11f, 23f)
            val color = when {
                word.share > 66 -> JbAccent2
                word.share > 33 -> JbAccent
                else -> JbSlate
            }
            Text(
                text = word.word,
                fontSize = size.sp,
                fontWeight = if (word.share > 50) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(JbSurfaceHigh.copy(alpha = 0.6f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

/** Полоса-доля типа контента. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MixBar(mix: ContentMix) {
    val parts = listOf(
        "текст" to mix.text to JbAccent,
        "медиа" to mix.media to JbAccent2,
        "реплаи" to mix.replies to JbGreen,
        "кружки" to mix.rounds to JbAmber,
        "голосовые" to mix.voice to Color(0xFF38BDF8),
        "ссылки" to mix.links to Color(0xFFF472B6)
    )
    val total = parts.sumOf { (it.first.second) }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            parts.forEach { (pair, color) ->
                val share = pair.second.toFloat() / total
                if (share > 0f) {
                    Box(
                        Modifier
                            .weight(share)
                            .height(12.dp)
                            .background(color)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            parts.filter { it.first.second > 0 }.forEach { (pair, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${pair.first} ${pair.second}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = JbMuted
                    )
                }
            }
        }
    }
}

/** Индикатор репутационного индекса. */
@Composable
fun ScoreBar(label: String, value: Int, color: Color, hint: String = "") {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = JbMuted, modifier = Modifier.weight(1f))
            Text("$value%", style = MaterialTheme.typography.titleSmall, color = color)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(JbBorderSoft)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0, 100) / 100f)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = JbFaint)
        }
    }
}

@Composable
fun MiniStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, color = JbText)
        Text(label, style = MaterialTheme.typography.labelSmall, color = JbFaint)
    }
}

/** Честная заглушка блока: данные не найдены. */
@Composable
fun DataStub(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JbSurfaceHigh.copy(alpha = 0.5f))
            .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = JbMuted
        )
    }
}

/** Мини-полоска доли внутри строки списка. */
@Composable
fun ShareRow(label: String, share: Int, count: Int, color: Color = JbAccent) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = JbText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(JbBorderSoft)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(share.coerceIn(0, 100) / 100f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("$count", style = MaterialTheme.typography.labelSmall, color = JbMuted)
    }
}

/** Круглая «аватарка» из инициалов. */
@Composable
fun AvatarBadge(title: String, size: Int = 64) {
    val initials = title.trim().split(' ', '_', '.')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(JbAccent.copy(alpha = 0.18f))
            .border(1.dp, JbAccent.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials,
            fontSize = (size / 2.6f).sp,
            fontWeight = FontWeight.Bold,
            color = JbAccent2
        )
    }
}

/** Кликабельная «таблетка» раздела. */
@Composable
fun SectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) JbAccent.copy(alpha = 0.22f) else JbSurfaceHigh)
            .border(
                1.dp,
                if (selected) JbAccent.copy(alpha = 0.7f) else JbBorderSoft,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) JbText else JbMuted
        )
    }
}

/** Процент как аккуратная подпись. */
fun percent(value: Int): String = "${value.coerceIn(0, 100)}%"

/** Округление для долей. */
fun share(count: Int, total: Int): Int = if (total <= 0) 0 else (count * 100.0 / total).roundToInt()
