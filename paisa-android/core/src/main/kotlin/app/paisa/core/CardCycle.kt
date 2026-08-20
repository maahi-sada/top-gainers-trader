package app.paisa.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A billing period, both ends inclusive. */
data class Cycle(val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

data class CardStatus(
    val account: Account,
    /** Everything owed on the card right now, billed and unbilled together. */
    val outstanding: Paise,
    /** The period still running: spending here appears on the next statement. */
    val openCycle: Cycle,
    val unbilled: Paise,
    /** The period that has closed but may not be paid yet. */
    val lastStatement: LocalDate?,
    val billed: Paise,
    val dueDate: LocalDate?,
    val daysToDue: Long?,
    val creditLimit: Paise,
    val available: Paise,
    /** 0.0 to 1.0 of the limit in use; 0 when no limit is set. */
    val utilisation: Double
) {
    val overdue: Boolean get() = daysToDue != null && daysToDue < 0 && outstanding > 0
    val dueSoon: Boolean get() = daysToDue != null && daysToDue in 0..5 && outstanding > 0
}

/**
 * Credit card billing maths. A card statement closes on [Account.statementDay]
 * and the bill falls due on the next [Account.dueDay] after that — which is
 * usually in the following month.
 */
object CardCycle {

    /** The given day of that month, pulled back if the month is too short. */
    fun dayIn(year: Int, month: Int, day: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        return first.withDayOfMonth(day.coerceIn(1, first.lengthOfMonth()))
    }

    /** The most recent statement date on or before [ref]. */
    fun statementOnOrBefore(statementDay: Int, ref: LocalDate): LocalDate {
        val thisMonth = dayIn(ref.year, ref.monthValue, statementDay)
        if (!thisMonth.isAfter(ref)) return thisMonth
        val previous = ref.minusMonths(1)
        return dayIn(previous.year, previous.monthValue, statementDay)
    }

    /** The next statement date strictly after [ref]. */
    fun statementAfter(statementDay: Int, ref: LocalDate): LocalDate {
        val thisMonth = dayIn(ref.year, ref.monthValue, statementDay)
        if (thisMonth.isAfter(ref)) return thisMonth
        val next = ref.plusMonths(1)
        return dayIn(next.year, next.monthValue, statementDay)
    }

    /** The next time that day of the month comes round, strictly after [ref]. */
    fun dueAfter(dueDay: Int, ref: LocalDate): LocalDate {
        val thisMonth = dayIn(ref.year, ref.monthValue, dueDay)
        if (thisMonth.isAfter(ref)) return thisMonth
        val next = ref.plusMonths(1)
        return dayIn(next.year, next.monthValue, dueDay)
    }

    /** The period still open on [today]: the day after the last statement, up to the next one. */
    fun openCycle(statementDay: Int, today: LocalDate): Cycle =
        Cycle(statementOnOrBefore(statementDay, today).plusDays(1), statementAfter(statementDay, today))

    /** The period that closed most recently. */
    fun closedCycle(statementDay: Int, today: LocalDate): Cycle {
        val end = statementOnOrBefore(statementDay, today)
        val previous = statementOnOrBefore(statementDay, end.minusDays(1))
        return Cycle(previous.plusDays(1), end)
    }

    /** Spending charged to this card inside a period. Card payments are transfers, so they are not spending. */
    fun spendIn(account: Account, txns: List<Txn>, cycle: Cycle): Paise =
        txns.filter { it.accountId == account.id && it.type == TxnType.EXPENSE && it.date in cycle }
            .sumOf { it.amount }

    fun status(account: Account, txns: List<Txn>, today: LocalDate): CardStatus {
        val outstanding = Ledger.cardOutstanding(account, txns)
        val open = openCycle(account.statementDay, today)
        val closed = closedCycle(account.statementDay, today)
        val lastStatement = statementOnOrBefore(account.statementDay, today)
        val dueDate = dueAfter(account.dueDay, lastStatement)

        val available = if (account.creditLimit > 0) (account.creditLimit - outstanding).coerceAtLeast(0) else 0
        val utilisation = if (account.creditLimit > 0) {
            (outstanding.toDouble() / account.creditLimit.toDouble()).coerceIn(0.0, 1.0)
        } else 0.0

        return CardStatus(
            account = account,
            outstanding = outstanding,
            openCycle = open,
            unbilled = spendIn(account, txns, open),
            lastStatement = lastStatement,
            billed = spendIn(account, txns, closed),
            dueDate = dueDate,
            daysToDue = ChronoUnit.DAYS.between(today, dueDate),
            creditLimit = account.creditLimit,
            available = available,
            utilisation = utilisation
        )
    }
}
