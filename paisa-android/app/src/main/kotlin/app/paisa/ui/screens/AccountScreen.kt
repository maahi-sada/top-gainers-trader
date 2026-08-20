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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import app.paisa.core.Account
import app.paisa.core.AccountType
import app.paisa.core.AppData
import app.paisa.core.Ids
import app.paisa.core.Money
import app.paisa.ui.SectionCard

/**
 * Adding or editing an account. Credit cards carry their limit and the two days
 * that drive the billing cycle, which is what the Cards screen runs on.
 */
@Composable
fun AccountScreen(
    data: AppData,
    accountId: String?,
    onSave: (Account) -> Unit,
    onCancel: () -> Unit
) {
    val existing = data.account(accountId)

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: AccountType.BANK) }
    var openingText by remember {
        mutableStateOf(existing?.openingBalance?.takeIf { it != 0L }?.let { Money.toRupees(it).toPlainString() } ?: "")
    }
    var limitText by remember {
        mutableStateOf(existing?.creditLimit?.takeIf { it > 0 }?.let { Money.toRupees(it).toPlainString() } ?: "")
    }
    var statementDay by remember { mutableStateOf((existing?.statementDay ?: 1).toString()) }
    var dueDay by remember { mutableStateOf((existing?.dueDay ?: 1).toString()) }
    var last4 by remember { mutableStateOf(existing?.last4 ?: "") }
    var archived by remember { mutableStateOf(existing?.archived ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text(if (existing == null) "New account" else "Edit account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(1.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Name") },
            placeholder = { Text("e.g. HDFC Savings, Amex Platinum") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        Text("TYPE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AccountType.CASH to "Cash",
                AccountType.BANK to "Bank",
                AccountType.WALLET to "Wallet / UPI",
                AccountType.CREDIT_CARD to "Credit card"
            ).forEach { (option, label) ->
                FilterChip(selected = type == option, onClick = { type = option }, label = { Text(label) })
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = last4,
            onValueChange = { last4 = it.filter { c -> c.isDigit() }.take(6) },
            label = { Text("Last digits as they appear in bank messages") },
            placeholder = { Text("e.g. 1234") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Captured messages ending in these digits are filed to this account automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (type == AccountType.CREDIT_CARD) {
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "Billing") {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it },
                    label = { Text("Credit limit (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = statementDay,
                        onValueChange = { statementDay = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Statement day") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dueDay,
                        onValueChange = { dueDay = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Due day") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "The day of the month your statement closes, and the day the bill is due. " +
                        "A due day earlier than the statement day means the following month.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = openingText,
                onValueChange = { openingText = it },
                label = { Text("Opening balance (₹)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "What was in this account when you started tracking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (existing != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = archived, onCheckedChange = { archived = it })
                Spacer(Modifier.height(1.dp))
                Text("  Archived (hidden from new entries)", style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (name.isBlank()) {
                    error = "Give the account a name"
                    return@Button
                }
                onSave(
                    Account(
                        id = existing?.id ?: Ids.next("acc"),
                        name = name.trim(),
                        type = type,
                        openingBalance = if (type == AccountType.CREDIT_CARD) 0L else (Money.parse(openingText) ?: 0L),
                        creditLimit = if (type == AccountType.CREDIT_CARD) (Money.parse(limitText) ?: 0L) else 0L,
                        statementDay = statementDay.toIntOrNull()?.coerceIn(1, 28) ?: 1,
                        dueDay = dueDay.toIntOrNull()?.coerceIn(1, 28) ?: 1,
                        last4 = last4.ifBlank { null },
                        archived = archived
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save account") }

        Spacer(Modifier.height(32.dp))
    }
}
