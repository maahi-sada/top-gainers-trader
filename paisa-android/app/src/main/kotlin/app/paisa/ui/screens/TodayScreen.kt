package app.paisa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.paisa.core.AppData
import app.paisa.core.Money
import app.paisa.core.TxnType
import app.paisa.ui.BigMoney
import app.paisa.ui.EmptyState
import app.paisa.ui.EntryRow
import app.paisa.ui.Fmt
import app.paisa.ui.Meter
import app.paisa.ui.ProgressRing
import app.paisa.ui.SectionCard
import app.paisa.ui.StatTile
import app.paisa.ui.expenseColor
import app.paisa.ui.incomeColor
import app.paisa.ui.toComposeColor
import java.time.LocalDate

/**
 * Every rupee, today. What came in against the target, what went out, what is
 * left, and what is owed either way.
 */
@Composable
fun TodayScreen(
    data: AppData,
    today: LocalDate,
    onOpenTargets: () -> Unit,
    onOpenLedger: () -> Unit,
    onOpenDebts: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenInbox: () -> Unit,
    onEditTxn: (String) -> Unit
) {
    val progress = data.todayProgress(today)
    val streak = data.streak(today)
    val spentToday = app.paisa.core.Ledger.summary(data.transactions, today, today).expense
    val month = data.summary(today)
    val income = incomeColor()
    val expense = expenseColor()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (data.inbox.isNotEmpty()) {
            item {
                SectionCard(action = { TextButton(onClick = onOpenInbox) { Text("Review") } }) {
                    Text(
                        if (data.inbox.size == 1) "1 entry captured, waiting for you"
                        else "${data.inbox.size} entries captured, waiting for you",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Read from your messages and email",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "Today's earning target",
                action = { TextButton(onClick = onOpenTargets) { Text("Targets") } }
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProgressRing(fraction = progress.fraction.toFloat()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                Money.formatShort(progress.earned),
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (progress.met) income else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (progress.target > 0) "of ${Money.formatShort(progress.target)}" else "no target set",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            progress.target <= 0L -> "Set a daily target to track how you are doing"
                            progress.met -> "Target met — ${Fmt.money(progress.surplus)} above it"
                            else -> "${Fmt.money(progress.shortfall)} to go today"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (streak > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (streak == 1) "1 day in a row" else "$streak days in a row",
                            style = MaterialTheme.typography.labelLarge,
                            color = income
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Earned today", Fmt.money(progress.earned), income, Modifier.weight(1f))
                StatTile("Spent today", Fmt.money(spentToday), expense, Modifier.weight(1f))
            }
        }

        item {
            SectionCard(title = "Money in hand") {
                BigMoney(data.moneyInHand)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Net worth ${Fmt.money(data.netWorth)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (data.cardDebt > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${Fmt.money(data.cardDebt)} owed on cards",
                        style = MaterialTheme.typography.bodyMedium,
                        color = expense
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("This month in", Fmt.money(month.income), income, Modifier.weight(1f))
                StatTile("This month out", Fmt.money(month.expense), expense, Modifier.weight(1f))
            }
        }

        if (data.settings.monthlyBudget > 0) {
            item {
                val used = (month.expense.toFloat() / data.settings.monthlyBudget.toFloat()).coerceIn(0f, 1f)
                val over = month.expense > data.settings.monthlyBudget
                SectionCard(title = "Monthly budget") {
                    Meter(used, if (over) expense else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (over) "${Fmt.money(month.expense - data.settings.monthlyBudget)} over budget"
                        else "${Fmt.money(data.settings.monthlyBudget - month.expense)} left this month",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (over) expense else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("To receive", Fmt.money(data.receivables), income, Modifier.weight(1f))
                StatTile("I owe", Fmt.money(data.payables), expense, Modifier.weight(1f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenDebts, modifier = Modifier.weight(1f)) { Text("Debts") }
                TextButton(onClick = onOpenCards, modifier = Modifier.weight(1f)) { Text("Cards") }
            }
        }

        val recent = data.ledger().take(8)
        item {
            SectionCard(
                title = "Recent",
                action = { TextButton(onClick = onOpenLedger) { Text("See all") } }
            ) {
                if (recent.isEmpty()) {
                    EmptyState(
                        "Nothing recorded yet",
                        "Add your first entry, or let Paisa read your bank messages for you."
                    )
                }
            }
        }

        items(recent, key = { it.id }) { txn ->
            val category = data.category(txn.categoryId)
            val debt = data.debt(txn.debtId)
            val account = data.account(txn.accountId)
            EntryRow(
                dotColor = category?.color?.toComposeColor() ?: MaterialTheme.colorScheme.primary,
                glyph = if (txn.type.sign > 0) "↑" else if (txn.type.sign < 0) "↓" else "⇄",
                title = debt?.person ?: category?.name ?: "Uncategorised",
                subtitle = listOfNotNull(account?.name, txn.note.ifBlank { null }).joinToString(" · "),
                amount = Fmt.signed(txn.amount, txn.type.sign),
                amountColor = when {
                    txn.type.sign > 0 -> income
                    txn.type.sign < 0 -> expense
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                onClick = { onEditTxn(txn.id) }
            )
        }
    }
}
