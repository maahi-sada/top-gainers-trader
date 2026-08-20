package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardCycleTest {

    /** Statement closes on the 18th, bill due on the 7th of the next month. */
    private val card = Account(
        "acc_card", "HDFC Regalia", AccountType.CREDIT_CARD,
        creditLimit = Money.ofRupees(2_00_000.0), statementDay = 18, dueDay = 7, last4 = "5678"
    )

    private var seq = 0
    private fun spend(rupees: Double, date: LocalDate, account: String = card.id) = Txn(
        id = "t${seq++}", type = TxnType.EXPENSE, amount = Money.ofRupees(rupees),
        date = date, accountId = account
    )

    @Test fun `short months pull the day back`() {
        assertEquals(LocalDate.of(2026, 2, 28), CardCycle.dayIn(2026, 2, 31))
        assertEquals(LocalDate.of(2024, 2, 29), CardCycle.dayIn(2024, 2, 31))
        assertEquals(LocalDate.of(2026, 4, 30), CardCycle.dayIn(2026, 4, 31))
    }

    @Test fun `the open cycle runs from the day after the last statement`() {
        val cycle = CardCycle.openCycle(18, LocalDate.of(2026, 8, 20))
        assertEquals(LocalDate.of(2026, 8, 19), cycle.start)
        assertEquals(LocalDate.of(2026, 9, 18), cycle.end)

        // on the statement day itself, that day still closes the old cycle
        val onStatementDay = CardCycle.openCycle(18, LocalDate.of(2026, 8, 18))
        assertEquals(LocalDate.of(2026, 8, 19), onStatementDay.start)
    }

    @Test fun `the closed cycle is the month before it`() {
        val cycle = CardCycle.closedCycle(18, LocalDate.of(2026, 8, 20))
        assertEquals(LocalDate.of(2026, 7, 19), cycle.start)
        assertEquals(LocalDate.of(2026, 8, 18), cycle.end)
    }

    @Test fun `the bill is due the next time that day comes round`() {
        assertEquals(LocalDate.of(2026, 9, 7), CardCycle.dueAfter(7, LocalDate.of(2026, 8, 18)))
        // a due day after the statement day falls in the same month
        assertEquals(LocalDate.of(2026, 8, 25), CardCycle.dueAfter(25, LocalDate.of(2026, 8, 18)))
    }

    @Test fun `billed and unbilled land in the right cycle`() {
        val today = LocalDate.of(2026, 8, 20)
        val txns = listOf(
            spend(4_000.0, LocalDate.of(2026, 7, 25)),   // closed cycle
            spend(6_000.0, LocalDate.of(2026, 8, 18)),   // closed cycle, on the statement date
            spend(1_500.0, LocalDate.of(2026, 8, 19)),   // open cycle, first day
            spend(2_500.0, LocalDate.of(2026, 8, 20))    // open cycle
        )
        val status = CardCycle.status(card, txns, today)
        assertEquals(Money.ofRupees(10_000.0), status.billed)
        assertEquals(Money.ofRupees(4_000.0), status.unbilled)
        assertEquals(Money.ofRupees(14_000.0), status.outstanding)
        assertEquals(LocalDate.of(2026, 8, 18), status.lastStatement)
        assertEquals(LocalDate.of(2026, 9, 7), status.dueDate)
        assertEquals(18L, status.daysToDue)
        assertFalse(status.overdue)
    }

    @Test fun `paying the bill reduces what is outstanding`() {
        val today = LocalDate.of(2026, 8, 20)
        val payment = Txn(
            id = "pay", type = TxnType.TRANSFER, amount = Money.ofRupees(10_000.0),
            date = LocalDate.of(2026, 8, 19), accountId = "acc_bank", toAccountId = card.id
        )
        val txns = listOf(spend(14_000.0, LocalDate.of(2026, 8, 10)), payment)
        val status = CardCycle.status(card, txns, today)
        assertEquals(Money.ofRupees(4_000.0), status.outstanding)
        // a payment is a transfer, so it never counts as spending in a cycle
        assertEquals(Money.ofRupees(14_000.0), status.billed)
        assertEquals(0L, status.unbilled)
    }

    @Test fun `utilisation and headroom track the limit`() {
        val txns = listOf(spend(50_000.0, LocalDate.of(2026, 8, 10)))
        val status = CardCycle.status(card, txns, LocalDate.of(2026, 8, 20))
        assertEquals(0.25, status.utilisation, 0.0001)
        assertEquals(Money.ofRupees(1_50_000.0), status.available)
    }

    @Test fun `an unpaid bill past its date shows as overdue`() {
        val today = LocalDate.of(2026, 9, 10)          // due date was the 7th
        val txns = listOf(spend(9_000.0, LocalDate.of(2026, 8, 10)))
        val status = CardCycle.status(card, txns, today)
        assertTrue(status.daysToDue!! < 0)
        assertTrue(status.overdue)
    }

    @Test fun `a card with no limit set reports no utilisation`() {
        val noLimit = card.copy(creditLimit = 0)
        val status = CardCycle.status(noLimit, listOf(spend(5_000.0, LocalDate.of(2026, 8, 10))), LocalDate.of(2026, 8, 20))
        assertEquals(0.0, status.utilisation)
        assertEquals(0L, status.available)
    }
}
