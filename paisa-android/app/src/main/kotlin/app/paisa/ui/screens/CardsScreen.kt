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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.paisa.core.AppData
import app.paisa.core.CardStatus
import app.paisa.core.CategoryKind
import app.paisa.core.Ledger
import app.paisa.ui.EmptyState
import app.paisa.ui.EntryRow
import app.paisa.ui.Fmt
import app.paisa.ui.Meter
import app.paisa.ui.Pill
import app.paisa.ui.SectionCard
import app.paisa.ui.StatTile
import app.paisa.ui.expenseColor
import app.paisa.ui.incomeColor
import app.paisa.ui.toComposeColor
import app.paisa.ui.warnColor
import java.time.LocalDate

/**
 * The dedicated place for credit cards: what is owed, what is on the last
 * statement, what has been spent since, how much of the limit is gone, and when
 * the bill falls due.
 */
@Composable
fun CardsScreen(
    data: AppData,
    today: LocalDate,
    onAddCard: () -> Unit,
    onEditCard: (String) -> Unit,
    onPayBill: (accountId: String, amount: Long) -> Unit
) {
    val statuses = data.cardStatuses(today)
    val expense = expenseColor()
    val income = incomeColor()
    val warn = warnColor()

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Owed on cards", Fmt.money(data.cardDebt), expense, Modifier.weight(1f))
                StatTile(
                    "Due within 7 days",
                    Fmt.money(statuses.filter { (it.daysToDue ?: 99) in 0..7 }.sumOf { it.outstanding }),
                    warn,
                    Modifier.weight(1f)
                )
            }
        }

        if (statuses.isEmpty()) {
            item {
                SectionCard {
                    EmptyState(
                        "No credit cards yet",
                        "Add a card yourself, or turn on email and message reading in Settings — Paisa picks " +
                            "the limit, statement date and due date straight out of your card statements.",
                        "Add a card",
                        onAddCard
                    )
                }
            }
        }

        items(statuses, key = { it.account.id }) { status ->
            CardPanel(
                status = status,
                data = data,
                today = today,
                onEdit = { onEditCard(status.account.id) },
                onPay = { onPayBill(status.account.id, status.outstanding) },
                expenseColor = expense,
                incomeColor = income,
                warnColor = warn
            )
        }

        if (statuses.isNotEmpty()) {
            item {
                TextButton(onClick = onAddCard, modifier = Modifier.fillMaxWidth()) { Text("Add another card") }
            }
        }
    }
}

@Composable
private fun CardPanel(
    status: CardStatus,
    data: AppData,
    today: LocalDate,
    onEdit: () -> Unit,
    onPay: () -> Unit,
    expenseColor: androidx.compose.ui.graphics.Color,
    incomeColor: androidx.compose.ui.graphics.Color,
    warnColor: androidx.compose.ui.graphics.Color
) {
    val account = status.account
    val dueColor = when {
        status.overdue -> expenseColor
        status.dueSoon -> warnColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SectionCard(
        title = account.name + (account.last4?.let { " ····$it" } ?: ""),
        action = { TextButton(onClick = onEdit) { Text("Edit") } }
    ) {
        Text(
            Fmt.money(status.outstanding),
            style = MaterialTheme.typography.displaySmall,
            color = if (status.outstanding > 0) expenseColor else incomeColor
        )
        Text(
            if (status.outstanding > 0) "outstanding" else "nothing owed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (status.creditLimit > 0) {
            Spacer(Modifier.height(12.dp))
            Meter(
                status.utilisation.toFloat(),
                if (status.utilisation > 0.7) expenseColor else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${Fmt.percent(status.utilisation)} of ${Fmt.money(status.creditLimit)} used · " +
                    "${Fmt.money(status.available)} available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Last statement", Fmt.money(status.billed), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatTile("Since then", Fmt.money(status.unbilled), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }

        /* What the bank itself billed, when a statement has been read. Paisa's
         * own figure counts only what it captured, so the two can differ. */
        if (account.lastStatementDue > 0 || account.lastMinimumDue > 0) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Billed by the bank", Fmt.money(account.lastStatementDue), expenseColor, Modifier.weight(1f))
                StatTile("Minimum due", Fmt.money(account.lastMinimumDue), warnColor, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(10.dp))
        // Bound to a local: a nullable property from another module does not smart-cast.
        val dueDate = status.dueDate
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dueDate != null) {
                Pill(
                    if (status.outstanding > 0) "Bill ${Fmt.dueLabel(dueDate, today)}"
                    else "Next due ${Fmt.shortDate(dueDate)}",
                    dueColor
                )
            }
            Spacer(Modifier.weight(1f))
            if (status.outstanding > 0) {
                TextButton(onClick = onPay) { Text("Pay bill") }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Statement closes on the ${ordinal(account.statementDay)}, bill due on the ${ordinal(account.dueDay)}. " +
                "Current period ${Fmt.shortDate(status.openCycle.start)} to ${Fmt.shortDate(status.openCycle.end)}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Bound to a local: a nullable property from another module does not smart-cast.
        val readFrom = account.detailsFrom
        if (readFrom != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Limit and dates read from your $readFrom.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val cycleSpends = data.transactions
            .filter { it.accountId == account.id && it.date in status.openCycle && it.type == app.paisa.core.TxnType.EXPENSE }
            .sortedByDescending { it.date }
            .take(6)

        if (cycleSpends.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "THIS PERIOD",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            cycleSpends.forEach { txn ->
                val category = data.category(txn.categoryId)
                EntryRow(
                    dotColor = category?.color?.toComposeColor() ?: MaterialTheme.colorScheme.primary,
                    glyph = "↓",
                    title = category?.name ?: "Uncategorised",
                    subtitle = listOfNotNull(Fmt.shortDate(txn.date), txn.note.ifBlank { null }).joinToString(" · "),
                    amount = Fmt.money(txn.amount),
                    amountColor = expenseColor
                )
            }
        }

        val topCategories = Ledger.byCategory(
            CategoryKind.EXPENSE,
            data.transactions.filter { it.accountId == account.id },
            data.categories,
            status.openCycle.start,
            status.openCycle.end
        ).take(3)

        if (topCategories.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Mostly on " + topCategories.joinToString(", ") { it.label.lowercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun ordinal(day: Int): String {
    val suffix = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
