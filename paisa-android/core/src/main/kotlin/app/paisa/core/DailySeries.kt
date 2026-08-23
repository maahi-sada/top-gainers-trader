package app.paisa.core

import java.time.LocalDate

/** What came in and what went out on one day. */
data class DayTotals(
    val date: LocalDate,
    val earned: Paise,
    val spent: Paise
) {
    val net: Paise get() = earned - spent
    val positive: Boolean get() = net >= 0
}

/**
 * Earnings against spending, day by day — the shape the dashboard is built on.
 * Debt movements stay out of it for the same reason they stay out of every
 * other total: borrowing is not earning.
 */
object DailySeries {

    /** The last [days] days ending today, oldest first, gaps filled with zeroes. */
    fun lastDays(transactions: List<Txn>, today: LocalDate, days: Int = 14): List<DayTotals> {
        val earned = HashMap<LocalDate, Long>()
        val spent = HashMap<LocalDate, Long>()

        for (t in transactions) {
            when (t.type) {
                TxnType.INCOME -> earned[t.date] = (earned[t.date] ?: 0L) + t.amount
                TxnType.EXPENSE -> spent[t.date] = (spent[t.date] ?: 0L) + t.amount
                TxnType.COLLECT -> if (t.interest > 0) earned[t.date] = (earned[t.date] ?: 0L) + t.interest
                TxnType.SETTLE -> if (t.interest > 0) spent[t.date] = (spent[t.date] ?: 0L) + t.interest
                else -> Unit
            }
        }

        val span = days.coerceAtLeast(1)
        return (span - 1 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            DayTotals(day, earned[day] ?: 0L, spent[day] ?: 0L)
        }
    }

    /** The largest single bar in a series, used to scale the chart. */
    fun peak(series: List<DayTotals>): Paise =
        series.maxOfOrNull { maxOf(it.earned, it.spent) } ?: 0L

    /** Averages over the window, so a run of good days is visible against a bad one. */
    fun averageEarned(series: List<DayTotals>): Paise =
        if (series.isEmpty()) 0 else series.sumOf { it.earned } / series.size

    fun averageSpent(series: List<DayTotals>): Paise =
        if (series.isEmpty()) 0 else series.sumOf { it.spent } / series.size

    /** Days in the window where more went out than came in. */
    fun daysInDeficit(series: List<DayTotals>): Int = series.count { it.net < 0 }
}
