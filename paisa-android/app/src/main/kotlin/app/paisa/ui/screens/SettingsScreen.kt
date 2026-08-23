package app.paisa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.paisa.core.AppData
import app.paisa.data.EmailSettings
import app.paisa.ui.EntryRow
import app.paisa.ui.Fmt
import app.paisa.ui.SectionCard
import app.paisa.ui.toComposeColor

/**
 * Where the automatic capture is switched on: messages, email, and what the app
 * is allowed to log without asking.
 */
@Composable
fun SettingsScreen(
    data: AppData,
    smsGranted: Boolean,
    emailSettings: EmailSettings,
    captureStatus: String?,
    busy: Boolean,
    onRequestSmsPermission: () -> Unit,
    onBackfillSms: () -> Unit,
    onSaveEmail: (EmailSettings) -> Unit,
    onCheckEmailNow: () -> Unit,
    onToggleAutoConfirm: (Boolean) -> Unit,
    onMonthStartDay: (Int) -> Unit,
    onOpenTargets: () -> Unit,
    onPasteMessage: () -> Unit,
    onAddAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    onForgetRule: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    var host by remember(emailSettings.host) { mutableStateOf(emailSettings.host) }
    var port by remember(emailSettings.port) { mutableStateOf(emailSettings.port.toString()) }
    var username by remember(emailSettings.username) { mutableStateOf(emailSettings.username) }
    var password by remember(emailSettings.password) { mutableStateOf(emailSettings.password) }
    var senders by remember(emailSettings.extraSenders) { mutableStateOf(emailSettings.extraSenders) }
    var monthStart by remember(data.settings.monthStartDay) { mutableStateOf(data.settings.monthStartDay.toString()) }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (captureStatus != null) {
            item {
                SectionCard { Text(captureStatus, style = MaterialTheme.typography.bodyLarge) }
            }
        }

        item {
            SectionCard(title = "Reading your bank messages") {
                Text(
                    if (smsGranted) "Paisa can read bank SMS as they arrive."
                    else "Paisa needs permission to read bank SMS.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Messages are read on this device and never sent anywhere. OTPs, bill reminders and offers are ignored.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (!smsGranted) {
                    Button(onClick = onRequestSmsPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow message reading")
                    }
                } else {
                    OutlinedButton(onClick = onBackfillSms, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Reading…" else "Read the last 90 days of messages")
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onPasteMessage, modifier = Modifier.fillMaxWidth()) {
                    Text("Paste a message by hand")
                }
            }
        }

        item {
            SectionCard(title = "Reading your bank emails") {
                Text(
                    "Connect the mailbox your bank alerts arrive in. Paisa opens it read-only and never marks " +
                        "anything read. Card statements are read too, for each card's limit, statement date and due date.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = emailSettings.enabled,
                        onCheckedChange = { onSaveEmail(emailSettings.copy(enabled = it)) }
                    )
                    Text("  Check email every few hours", style = MaterialTheme.typography.bodyLarge)
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("App password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Use an app-specific password, not your main one. Gmail: Google Account → Security → App passwords.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("IMAP server") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = senders,
                    onValueChange = { senders = it },
                    label = { Text("Extra bank senders (optional)") },
                    placeholder = { Text("mycoopbank, creditcardalerts") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "The big banks are recognised already. Add fragments of any other sender you want read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSaveEmail(
                                emailSettings.copy(
                                    host = host.trim(),
                                    port = port.toIntOrNull() ?: 993,
                                    username = username.trim(),
                                    password = password,
                                    extraSenders = senders.trim()
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                    OutlinedButton(onClick = onCheckEmailNow, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(if (busy) "Checking…" else "Check now")
                    }
                }
            }
        }

        item {
            SectionCard(title = "What Paisa may log on its own") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = data.settings.autoConfirm, onCheckedChange = onToggleAutoConfirm)
                    Text("  Log known shops without asking", style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "Off by default: everything captured waits in the Inbox for a tap. Turn this on once the categories look right, " +
                        "and shops you have filed before will skip the review step.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        item {
            SectionCard(title = "Targets and month", action = { TextButton(onClick = onOpenTargets) { Text("Open") } }) {
                Text(
                    "Daily earning target ${Fmt.money(data.settings.dailyEarningTarget)}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = monthStart,
                    onValueChange = {
                        monthStart = it.filter { c -> c.isDigit() }.take(2)
                        monthStart.toIntOrNull()?.let { day -> onMonthStartDay(day.coerceIn(1, 28)) }
                    },
                    label = { Text("Month starts on day") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Set this to your salary date so \"this month\" follows your pay cycle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            SectionCard(title = "Accounts", action = { TextButton(onClick = onAddAccount) { Text("+ Add") } }) {
                data.accounts.forEach { account ->
                    EntryRow(
                        dotColor = MaterialTheme.colorScheme.primary,
                        glyph = when (account.type) {
                            app.paisa.core.AccountType.CASH -> "₹"
                            app.paisa.core.AccountType.WALLET -> "◈"
                            app.paisa.core.AccountType.CREDIT_CARD -> "▤"
                            else -> "▦"
                        },
                        title = account.name + if (account.archived) " (archived)" else "",
                        subtitle = listOfNotNull(
                            account.type.name.lowercase().replace('_', ' '),
                            account.last4?.let { "····$it" }
                        ).joinToString(" · "),
                        amount = Fmt.money(data.balance(account.id)),
                        onClick = { onEditAccount(account.id) }
                    )
                }
            }
        }

        if (data.rules.isNotEmpty()) {
            item {
                SectionCard(title = "Learned categories") {
                    data.rules.sortedByDescending { it.hits }.forEach { rule ->
                        val category = data.category(rule.categoryId)
                        EntryRow(
                            dotColor = category?.color?.toComposeColor() ?: MaterialTheme.colorScheme.primary,
                            glyph = "◆",
                            title = rule.match,
                            subtitle = listOfNotNull(
                                category?.name ?: "No category",
                                if (rule.hits > 1) "used ${rule.hits} times" else null
                            ).joinToString(" · "),
                            amount = "",
                            trailing = { TextButton(onClick = { onForgetRule(rule.id) }) { Text("Forget") } }
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Backup") {
                Text(
                    "The backup file is the same format the Paisa web app uses, so it opens in either.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export") }
                    OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${data.transactions.size} entries · ${data.debts.size} debts · ${data.accounts.size} accounts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
