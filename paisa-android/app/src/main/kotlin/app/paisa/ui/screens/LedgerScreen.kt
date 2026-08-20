package app.paisa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.paisa.core.AppData
import app.paisa.core.Money
import app.paisa.core.Txn
import app.paisa.ui.EmptyState
import app.paisa.ui.EntryRow
import app.paisa.ui.Fmt
import app.paisa.ui.expenseColor
import app.paisa.ui.incomeColor
import app.paisa.ui.toComposeColor
import java.time.LocalDate

/** Every entry, newest first, grouped by day with the day's net. */
@Composable
fun LedgerScreen(data: AppData, today: LocalDate, onEditTxn: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val income = incomeColor()
    val expense = expenseColor()

    val matches = remember(data.transactions, query) {
        val needle = query.trim().lowercase()
        val all = data.ledger()
        if (needle.isEmpty()) all else all.filter { txn ->
            val haystack = listOfNotNull(
                txn.note,
                data.category(txn.categoryId)?.name,
                data.debt(txn.debtId)?.person,
                data.account(txn.accountId)?.name,
                Money.toRupees(txn.amount).toPlainString()
            ).joinToString(" ").lowercase()
            haystack.contains(needle)
        }
    }

    val days: List<Pair<LocalDate, List<Txn>>> = remember(matches) {
        matches.groupBy { it.date }.toList().sortedByDescending { it.first }
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search notes, people, amounts") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        if (days.isEmpty()) {
            item {
                EmptyState(
                    if (query.isBlank()) "Nothing recorded yet" else "No matching entries",
                    if (query.isBlank()) "Add an entry with the + button, or let Paisa read your bank messages."
                    else "Try a different search."
                )
            }
        }

        days.forEach { (date, entries) ->
            val net = entries.sumOf { it.type.sign * it.amount }
            item(key = "head-$date") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        Fmt.relativeDay(date, today),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        Fmt.signed(net, if (net >= 0) 1 else -1),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (net >= 0) income else expense
                    )
                }
            }

            items(entries, key = { it.id }) { txn ->
                val category = data.category(txn.categoryId)
                val debt = data.debt(txn.debtId)
                val account = data.account(txn.accountId)
                val destination = data.account(txn.toAccountId)

                EntryRow(
                    dotColor = category?.color?.toComposeColor() ?: MaterialTheme.colorScheme.primary,
                    glyph = if (txn.type.sign > 0) "↑" else if (txn.type.sign < 0) "↓" else "⇄",
                    title = when {
                        debt != null -> debt.person
                        txn.type == app.paisa.core.TxnType.TRANSFER ->
                            "${account?.name ?: "?"} → ${destination?.name ?: "?"}"
                        else -> category?.name ?: "Uncategorised"
                    },
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
}
