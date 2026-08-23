package app.paisa.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import app.paisa.core.Paise

/** A titled card, optionally with an action on the right of its heading. */
@Composable
fun SectionCard(
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null || action != null) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.orEmpty().uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    action?.invoke()
                }
            }
            content()
        }
    }
}

/** One of the three small figures across the top of a screen. */
@Composable
fun StatTile(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A standard list row: coloured dot, title and subtitle, amount on the right. */
@Composable
fun EntryRow(
    dotColor: Color,
    glyph: String,
    title: String,
    subtitle: String,
    amount: String,
    amountColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(dotColor),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(amount, style = MaterialTheme.typography.titleMedium, color = amountColor, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** The daily earning ring on the Today screen. */
@Composable
fun ProgressRing(
    fraction: Float,
    size: Int = 168,
    stroke: Int = 14,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size.dp)) {
            val strokeWidth = stroke.dp.toPx()
            val inset = strokeWidth / 2
            val diameter = this.size.minDimension - strokeWidth
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            if (fraction > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
        content()
    }
}

/** A share-of-total bar, used under category names and card limits. */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = StrokeCap.Round
    )
}

@Composable
fun EmptyState(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** A small pill of supporting text, e.g. "auto" or "3 days overdue". */
@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** Money shown large, as on the Today hero. */
@Composable
fun BigMoney(paise: Paise, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(Fmt.money(paise), style = MaterialTheme.typography.displaySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Earnings against spending, one pair of bars per day. This is the first thing
 * on the dashboard because it answers the only question that matters daily:
 * did more come in than went out?
 */
@Composable
fun DailyBars(
    series: List<app.paisa.core.DayTotals>,
    incomeColor: Color,
    expenseColor: Color,
    height: Int = 150
) {
    if (series.isEmpty()) return
    val peak = app.paisa.core.DailySeries.peak(series).coerceAtLeast(1L)
    val gridColor = MaterialTheme.colorScheme.outline
    val todayMark = MaterialTheme.colorScheme.primary

    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        val slotWidth = size.width / series.size
        val barWidth = (slotWidth * 0.32f).coerceAtMost(14f.dp.toPx())
        val gap = barWidth * 0.25f
        val baseline = size.height

        // a faint line at the top of the tallest bar, so heights are comparable
        drawLine(
            color = gridColor,
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width, 0f),
            strokeWidth = 1f
        )

        series.forEachIndexed { index, day ->
            val centre = slotWidth * index + slotWidth / 2f
            val earnedHeight = (day.earned.toFloat() / peak.toFloat()) * baseline
            val spentHeight = (day.spent.toFloat() / peak.toFloat()) * baseline

            if (day.earned > 0) {
                drawRoundRect(
                    color = incomeColor,
                    topLeft = androidx.compose.ui.geometry.Offset(centre - barWidth - gap / 2f, baseline - earnedHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, earnedHeight.coerceAtLeast(2f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                )
            }
            if (day.spent > 0) {
                drawRoundRect(
                    color = expenseColor,
                    topLeft = androidx.compose.ui.geometry.Offset(centre + gap / 2f, baseline - spentHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, spentHeight.coerceAtLeast(2f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                )
            }
            // today gets a tick beneath it
            if (index == series.lastIndex) {
                drawLine(
                    color = todayMark,
                    start = androidx.compose.ui.geometry.Offset(centre - barWidth, baseline + 3f),
                    end = androidx.compose.ui.geometry.Offset(centre + barWidth, baseline + 3f),
                    strokeWidth = 3f
                )
            }
        }
    }
}

/** The two-colour key that goes under the chart. */
@Composable
fun ChartLegend(incomeColor: Color, expenseColor: Color, earnedLabel: String, spentLabel: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(incomeColor to earnedLabel, expenseColor to spentLabel).forEach { (swatch, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(swatch))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
