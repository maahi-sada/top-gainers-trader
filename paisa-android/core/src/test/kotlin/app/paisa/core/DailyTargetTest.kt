package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyTargetTest {

    private val target = Money.ofRupees(3_000.0)
    private var seq = 0
    private fun earn(rupees: Double, date: LocalDate) = Txn(
        id = "t${seq++}", type = TxnType.INCOME, amount = Money.ofRupees(rupees),
        date = date, accountId = "acc", categoryId = "cat_salary"
    )
    private fun spend(rupees: Double, date: LocalDate) = Txn(
        id = "t${seq++}", type = TxnType.EXPENSE, amount = Money.ofRupees(rupees),
        date = date, accountId = "acc"
    )

    @Test fun `progress counts earnings only`() {
        val today = LocalDate.of(2026, 8, 20)
        val txns = listOf(earn(2_000.0, today), spend(9_000.0, today), earn(500.0, today))
        val progress = DailyTarget.progress(today, target, txns)
        assertEquals(Money.ofRupees(2_500.0), progress.earned)
        assertEquals(Money.ofRupees(500.0), progress.shortfall)
        assertFalse(progress.met)
        assertEquals(2500.0 / 3000.0, progress.fraction, 0.0001)
    }

    @Test fun `meeting the target caps the ring and shows the surplus`() {
        val today = LocalDate.of(2026, 8, 20)
        val progress = DailyTarget.progress(today, target, listOf(earn(4_000.0, today)))
        assertTrue(progress.met)
        assertEquals(Money.ofRupees(1_000.0), progress.surplus)
        assertEquals(1.0, progress.fraction)
    }

    @Test fun `a streak counts back through days that met the target`() {
        val today = LocalDate.of(2026, 8, 20)
        val txns = listOf(
            earn(3_500.0, today),
            earn(3_000.0, today.minusDays(1)),
            earn(9_000.0, today.minusDays(2)),
            earn(100.0, today.minusDays(3))       // breaks it
        )
        assertEquals(3, DailyTarget.streak(target, txns, today))
    }

    @Test fun `a quiet morning does not break yesterday's streak`() {
        val today = LocalDate.of(2026, 8, 20)
        val txns = listOf(
            earn(3_000.0, today.minusDays(1)),
            earn(3_000.0, today.minusDays(2))
        )
        // nothing earned yet today, so the count runs from yesterday
        assertEquals(2, DailyTarget.streak(target, txns, today))
    }

    @Test fun `no target means no streak`() {
        val today = LocalDate.of(2026, 8, 20)
        assertEquals(0, DailyTarget.streak(0, listOf(earn(5_000.0, today)), today))
    }

    @Test fun `month pace compares earnings against the days gone by`() {
        val today = LocalDate.of(2026, 8, 10)        // 10th day of a 1st-start month
        val txns = listOf(earn(20_000.0, LocalDate.of(2026, 8, 3)))
        val pace = DailyTarget.monthPace(target, txns, today, monthStartDay = 1)

        assertEquals(10, pace.daysElapsed)
        assertEquals(21, pace.daysRemaining)               // August has 31 days
        assertEquals(Money.ofRupees(30_000.0), pace.expected)
        assertEquals(Money.ofRupees(20_000.0), pace.earned)
        assertFalse(pace.ahead)
        assertEquals(Money.ofRupees(-10_000.0), pace.gap)
        assertEquals(Money.ofRupees(3_000.0 * 31), pace.monthTarget)
        // ₹93,000 goal less ₹20,000 earned, spread over the 21 days left
        assertEquals((Money.ofRupees(93_000.0) - Money.ofRupees(20_000.0)) / 21, pace.requiredPerRemainingDay)
    }

    @Test fun `nothing more is required once the month goal is met`() {
        val today = LocalDate.of(2026, 8, 10)
        val txns = listOf(earn(1_00_000.0, LocalDate.of(2026, 8, 2)))
        assertEquals(0L, DailyTarget.monthPace(target, txns, today).requiredPerRemainingDay)
    }
}
