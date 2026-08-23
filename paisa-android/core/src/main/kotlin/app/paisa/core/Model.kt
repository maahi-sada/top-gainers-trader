package app.paisa.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * How money moved. Debt movements are deliberately separate from income and
 * expense: borrowing is not earning, and repaying a loan is not spending.
 */
enum class TxnType {
    EXPENSE,    // money out, counts as spending
    INCOME,     // money in, counts as earnings
    TRANSFER,   // between my own accounts (a card payment is one of these)
    LEND,       // money out to a person -> they owe me
    COLLECT,    // money back from that person -> reduces what they owe
    BORROW,     // money in from a person -> I owe them
    SETTLE;     // money out to that person -> reduces what I owe

    val isInflow: Boolean get() = this == INCOME || this == COLLECT || this == BORROW
    val isOutflow: Boolean get() = this == EXPENSE || this == LEND || this == SETTLE

    /** +1 money in, -1 money out, 0 neutral — for signing amounts in lists. */
    val sign: Int get() = when {
        isInflow -> 1
        isOutflow -> -1
        else -> 0
    }
}

enum class AccountType { CASH, BANK, WALLET, CREDIT_CARD }

enum class DebtDirection {
    OWED_TO_ME,   // I lent it out
    I_OWE         // I borrowed it
}

enum class CategoryKind { EXPENSE, INCOME }

enum class CaptureSource { MANUAL, SMS, EMAIL, RECURRING, IMPORT }

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val openingBalance: Paise = 0,
    /** Credit cards only. */
    val creditLimit: Paise = 0,
    /** Day of month the statement is generated. Credit cards only. */
    val statementDay: Int = 1,
    /** Day of month the bill is due. Credit cards only. */
    val dueDay: Int = 1,
    /** Last digits as they appear in bank messages, used to route captures. */
    val last4: String? = null,
    /** The date on the most recent statement Paisa read for this card. */
    val lastStatementDate: LocalDate? = null,
    /** What that statement said was owed in full. */
    val lastStatementDue: Paise = 0,
    /** What that statement said was the least you could pay. */
    val lastMinimumDue: Paise = 0,
    /** Where the limit and dates came from: "HDFC statement of 18 Aug 2026". */
    val detailsFrom: String? = null,
    val archived: Boolean = false
)

data class Category(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val color: Int,
    val archived: Boolean = false
)

data class Txn(
    val id: String,
    val type: TxnType,
    val amount: Paise,
    val date: LocalDate,
    /** Time of day it happened, when the bank said so or the message arrived. */
    val time: LocalTime? = null,
    val accountId: String?,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val debtId: String? = null,
    /** The slice of a COLLECT/SETTLE that is interest, not principal. */
    val interest: Paise = 0,
    val note: String = "",

    // ---- the full record of a captured transaction ----
    /** Bank reference, UTR or RRN — the number to quote in a dispute. */
    val reference: String? = null,
    /** How it moved: upi, card, atm, netbanking. */
    val method: String? = null,
    /** The shop or person exactly as the bank named them. */
    val merchant: String? = null,
    /** The counterparty's UPI handle, when there was one. */
    val vpa: String? = null,
    /** Which bank or wallet sent the alert. */
    val bank: String? = null,
    /** Last digits of the account or card the bank quoted. */
    val accountTail: String? = null,
    /** Balance the bank reported straight after, for reconciliation. */
    val balanceAfter: Paise? = null,
    /** The original message, kept verbatim so any figure can be checked. */
    val rawMessage: String? = null,
    /** When Paisa recorded it, as opposed to when it happened. */
    val capturedAtMillis: Long? = null,

    /** Identity of the message or schedule this came from, for de-duplication. */
    val fingerprint: String? = null,
    val source: CaptureSource = CaptureSource.MANUAL
) {
    /** Principal moved by a debt repayment: the part that reduces the balance. */
    val principal: Paise get() = (amount - interest).coerceAtLeast(0)

    /** Date and time together, falling back to midnight when no time is known. */
    val at: LocalDateTime get() = LocalDateTime.of(date, time ?: LocalTime.MIDNIGHT)
}

data class Debt(
    val id: String,
    val direction: DebtDirection,
    val person: String,
    val note: String = "",
    val dueDate: LocalDate? = null,
    val archived: Boolean = false
)

/** Remembers that a counterparty belongs in a category, so it files itself next time. */
data class Rule(
    val id: String,
    val match: String,
    val categoryId: String?,
    val accountId: String?,
    val hits: Int = 0
)

enum class Frequency { DAILY, WEEKLY, MONTHLY }

data class Recurring(
    val id: String,
    val label: String,
    val type: TxnType,
    val amount: Paise,
    val categoryId: String?,
    val accountId: String?,
    val note: String = "",
    val frequency: Frequency = Frequency.MONTHLY,
    /** Day of month for MONTHLY, day of week (1=Monday) for WEEKLY, ignored for DAILY. */
    val day: Int = 1,
    val nextDate: LocalDate,
    val autoPost: Boolean = false,
    val paused: Boolean = false
)

/** A capture waiting to be confirmed. */
data class InboxItem(
    val id: String,
    val parsed: ParsedMessage,
    val source: CaptureSource,
    val receivedAtMillis: Long,
    val fingerprint: String
)

/** What the user wants to earn, and what they allow themselves to spend. */
data class Targets(
    val dailyEarning: Paise = 0,
    val monthlyBudget: Paise = 0
)
