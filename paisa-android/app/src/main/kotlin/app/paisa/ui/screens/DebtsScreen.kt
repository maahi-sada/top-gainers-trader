package app.paisa.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.paisa.core.AppData
import app.paisa.core.Debt
import app.paisa.core.DebtDirection
import app.paisa.ui.EmptyState
import app.paisa.ui.EntryRow
import app.paisa.ui.Fmt
import app.paisa.ui.Meter
import app.paisa.ui.SectionCard
import app.paisa.ui.StatTile
import app.paisa.ui.expenseColor
import app.paisa.ui.incomeColor
import app.paisa.ui.warnColor
import java.time.LocalDate
import kotlin.math.absoluteValue

/**
 * The dedicated place for debts: who owes you, who you owe, how much is left of
 * each, and what is overdue.
 */
@Composable
fun DebtsScreen(
    data: AppData,
    today: LocalDate,
    onAddDebt: (DebtDirection) -> Unit,
    onOpenDebt: (String) -> Unit
) {
    val income = incomeColor()
    val expense = expenseColor()
    val warn = warnColor()

    data class Line(val debt: Debt, val outstanding: Long, val lent: Long)

    val lines = data.debts.map { debt ->
        val lent = data.transactions
            .filter { it.debtId == debt.id && (it.type == app.paisa.core.TxnType.LEND || it.type == app.paisa.core.TxnType.BORROW) }
            .sumOf { it.amount }
        Line(debt, data.outstanding(debt.id), lent)
    }
    val open = lines.filter { it.outstanding > 0 }
    val owedToMe = open.filter { it.debt.direction == DebtDirection.OWED_TO_ME }.sortedByDescending { it.outstanding }
    val iOwe = open.filter { it.debt.direction == DebtDirection.I_OWE }.sortedByDescending { it.outstanding }
    val settled = lines.filter { it.outstanding <= 0 }
    val net = data.receivables - data.payables

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("They owe me", Fmt.money(data.receivables), income, Modifier.weight(1f))
                StatTile("I owe them", Fmt.money(data.payables), expense, Modifier.weight(1f))
            }
        }

        item {
            Text(
                if (net >= 0) "Net position: ${Fmt.money(net)} in your favour"
                else "Net position: ${Fmt.money(net.absoluteValue)} against you",
                style = MaterialTheme.typography.bodyLarge,
                color = if (net >= 0) income else expense,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        if (lines.isEmpty()) {
            item {
                SectionCard {
                    EmptyState(
                        "No debts tracked",
                        "Record money you lend out or borrow. Every repayment is tracked against it.",
                        "Record money I lent"
                    ) { onAddDebt(DebtDirection.OWED_TO_ME) }
                }
            }
        }

        if (owedToMe.isNotEmpty()) {
            item {
                SectionCard(
                    title = "They owe me",
                    action = { TextButton(onClick = { onAddDebt(DebtDirection.OWED_TO_ME) }) { Text("+ Lend") } }
                ) {}
            }
        }
        items(owedToMe, key = { it.debt.id }) { line ->
            DebtLine(line.debt, line.outstanding, line.lent, today, income, warn, expense) { onOpenDebt(line.debt.id) }
        }

        if (iOwe.isNotEmpty()) {
            item {
                SectionCard(
                    title = "I owe them",
                    action = { TextButton(onClick = { onAddDebt(DebtDirection.I_OWE) }) { Text("+ Borrow") } }
                ) {}
            }
        }
        items(iOwe, key = { it.debt.id }) { line ->
            DebtLine(line.debt, line.outstanding, line.lent, today, expense, warn, expense) { onOpenDebt(line.debt.id) }
        }

        if (settled.isNotEmpty()) {
            item {
                Text(
                    "SETTLED",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }
            items(settled, key = { it.debt.id }) { line ->
                EntryRow(
                    dotColor = MaterialTheme.colorScheme.surfaceVariant,
                    glyph = "✓",
                    title = line.debt.person,
                    subtitle = "Cleared",
                    amount = Fmt.money(line.lent),
                    amountColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { onOpenDebt(line.debt.id) }
                )
            }
        }
    }
}

@Composable
private fun DebtLine(
    debt: Debt,
    outstanding: Long,
    lent: Long,
    today: LocalDate,
    amountColor: androidx.compose.ui.graphics.Color,
    warnColor: androidx.compose.ui.graphics.Color,
    overdueColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val cleared = (lent - outstanding).coerceAtLeast(0)
    val fraction = if (lent > 0) cleared.toFloat() / lent.toFloat() else 0f
    val overdue = debt.dueDate != null && debt.dueDate!!.isBefore(today)

    val subtitle = buildString {
        if (lent > 0) append("${Fmt.money(cleared)} of ${Fmt.money(lent)} cleared")
        if (debt.dueDate != null) {
            if (isNotEmpty()) append(" · ")
            append(Fmt.dueLabel(debt.dueDate!!, today))
        }
        if (isEmpty()) append(debt.note)
    }

    EntryRow(
        dotColor = if (overdue) overdueColor else amountColor,
        glyph = debt.person.take(1).uppercase(),
        title = debt.person,
        subtitle = subtitle,
        amount = Fmt.money(outstanding),
        amountColor = if (overdue) overdueColor else amountColor,
        onClick = onClick
    )
    if (lent > 0) {
        Meter(fraction, if (overdue) overdueColor else amountColor, Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(4.dp))
    }
}
