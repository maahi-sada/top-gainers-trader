package app.paisa.core

import java.time.LocalDate

/**
 * When a repeating entry next falls due. Weekly days follow the same convention
 * as the web app: 0 is Sunday.
 */
object Schedule {

    private fun clampDayOfMonth(date: LocalDate, day: Int): LocalDate =
        date.withDayOfMonth(day.coerceIn(1, date.lengthOfMonth()))

    /** The first occurrence strictly after [from]. */
    fun nextOccurrence(frequency: Frequency, day: Int, from: LocalDate): LocalDate = when (frequency) {
        Frequency.DAILY -> from.plusDays(1)
        Frequency.WEEKLY -> {
            /* java.time counts Monday as 1 and Sunday as 7; the stored value counts Sunday as 0. */
            val target = day.coerceIn(0, 6)
            val current = from.dayOfWeek.value % 7
            val delta = (target - current + 7) % 7
            from.plusDays(if (delta == 0L.toInt()) 7L else delta.toLong())
        }
        Frequency.MONTHLY -> {
            val dom = day.coerceIn(1, 28)
            val thisMonth = clampDayOfMonth(from, dom)
            if (thisMonth.isAfter(from)) thisMonth else clampDayOfMonth(from.plusMonths(1), dom)
        }
    }

    /** The occurrence after one that has just been posted. */
    fun advance(frequency: Frequency, day: Int, posted: LocalDate): LocalDate = when (frequency) {
        Frequency.DAILY -> posted.plusDays(1)
        Frequency.WEEKLY -> posted.plusWeeks(1)
        Frequency.MONTHLY -> clampDayOfMonth(posted.plusMonths(1), day.coerceIn(1, 28))
    }

    /** Every date a template owes, catching up anything missed while the app was closed. */
    fun due(recurring: Recurring, today: LocalDate, maxCatchUp: Int = 60): List<LocalDate> {
        if (recurring.paused || recurring.amount <= 0) return emptyList()
        val dates = mutableListOf<LocalDate>()
        var cursor = recurring.nextDate
        while (!cursor.isAfter(today) && dates.size < maxCatchUp) {
            dates += cursor
            cursor = advance(recurring.frequency, recurring.day, cursor)
        }
        return dates
    }

    /** A posted occurrence's identity, so a template never posts the same day twice. */
    fun fingerprint(recurringId: String, date: LocalDate): String = "rec:$recurringId:$date"
}
