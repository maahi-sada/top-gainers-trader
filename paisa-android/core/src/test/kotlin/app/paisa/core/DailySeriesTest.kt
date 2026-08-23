package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailySeriesTest {

    private val today = LocalDate.of(2026, 8, 21)
    private var seq = 0
    private fun txn(type: TxnType, rupees: Double, date: LocalDate, interest: Double = 0.0) = Txn(
        id = "t${seq++}", type = type, amount = Money.ofRupees(rupees), date = date,
        accountId = "acc", interest = Money.ofRupees(interest)
    )

    @Test fun `a day with nothing still appears, at zero`() {
        val series = DailySeries.lastDays(emptyList(), today, days = 7)
        assertEquals(7, series.size)
        assertEquals(today.minusDays(6), series.first().date, "oldest first")
        assertEquals(today, series.last().date)
        assertTrue(series.all { it.earned == 0L && it.spent == 0L })
    }

    @Test fun `earnings and spending land on their own day`() {
        val txns = listOf(
            txn(TxnType.INCOME, 4_000.0, today),
            txn(TxnType.INCOME, 1_500.0, today),
            txn(TxnType.EXPENSE, 900.0, today),
            txn(TxnType.EXPENSE, 300.0, today.minusDays(1))
        )
        val series = DailySeries.lastDays(txns, today, days = 3)
        val (dayBefore, yesterday, now) = series

        assertEquals(0L, dayBefore.earned)
        assertEquals(Money.ofRupees(300.0), yesterday.spent)
        assertEquals(Money.ofRupees(5_500.0), now.earned)
        assertEquals(Money.ofRupees(900.0), now.spent)
        assertEquals(Money.ofRupees(4_600.0), now.net)
        assertTrue(now.positive)
    }

    @Test fun `borrowing never shows up as a day's earnings`() {
        val txns = listOf(
            txn(TxnType.BORROW, 50_000.0, today),
            txn(TxnType.LEND, 20_000.0, today),
            txn(TxnType.INCOME, 3_000.0, today)
        )
        val series = DailySeries.lastDays(txns, today, days = 1)
        assertEquals(Money.ofRupees(3_000.0), series.single().earned)
        assertEquals(0L, series.single().spent)
    }

    @Test fun `interest inside a repayment does count`() {
        val txns = listOf(
            txn(TxnType.SETTLE, 10_400.0, today, interest = 400.0),
            txn(TxnType.COLLECT, 5_200.0, today, interest = 200.0)
        )
        val series = DailySeries.lastDays(txns, today, days = 1).single()
        assertEquals(Money.ofRupees(200.0), series.earned)
        assertEquals(Money.ofRupees(400.0), series.spent)
    }

    @Test fun `entries outside the window are left out`() {
        val txns = listOf(txn(TxnType.INCOME, 9_000.0, today.minusDays(30)))
        assertEquals(0L, DailySeries.lastDays(txns, today, days = 7).sumOf { it.earned })
    }

    @Test fun `peak scales the chart and averages summarise it`() {
        val txns = listOf(
            txn(TxnType.INCOME, 6_000.0, today),
            txn(TxnType.EXPENSE, 2_000.0, today),
            txn(TxnType.EXPENSE, 4_000.0, today.minusDays(1))
        )
        val series = DailySeries.lastDays(txns, today, days = 2)
        assertEquals(Money.ofRupees(6_000.0), DailySeries.peak(series))
        assertEquals(Money.ofRupees(3_000.0), DailySeries.averageEarned(series))
        assertEquals(Money.ofRupees(3_000.0), DailySeries.averageSpent(series))
        assertEquals(1, DailySeries.daysInDeficit(series), "yesterday spent with nothing coming in")
    }

    @Test fun `the categories are the ones actually used`() {
        val names = AppData.seed().categories.map { it.name }
        listOf("Home", "Maahi", "EMI Payments", "Utility Bills").forEach {
            assertTrue(names.contains(it), "missing spending category: $it")
        }
        listOf("Day Trading", "Credit Card Swiping").forEach {
            assertTrue(names.contains(it), "missing income source: $it")
        }
    }
}
