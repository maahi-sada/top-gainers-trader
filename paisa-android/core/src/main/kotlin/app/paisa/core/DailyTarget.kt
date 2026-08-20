package app.paisa.core

import java.time.LocalDate

data class DayProgress(
    val date: LocalDate,
    val earned: Paise,
    val target: Paise
) {
    val met: Boolean get() = target > 0 && earned >= target
    val shortfall: Paise get() = (target - earned).coerceAtLeast(0)
    val surplus: Paise get() = (earned - target).coerceAtLeast(0)
    /** 0.0 to 1.0 for a progress ring; 1.0 once the target is met. */
    val fraction: Double get() = if (target <= 0) 0.0 else (earned.toDouble() / target.toDouble()).coerceIn(0.0, 1.0)
}

data class MonthPace(
    val earned: Paise,
    /** What the target implies you should have earned by today. */
    val expected: Paise,
    val daysElapsed: Int,
    val daysRemaining: Int,
    val monthTarget: Paise
) {
    val ahead: Boolean get() = earned >= expected
    val gap: Paise get() = earned - expected
    /** What each remaining day has to bring to finish the month on target. */
    val requiredPerRemainingDay: Paise
        get() {
            val left = monthTarget - earned
            if (left <= 0) return 0
            if (daysRemaining <= 0) return left
            return left / daysRemaining
        }
}

/**
 * Daily earning targets: what came in today against what you set, how many days
 * in a row you have hit it, and whether the month is on pace.
 */
object DailyTarget {

    /** Money genuinely earned on a day. Borrowing and collected loans do not count. */
    fun earnedOn(date: LocalDate, txns: List<Txn>): Paise =
        Ledger.summary(txns, date, date).income

    fun progress(date: LocalDate, target: Paise, txns: List<Txn>): DayProgress =
        DayProgress(date, earnedOn(date, txns), target)

    /**
     * Days in a row the target was met, counting back from today.
     *
     * Today only breaks a streak once it is over: if nothing has come in yet
     * today, the count runs from yesterday instead of resetting to zero.
     */
    fun streak(target: Paise, txns: List<Txn>, today: LocalDate, maxLookBack: Int = 400): Int {
        if (target <= 0) return 0
        val earnedByDate = HashMap<LocalDate, Long>()
        for (t in txns) {
            if (t.type == TxnType.INCOME) earnedByDate[t.date] = (earnedByDate[t.date] ?: 0L) + t.amount
            if (t.type == TxnType.COLLECT && t.interest > 0) earnedByDate[t.date] = (earnedByDate[t.date] ?: 0L) + t.interest
        }

        var count = 0
        var cursor = today
        if ((earnedByDate[today] ?: 0L) < target) cursor = today.minusDays(1)

        var guard = 0
        while (guard < maxLookBack) {
            if ((earnedByDate[cursor] ?: 0L) < target) break
            count++
            cursor = cursor.minusDays(1)
            guard++
        }
        return count
    }

    /** How the month is going against the daily target, on a pay-cycle month. */
    fun monthPace(
        target: Paise,
        txns: List<Txn>,
        today: LocalDate,
        monthStartDay: Int = 1
    ): MonthPace {
        val range = Ledger.monthRange(today, monthStartDay)
        val earned = Ledger.summary(txns, range.start, range.endInclusive).income
        val totalDays = (range.endInclusive.toEpochDay() - range.start.toEpochDay() + 1).toInt()
        val elapsed = (today.toEpochDay() - range.start.toEpochDay() + 1).toInt().coerceIn(0, totalDays)
        return MonthPace(
            earned = earned,
            expected = target * elapsed,
            daysElapsed = elapsed,
            daysRemaining = (totalDays - elapsed).coerceAtLeast(0),
            monthTarget = target * totalDays
        )
    }
}
