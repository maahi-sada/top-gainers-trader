package app.paisa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.paisa.core.AppData
import app.paisa.core.CaptureSource
import app.paisa.core.InboxItem
import app.paisa.ui.EmptyState
import app.paisa.ui.EntryRow
import app.paisa.ui.Fmt
import app.paisa.ui.SectionCard
import app.paisa.ui.expenseColor
import app.paisa.ui.incomeColor
import app.paisa.ui.toComposeColor
import java.time.LocalDate

/**
 * Everything read automatically, waiting for a tap. Nothing here has touched
 * the ledger yet.
 */
@Composable
fun InboxScreen(
    data: AppData,
    today: LocalDate,
    onConfirm: (String) -> Unit,
    onConfirmAll: () -> Unit,
    onDiscard: (String) -> Unit,
    onEdit: (String) -> Unit,
    onPasteMessage: () -> Unit
) {
    val income = incomeColor()
    val expense = expenseColor()
    val items = data.inbox.sortedByDescending { it.receivedAtMillis }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (items.isEmpty()) {
            item {
                SectionCard {
                    EmptyState(
                        "Nothing waiting",
                        "Bank messages are read as they arrive. You can also paste one in, or check your email from Settings.",
                        "Paste a message",
                        onPasteMessage
                    )
                }
            }
            return@LazyColumn
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (items.size == 1) "1 entry waiting" else "${items.size} entries waiting",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
                TextButton(onClick = onConfirmAll) { Text("Log all") }
            }
        }

        items(items, key = { it.id }) { item ->
            InboxRow(data, item, today, income, expense, onConfirm, onDiscard, onEdit)
        }

        item {
            Text(
                "Tap a row to change anything before logging. Paisa remembers the category you pick and files that shop the same way next time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun InboxRow(
    data: AppData,
    item: InboxItem,
    today: LocalDate,
    incomeColor: androidx.compose.ui.graphics.Color,
    expenseColor: androidx.compose.ui.graphics.Color,
    onConfirm: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onEdit: (String) -> Unit
) {
    val suggestion = data.suggestionFor(item, today)
    val category = data.category(suggestion.categoryId)
    val account = data.account(suggestion.accountId)
    val sign = suggestion.type.sign

    val sourceLabel = when (item.source) {
        CaptureSource.SMS -> "message"
        CaptureSource.EMAIL -> "email"
        CaptureSource.RECURRING -> "scheduled"
        CaptureSource.IMPORT -> "pasted"
        CaptureSource.MANUAL -> "manual"
    }

    val subtitle = listOfNotNull(
        Fmt.shortDate(suggestion.date),
        category?.name,
        account?.name,
        sourceLabel,
        if (suggestion.matchedRule) "auto" else null
    ).joinToString(" · ")

    EntryRow(
        dotColor = category?.color?.toComposeColor() ?: MaterialTheme.colorScheme.primary,
        glyph = if (sign > 0) "↑" else "↓",
        title = item.parsed.counterparty ?: "Unnamed",
        subtitle = subtitle,
        amount = Fmt.signed(suggestion.amount, sign),
        amountColor = if (sign > 0) incomeColor else expenseColor,
        onClick = { onEdit(item.id) },
        trailing = {
            Row {
                IconButton(onClick = { onConfirm(item.id) }) {
                    Icon(Icons.Filled.Check, contentDescription = "Log this entry", tint = incomeColor)
                }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = { onDiscard(item.id) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Discard",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
