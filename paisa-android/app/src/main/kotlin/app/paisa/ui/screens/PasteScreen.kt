package app.paisa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import app.paisa.core.MessageParser
import app.paisa.core.Money
import app.paisa.ui.Fmt
import app.paisa.ui.SectionCard
import app.paisa.ui.expenseColor
import app.paisa.ui.incomeColor

/**
 * Typing a message in by hand — for a bank Paisa cannot read automatically, or
 * one forwarded from someone else. Shows what it will become before it is kept.
 */
@Composable
fun PasteScreen(onCapture: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val income = incomeColor()
    val expense = expenseColor()

    val previews = remember(text) {
        if (text.isBlank()) emptyList() else MessageParser.split(text).map { MessageParser.parse(it) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text("Paste a message", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(1.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Bank or UPI message") },
            placeholder = { Text("Rs.640.50 debited from A/c XX1234 on 19-08-26 to VPA swiggy@icici") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "One message, or several — one per line or separated by blank lines.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )

        if (previews.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionCard(title = "${previews.size} read") {
                previews.forEach { parsed ->
                    if (!parsed.ok) {
                        Text(
                            "Skipped — ${parsed.why}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                Money.formatSigned(parsed.amount ?: 0, parsed.type?.sign ?: 0),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if ((parsed.type?.sign ?: 0) > 0) income else expense
                            )
                            Spacer(Modifier.fillMaxWidth(0.05f))
                            Text(
                                listOfNotNull(parsed.counterparty, parsed.date?.let { Fmt.shortDate(it) })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onCapture(text) },
            enabled = previews.any { it.ok },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Capture") }
    }
}
