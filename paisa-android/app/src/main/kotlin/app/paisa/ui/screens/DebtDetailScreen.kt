package app.paisa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.paisa.core.AppData
import app.paisa.core.DebtDirection
import app.paisa.core.TxnType
import app.paisa.ui.EntryRow
import app.paisa.ui.Fmt
import app.paisa.ui.Meter
import app.paisa.ui.Pill
import app.paisa.ui.SectionCard
import app.paisa.ui.expenseColor
import app.paisa.ui.incomeColor
import java.time.LocalDate

/** One person's debt: what is left, what has been paid, and every movement. */
@Composable
fun DebtDetailScreen(
    data: AppData,
    debtId: String,
    today: LocalDate,
    onRecordPayment: () -> Unit,
    onLendMore: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onEditTxn: (String) -> Unit
) {
    val debt = data.debt(debtId) ?: return
    val income = incomeColor()
    val expense = expenseColor()
    val theyOweMe = debt.direction == DebtDirection.OWED_TO_ME
    val accent = if (theyOweMe) income else expense

    val movements = data.transactions.filter { it.debtId == debtId }.sortedByDescending { it.date }
    val advanced = movements.filter { it.type == TxnType.LEND || it.type == TxnType.BORROW }.sumOf { it.amount }
    val cleared = movements.filter { it.type == TxnType.COLLECT || it.type == TxnType.SETTLE }.sumOf { it.principal }
    val interest = movements.sumOf { it.interest }
    val outstanding = data.outstanding(debtId)

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.weight(1f))
            }
        }

        item {
            SectionCard(title = if (theyOweMe) "Owes you" else "You owe them") {
                Text(debt.person, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (outstanding > 0) Fmt.money(outstanding) else "Settled",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (outstanding > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (advanced > 0) {
                    Spacer(Modifier.height(10.dp))
                    Meter((cleared.toFloat() / advanced.toFloat()).coerceIn(0f, 1f), accent)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${Fmt.money(cleared)} of ${Fmt.money(advanced)} cleared" +
                            if (interest > 0) " · ${Fmt.money(interest)} interest" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (debt.dueDate != null) {
                    Spacer(Modifier.height(8.dp))
                    Pill(
                        Fmt.dueLabel(debt.dueDate!!, today),
                        if (debt.dueDate!!.isBefore(today) && outstanding > 0) expense else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onRecordPayment, modifier = Modifier.weight(1f)) {
                    Text(if (theyOweMe) "Money received" else "Record repayment")
                }
                OutlinedButton(onClick = onLendMore, modifier = Modifier.weight(1f)) {
                    Text(if (theyOweMe) "Lend more" else "Borrow more")
                }
            }
        }

        item {
            Text(
                "HISTORY",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        items(movements, key = { it.id }) { txn ->
            EntryRow(
                dotColor = accent,
                glyph = if (txn.type.sign > 0) "↑" else "↓",
                title = when (txn.type) {
                    TxnType.LEND -> "Lent out"
                    TxnType.BORROW -> "Borrowed"
                    TxnType.COLLECT -> "Got back"
                    TxnType.SETTLE -> "Repaid"
                    else -> txn.type.name
                },
                subtitle = listOfNotNull(
                    Fmt.shortDate(txn.date),
                    txn.note.ifBlank { null },
                    txn.interest.takeIf { it > 0 }?.let { "incl. ${Fmt.money(it)} interest" }
                ).joinToString(" · "),
                amount = Fmt.signed(txn.amount, txn.type.sign),
                amountColor = if (txn.type.sign > 0) income else expense,
                onClick = { onEditTxn(txn.id) }
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("Delete this debt and its history", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
