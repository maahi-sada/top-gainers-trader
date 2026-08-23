package app.paisa.core

import java.time.LocalDate
import java.time.LocalTime

/** A filled-in entry form, before it becomes stored records. */
data class EntryInput(
    val type: TxnType,
    val amount: Paise,
    val date: LocalDate,
    val accountId: String?,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val debtId: String? = null,
    /** Used when lending to or borrowing from someone not tracked yet. */
    val personName: String = "",
    val interest: Paise = 0,
    val note: String = ""
)

/**
 * Stores a filled-in form.
 *
 * Handles the three ways an entry arrives — typed fresh, edited, or confirmed
 * from the inbox — including creating a debt for a person not seen before, and
 * remembering the choice when the entry came from a captured message.
 */
fun AppData.applyEntry(
    input: EntryInput,
    editingTxnId: String? = null,
    confirmingInboxId: String? = null,
    today: LocalDate = LocalDate.now()
): AppData {
    var data = this

    /* Lending to or borrowing from someone: reuse their debt if it exists,
     * otherwise start one. */
    var debtId = input.debtId
    if (debtId == null && (input.type == TxnType.LEND || input.type == TxnType.BORROW)) {
        val direction = if (input.type == TxnType.LEND) DebtDirection.OWED_TO_ME else DebtDirection.I_OWE
        val person = input.personName.trim()
        if (person.isNotEmpty()) {
            val existing = data.debts.firstOrNull {
                it.direction == direction && it.person.equals(person, ignoreCase = true)
            }
            debtId = existing?.id ?: Ids.next("debt").also { newId ->
                data = data.withDebt(Debt(newId, direction, person))
            }
        }
    }

    val previous = editingTxnId?.let { id -> data.transactions.firstOrNull { it.id == id } }
    val inboxItem = confirmingInboxId?.let { id -> data.inbox.firstOrNull { it.id == id } }

    val parsed = inboxItem?.parsed

    val txn = Txn(
        id = previous?.id ?: Ids.next("txn"),
        type = input.type,
        amount = input.amount,
        date = input.date,
        /* Time of day from the message when there was one; for something typed
         * in today, the moment it was typed. */
        time = parsed?.time
            ?: previous?.time
            ?: if (input.date == today) LocalTime.now().withNano(0) else null,
        accountId = input.accountId,
        toAccountId = if (input.type == TxnType.TRANSFER) input.toAccountId else null,
        categoryId = if (input.type == TxnType.EXPENSE || input.type == TxnType.INCOME) input.categoryId else null,
        debtId = debtId,
        interest = if (input.type == TxnType.COLLECT || input.type == TxnType.SETTLE) input.interest else 0,
        note = input.note.trim(),

        // Everything the bank told us, kept rather than thrown away.
        reference = parsed?.reference ?: previous?.reference,
        method = parsed?.method ?: previous?.method,
        merchant = parsed?.counterparty ?: previous?.merchant,
        vpa = parsed?.vpa ?: previous?.vpa,
        bank = parsed?.bank ?: previous?.bank,
        accountTail = parsed?.accountTail ?: previous?.accountTail,
        balanceAfter = parsed?.balance ?: previous?.balanceAfter,
        rawMessage = parsed?.raw?.ifBlank { null } ?: previous?.rawMessage,
        capturedAtMillis = previous?.capturedAtMillis ?: System.currentTimeMillis(),

        fingerprint = inboxItem?.fingerprint ?: previous?.fingerprint,
        source = inboxItem?.source ?: previous?.source ?: CaptureSource.MANUAL
    )

    data = if (previous != null) data.replacingTransaction(txn) else data.withTransaction(txn)

    if (inboxItem != null) {
        data = data
            .learning(inboxItem.parsed.vpa ?: inboxItem.parsed.counterparty, txn.categoryId, txn.accountId)
            .rememberingTail(inboxItem.parsed.accountTail, txn.accountId)
            .discardInbox(inboxItem.id)
    }

    return data
}
