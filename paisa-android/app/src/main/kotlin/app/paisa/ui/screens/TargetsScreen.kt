package app.paisa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.paisa.core.AppData
import app.paisa.core.DailyTarget
import app.paisa.core.Money
import app.paisa.ui.Fmt
import app.paisa.ui.Meter
import app.paisa.ui.SectionCard
import app.paisa.ui.StatTile
import app.paisa.ui.expenseColor
import app.paisa.ui.incomeColor
import java.time.LocalDate

/**
 * The dedicated place for daily earning targets: what you set, whether today is
 * there yet, how long the run is, and whether the month is on pace.
 */
@Composable
fun TargetsScreen(
    data: AppData,
    today: LocalDate,
    onSave: (dailyTarget: Long, monthlyBudget: Long) -> Unit
) {
    var dailyText by remember(data.settings.dailyEarningTarget) {
        mutableStateOf(if (data.settings.dailyEarningTarget > 0) Money.toRupees(data.settings.dailyEarningTarget).toPlainString() else "")
    }
    var budgetText by remember(data.settings.monthlyBudget) {
        mutableStateOf(if (data.settings.monthlyBudget > 0) Money.toRupees(data.settings.monthlyBudget).toPlainString() else "")
    }

    val progress = data.todayProgress(today)
    val pace = data.monthPace(today)
    val streak = data.streak(today)
    val income = incomeColor()
    val expense = expenseColor()

    val recentDays = remember(data.transactions, data.settings.dailyEarningTarget, today) {
        (0L until 14L).map { back ->
            DailyTarget.progress(today.minusDays(back), data.settings.dailyEarningTarget, data.transactions)
        }
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "What you are aiming for") {
                OutlinedTextField(
                    value = dailyText,
                    onValueChange = { dailyText = it },
                    label = { Text("Daily earning target (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it },
                    label = { Text("Monthly spending budget (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        onSave(Money.parse(dailyText) ?: 0L, Money.parse(budgetText) ?: 0L)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save targets") }
            }
        }

        item {
            SectionCard(title = "Today") {
                Meter(progress.fraction.toFloat(), if (progress.met) income else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                Text(
                    "${Fmt.money(progress.earned)} earned of ${Fmt.money(progress.target)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    when {
                        progress.target <= 0L -> "No target set yet"
                        progress.met -> "Target met, ${Fmt.money(progress.surplus)} above it"
                        else -> "${Fmt.money(progress.shortfall)} still to come in"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Run of days", if (streak == 0) "—" else "$streak", income, Modifier.weight(1f))
                StatTile(
                    "Month so far",
                    Fmt.money(pace.earned),
                    if (pace.ahead) income else expense,
                    Modifier.weight(1f)
                )
            }
        }

        item {
            SectionCard(title = "This month's pace") {
                Text(
                    if (pace.ahead) "Ahead by ${Fmt.money(pace.gap)}" else "Behind by ${Fmt.money(-pace.gap)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (pace.ahead) income else expense
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Day ${pace.daysElapsed} of ${pace.daysElapsed + pace.daysRemaining}. " +
                        "Expected ${Fmt.money(pace.expected)} by now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pace.daysRemaining > 0 && pace.requiredPerRemainingDay > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${Fmt.money(pace.requiredPerRemainingDay)} a day for the ${pace.daysRemaining} days left " +
                            "to finish on ${Fmt.money(pace.monthTarget)}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Text(
                "LAST 14 DAYS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        items(recentDays, key = { it.date.toString() }) { day ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    Fmt.relativeDay(day.date, today),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    Fmt.money(day.earned),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (day.met) income else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Meter(
                day.fraction.toFloat(),
                if (day.met) income else MaterialTheme.colorScheme.primary,
                Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
