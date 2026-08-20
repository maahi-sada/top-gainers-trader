package app.paisa.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.paisa.core.AppData
import app.paisa.core.CategoryKind
import app.paisa.core.DebtDirection
import app.paisa.core.EntryInput
import app.paisa.core.Money
import app.paisa.core.TxnType
import app.paisa.ui.Fmt
import app.paisa.ui.SectionCard
import java.time.LocalDate

/** What the entry form is being opened for. */
sealed interface EntryTarget {
    data object New : EntryTarget
    data class Existing(val txnId: String) : EntryTarget
    data class Confirming(val inboxId: String) : EntryTarget
}

/**
 * One form for every kind of entry — spending, earning, moving money between
 * your own accounts, and the four debt movements.
 */
@Composable
fun EntryScreen(
    data: AppData,
    target: EntryTarget,
    today: LocalDate,
    /** Pre-filled fields when the form is opened from a card bill or a debt. */
    preset: EntryInput? = null,
    onSave: (EntryInput) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    val existing = (target as? EntryTarget.Existing)?.let { t -> data.transactions.firstOrNull { it.id == t.txnId } }
    val inboxItem = (target as? EntryTarget.Confirming)?.let { t -> data.inbox.firstOrNull { it.id == t.inboxId } }
    val suggestion = inboxItem?.let { data.suggestionFor(it, today) }

    var type by remember { mutableStateOf(preset?.type ?: existing?.type ?: suggestion?.type ?: TxnType.EXPENSE) }
    var amountText by remember {
        mutableStateOf(
            (preset?.amount?.takeIf { it > 0 } ?: existing?.amount ?: suggestion?.amount)
                ?.let { Money.toRupees(it).toPlainString() } ?: ""
        )
    }
    var date by remember { mutableStateOf(preset?.date ?: existing?.date ?: suggestion?.date ?: today) }
    var accountId by remember {
        mutableStateOf(
            preset?.accountId ?: existing?.accountId ?: suggestion?.accountId ?: data.liveAccounts.firstOrNull()?.id
        )
    }
    var toAccountId by remember {
        mutableStateOf(preset?.toAccountId ?: existing?.toAccountId ?: data.liveAccounts.getOrNull(1)?.id)
    }
    var categoryId by remember { mutableStateOf(preset?.categoryId ?: existing?.categoryId ?: suggestion?.categoryId) }
    var debtId by remember { mutableStateOf(preset?.debtId ?: existing?.debtId) }
    var personName by remember { mutableStateOf(preset?.personName ?: inboxItem?.parsed?.counterparty ?: "") }
    var interestText by remember {
        mutableStateOf(existing?.interest?.takeIf { it > 0 }?.let { Money.toRupees(it).toPlainString() } ?: "")
    }
    var note by remember { mutableStateOf(preset?.note?.ifBlank { null } ?: existing?.note ?: suggestion?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    val categoryKind = if (type == TxnType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val debtDirection = when (type) {
        TxnType.LEND, TxnType.COLLECT -> DebtDirection.OWED_TO_ME
        TxnType.BORROW, TxnType.SETTLE -> DebtDirection.I_OWE
        else -> null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text(
                when (target) {
                    is EntryTarget.Existing -> "Edit entry"
                    is EntryTarget.Confirming -> "Review entry"
                    else -> "New entry"
                },
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = {
                val amount = Money.parse(amountText) ?: 0L
                val interest = Money.parse(interestText) ?: 0L
                error = when {
                    amount <= 0L -> "Enter an amount"
                    type == TxnType.TRANSFER && accountId == toAccountId -> "Pick two different accounts"
                    (type == TxnType.LEND || type == TxnType.BORROW) && personName.isBlank() && debtId == null ->
                        "Whose debt is this?"
                    (type == TxnType.COLLECT || type == TxnType.SETTLE) && debtId == null -> "Pick who this is for"
                    interest > amount -> "Interest cannot exceed the amount"
                    else -> null
                }
                if (error == null) {
                    onSave(
                        EntryInput(
                            type = type, amount = amount, date = date,
                            accountId = accountId,
                            toAccountId = if (type == TxnType.TRANSFER) toAccountId else null,
                            categoryId = if (type == TxnType.EXPENSE || type == TxnType.INCOME) categoryId else null,
                            debtId = debtId, personName = personName.trim(),
                            interest = if (type == TxnType.COLLECT || type == TxnType.SETTLE) interest else 0L,
                            note = note.trim()
                        )
                    )
                }
            }) { Text("Save") }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            typeOptions.forEach { (option, label) ->
                FilterChip(
                    selected = type == option,
                    onClick = {
                        type = option
                        val kind = if (option == TxnType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
                        if (data.category(categoryId)?.kind != kind) {
                            categoryId = data.categories.firstOrNull { it.kind == kind && !it.archived }?.id
                        }
                        if (option != TxnType.COLLECT && option != TxnType.SETTLE &&
                            option != TxnType.LEND && option != TxnType.BORROW
                        ) debtId = null
                    },
                    label = { Text(label) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it; error = null },
            label = { Text("Amount (₹)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100, 500, 1000, 5000).forEach { step ->
                OutlinedButton(onClick = {
                    val current = Money.parse(amountText) ?: 0L
                    amountText = Money.toRupees(current + step * 100L).toPlainString()
                }) { Text("+$step") }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "Date") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { date = date.minusDays(1) }) { Text("−1 day") }
                Spacer(Modifier.width(12.dp))
                Text(Fmt.date(date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (date != today) {
                    TextButton(onClick = { date = today }) { Text("Today") }
                }
                OutlinedButton(
                    onClick = { if (date.isBefore(today)) date = date.plusDays(1) },
                    enabled = date.isBefore(today)
                ) { Text("+1 day") }
            }
        }

        Spacer(Modifier.height(12.dp))

        ChipPicker(
            title = if (type == TxnType.TRANSFER) "From account" else "Account",
            options = data.liveAccounts.map { it.id to "${it.name} · ${Fmt.money(data.balance(it.id))}" },
            selectedId = accountId,
            onSelect = { accountId = it }
        )

        if (type == TxnType.TRANSFER) {
            Spacer(Modifier.height(12.dp))
            ChipPicker(
                title = "To account",
                options = data.liveAccounts.map { it.id to it.name },
                selectedId = toAccountId,
                onSelect = { toAccountId = it }
            )
        }

        if (type == TxnType.EXPENSE || type == TxnType.INCOME) {
            Spacer(Modifier.height(12.dp))
            ChipPicker(
                title = "Category",
                options = data.categories.filter { it.kind == categoryKind && !it.archived }.map { it.id to it.name },
                selectedId = categoryId,
                onSelect = { categoryId = it }
            )
        }

        if (type == TxnType.LEND || type == TxnType.BORROW) {
            Spacer(Modifier.height(12.dp))
            val existingDebts = data.debts.filter { it.direction == debtDirection }
            if (existingDebts.isNotEmpty()) {
                ChipPicker(
                    title = "Existing person",
                    options = existingDebts.map { it.id to it.person },
                    selectedId = debtId,
                    onSelect = { debtId = if (debtId == it) null else it }
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = personName,
                onValueChange = { personName = it; debtId = null },
                label = { Text("Or a new person") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (type == TxnType.LEND)
                    "Money leaves your account but is still yours — tracked as a receivable, not as spending."
                else
                    "Money arrives but it is not income — tracked as something you owe.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (type == TxnType.COLLECT || type == TxnType.SETTLE) {
            Spacer(Modifier.height(12.dp))
            val open = data.debts.filter { it.direction == debtDirection && data.outstanding(it.id) > 0 }
            if (open.isEmpty()) {
                Text(
                    "Nothing open in this direction yet. Record a ‘Lent out’ or ‘Borrowed’ entry first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                ChipPicker(
                    title = "Who",
                    options = open.map { it.id to "${it.person} · ${Fmt.money(data.outstanding(it.id))}" },
                    selectedId = debtId,
                    onSelect = { debtId = it }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = interestText,
                    onValueChange = { interestText = it },
                    label = { Text("Of which interest (₹, optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Interest counts as ${if (type == TxnType.COLLECT) "income" else "an expense"}. The rest reduces what is outstanding.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val amount = Money.parse(amountText) ?: 0L
                if (amount > 0) {
                    onSave(
                        EntryInput(
                            type = type, amount = amount, date = date, accountId = accountId,
                            toAccountId = if (type == TxnType.TRANSFER) toAccountId else null,
                            categoryId = if (type == TxnType.EXPENSE || type == TxnType.INCOME) categoryId else null,
                            debtId = debtId, personName = personName.trim(),
                            interest = if (type == TxnType.COLLECT || type == TxnType.SETTLE) Money.parse(interestText) ?: 0L else 0L,
                            note = note.trim()
                        )
                    )
                } else {
                    error = "Enter an amount"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (target is EntryTarget.Confirming) "Log it" else "Save entry") }

        if (onDelete != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

private val typeOptions = listOf(
    TxnType.EXPENSE to "Spent",
    TxnType.INCOME to "Earned",
    TxnType.TRANSFER to "Transfer",
    TxnType.LEND to "Lent out",
    TxnType.BORROW to "Borrowed",
    TxnType.COLLECT to "Got back",
    TxnType.SETTLE to "Repaid"
)

/** A horizontally scrolling row of choices; simpler and steadier than a dropdown. */
@Composable
private fun ChipPicker(
    title: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (id, label) ->
                FilterChip(
                    selected = selectedId == id,
                    onClick = { onSelect(id) },
                    label = { Text(label) }
                )
            }
        }
    }
}
