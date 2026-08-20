package app.paisa.ui

import androidx.compose.ui.graphics.Color
import app.paisa.core.Money
import app.paisa.core.Paise
import app.paisa.core.TxnType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("en-IN"))
private val fullDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("en-IN"))
private val weekdayDate = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("en-IN"))

object Fmt {
    fun money(paise: Paise): String = Money.format(paise)
    fun moneyShort(paise: Paise): String = Money.formatShort(paise)
    fun signed(paise: Paise, sign: Int): String = Money.formatSigned(paise, sign)

    fun date(date: LocalDate): String = date.format(fullDate)
    fun shortDate(date: LocalDate): String = date.format(dayMonth)

    /** "Today", "Yesterday", or a weekday — used for ledger day headings. */
    fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(weekdayDate)
    }

    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    /** "in 6 days", "3 days overdue", "today". */
    fun dueLabel(due: LocalDate, today: LocalDate = LocalDate.now()): String {
        val days = daysBetween(today, due)
        return when {
            days == 0L -> "due today"
            days == 1L -> "due tomorrow"
            days > 1 -> "due in $days days"
            days == -1L -> "1 day overdue"
            else -> "${-days} days overdue"
        }
    }

    fun percent(fraction: Double): String = "${(fraction * 100).toInt()}%"
}

fun Int.toComposeColor(): Color = Color(this)

/** How a row's amount should read: green with a plus, red with a minus, or plain. */
fun signOf(type: TxnType): Int = type.sign
