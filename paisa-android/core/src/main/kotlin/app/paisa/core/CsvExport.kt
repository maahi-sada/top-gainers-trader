package app.paisa.core

import java.time.Instant
import java.time.ZoneId

/**
 * The whole ledger as a spreadsheet, one row per movement, every field the app
 * holds. This is the audit trail: if a figure anywhere looks wrong, the row it
 * came from is here with the bank's own words alongside it.
 */
object CsvExport {

    private val columns = listOf(
        "Date", "Time", "Type", "Direction", "Amount (INR)", "Interest (INR)",
        "Category", "Account", "To account", "Person", "Merchant", "UPI handle",
        "Bank", "Method", "Account digits", "Reference", "Balance after (INR)",
        "Note", "Source", "Captured at", "Original message"
    )

    fun ledger(data: AppData, zone: ZoneId = ZoneId.systemDefault()): String {
        val rows = data.transactions
            .sortedWith(compareBy<Txn> { it.date }.thenBy { it.time ?: java.time.LocalTime.MIDNIGHT })
            .map { txn -> row(data, txn, zone) }

        return (listOf(columns) + rows).joinToString("\n") { cells ->
            cells.joinToString(",") { escape(it) }
        }
    }

    private fun row(data: AppData, txn: Txn, zone: ZoneId): List<String> {
        val direction = when {
            txn.type.sign > 0 -> "in"
            txn.type.sign < 0 -> "out"
            else -> "neutral"
        }
        val capturedAt = txn.capturedAtMillis
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().toString() }
            .orEmpty()

        return listOf(
            txn.date.toString(),
            txn.time?.toString().orEmpty(),
            txn.type.name.lowercase(),
            direction,
            rupees(txn.amount),
            if (txn.interest > 0) rupees(txn.interest) else "",
            data.category(txn.categoryId)?.name.orEmpty(),
            data.account(txn.accountId)?.name.orEmpty(),
            data.account(txn.toAccountId)?.name.orEmpty(),
            data.debt(txn.debtId)?.person.orEmpty(),
            txn.merchant.orEmpty(),
            txn.vpa.orEmpty(),
            txn.bank.orEmpty(),
            txn.method.orEmpty(),
            txn.accountTail.orEmpty(),
            txn.reference.orEmpty(),
            txn.balanceAfter?.let { rupees(it) }.orEmpty(),
            txn.note,
            txn.source.name.lowercase(),
            capturedAt,
            txn.rawMessage.orEmpty()
        )
    }

    private fun rupees(paise: Paise): String = Money.toRupees(paise).toPlainString()

    /** A bank narration is full of commas and quotes; both have to survive. */
    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"").replace("\r\n", " ").replace("\n", " ") + "\""
    }
}
