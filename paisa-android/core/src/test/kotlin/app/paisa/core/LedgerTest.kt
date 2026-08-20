package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerTest {

    private val bank = Account("acc_bank", "HDFC", AccountType.BANK, openingBalance = 5_000_000)   // ₹50,000
    private val cash = Account("acc_cash", "Cash", AccountType.CASH, openingBalance = 0)
    private val card = Account(
        "acc_card", "Amex", AccountType.CREDIT_CARD,
        creditLimit = 20_000_000, statementDay = 18, dueDay = 7, last4 = "5678"
    )
    private val food = Category("cat_food", "Food & Dining", CategoryKind.EXPENSE, 0xFFF97316.toInt())
    private val salary = Category("cat_salary", "Salary", CategoryKind.INCOME, 0xFF22C55E.toInt())
    private val interestPaid = Category("cat_int", "Interest Paid", CategoryKind.EXPENSE, 0xFFDC2626.toInt())
    private val d = LocalDate.of(2026, 8, 15)

    private var seq = 0
    private fun txn(
        type: TxnType, rupees: Double, account: String? = bank.id,
        date: LocalDate = d, category: String? = null, debt: String? = null,
        interest: Double = 0.0, to: String? = null
    ) = Txn(
        id = "t${seq++}", type = type, amount = Money.ofRupees(rupees), date = date,
        accountId = account, toAccountId = to, categoryId = category, debtId = debt,
        interest = Money.ofRupees(interest)
    )

    @Test fun `balance follows money in and out`() {
        val txns = listOf(
            txn(TxnType.INCOME, 82_000.0, category = salary.id),
            txn(TxnType.EXPENSE, 640.50, category = food.id),
            txn(TxnType.TRANSFER, 5_000.0, account = bank.id, to = cash.id)
        )
        assertEquals(Money.ofRupees(50_000.0 + 82_000.0 - 640.50 - 5_000.0), Ledger.balance(bank, txns))
        assertEquals(Money.ofRupees(5_000.0), Ledger.balance(cash, txns))
    }

    @Test fun `borrowing is not income and repaying is not spending`() {
        val txns = listOf(
            txn(TxnType.BORROW, 40_000.0, debt = "debt_papa"),
            txn(TxnType.SETTLE, 10_500.0, debt = "debt_papa", interest = 500.0),
            txn(TxnType.LEND, 15_000.0, debt = "debt_ramesh"),
            txn(TxnType.COLLECT, 5_000.0, debt = "debt_ramesh"),
            txn(TxnType.INCOME, 82_000.0, category = salary.id),
            txn(TxnType.EXPENSE, 640.50, category = food.id)
        )
        val summary = Ledger.summary(txns)
        // only the salary, plus the ₹500 interest inside the repayment
        assertEquals(Money.ofRupees(82_000.0), summary.income)
        assertEquals(Money.ofRupees(640.50 + 500.0), summary.expense)

        val debts = listOf(
            Debt("debt_papa", DebtDirection.I_OWE, "Papa"),
            Debt("debt_ramesh", DebtDirection.OWED_TO_ME, "Ramesh")
        )
        // ₹10,500 paid, of which ₹500 was interest -> ₹10,000 came off the principal
        assertEquals(Money.ofRupees(30_000.0), Ledger.payables(debts, txns))
        assertEquals(Money.ofRupees(10_000.0), Ledger.receivables(debts, txns))

        val expectedBalance = Money.ofRupees(50_000.0 + 40_000.0 - 10_500.0 - 15_000.0 + 5_000.0 + 82_000.0 - 640.50)
        assertEquals(expectedBalance, Ledger.balance(bank, txns))
        assertEquals(
            expectedBalance + Money.ofRupees(10_000.0) - Money.ofRupees(30_000.0),
            Ledger.netWorth(listOf(bank), debts, txns)
        )
    }

    @Test fun `a credit card owes rather than holds`() {
        val txns = listOf(
            txn(TxnType.EXPENSE, 12_000.0, account = card.id, category = food.id),
            txn(TxnType.EXPENSE, 3_000.0, account = card.id, category = food.id),
            txn(TxnType.TRANSFER, 5_000.0, account = bank.id, to = card.id)   // paying the bill
        )
        assertEquals(Money.ofRupees(-10_000.0), Ledger.balance(card, txns))
        assertEquals(Money.ofRupees(10_000.0), Ledger.cardOutstanding(card, txns))
        // net worth counts the card as a liability
        assertEquals(Money.ofRupees(50_000.0 - 5_000.0 - 10_000.0), Ledger.netWorth(listOf(bank, card), emptyList(), txns))
    }

    @Test fun `overpaying a card never shows a negative debt`() {
        val txns = listOf(
            txn(TxnType.EXPENSE, 1_000.0, account = card.id),
            txn(TxnType.TRANSFER, 3_000.0, account = bank.id, to = card.id)
        )
        assertEquals(0L, Ledger.cardOutstanding(card, txns))
    }

    @Test fun `category totals reconcile with the headline figures`() {
        val txns = listOf(
            txn(TxnType.EXPENSE, 18_500.0, category = food.id),
            txn(TxnType.EXPENSE, 420.50, category = food.id),
            txn(TxnType.EXPENSE, 1_180.0, category = null),
            txn(TxnType.SETTLE, 10_500.0, debt = "debt_papa", interest = 500.0)
        )
        val slices = Ledger.byCategory(CategoryKind.EXPENSE, txns, listOf(food, interestPaid))
        assertEquals(Ledger.summary(txns).expense, slices.sumOf { it.amount })
        assertEquals("Food & Dining", slices.first().label)
        assertTrue(slices.any { it.label == "Interest Paid" && it.amount == Money.ofRupees(500.0) })
        assertTrue(slices.any { it.label == "Uncategorised" })
    }

    @Test fun `the month can start on payday`() {
        val august = Ledger.monthRange(LocalDate.of(2026, 8, 20), startDay = 5)
        assertEquals(LocalDate.of(2026, 8, 5), august.start)
        assertEquals(LocalDate.of(2026, 9, 4), august.endInclusive)

        // before the 5th we are still inside the previous pay month
        val early = Ledger.monthRange(LocalDate.of(2026, 8, 2), startDay = 5)
        assertEquals(LocalDate.of(2026, 7, 5), early.start)
    }

    @Test fun `summary respects the window`() {
        val txns = listOf(
            txn(TxnType.INCOME, 1_000.0, date = LocalDate.of(2026, 7, 31), category = salary.id),
            txn(TxnType.INCOME, 2_000.0, date = LocalDate.of(2026, 8, 1), category = salary.id),
            txn(TxnType.INCOME, 3_000.0, date = LocalDate.of(2026, 8, 31), category = salary.id),
            txn(TxnType.INCOME, 4_000.0, date = LocalDate.of(2026, 9, 1), category = salary.id)
        )
        val august = Ledger.summary(txns, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))
        assertEquals(Money.ofRupees(5_000.0), august.income)
    }
}
