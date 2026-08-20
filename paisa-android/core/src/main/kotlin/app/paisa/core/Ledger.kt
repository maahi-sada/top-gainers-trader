package app.paisa.core

import java.time.LocalDate

data class Summary(val income: Paise, val expense: Paise) {
    val net: Paise get() = income - expense
}

data class CategorySlice(
    val categoryId: String?,
    val label: String,
    val color: Int,
    val amount: Paise,
    val count: Int
)

/**
 * Every derived number in the app. Pure functions over the stored rows, so the
 * arithmetic can be tested without a database or a screen.
 */
object Ledger {

    /** Fallback grey for anything not filed under a category. */
    const val UNCATEGORISED_COLOR = 0xFF94A3B8.toInt()

    /**
     * What is in an account right now. For a credit card this goes negative as
     * you spend and climbs back towards zero as you pay the bill.
     */
    fun balance(account: Account, txns: List<Txn>): Paise {
        var total = account.openingBalance
        for (t in txns) {
            if (t.type == TxnType.TRANSFER) {
                if (t.accountId == account.id) total -= t.amount
                if (t.toAccountId == account.id) total += t.amount
                continue
            }
            if (t.accountId != account.id) continue
            when {
                t.type.isInflow -> total += t.amount
                t.type.isOutflow -> total -= t.amount
            }
        }
        return total
    }

    /** Cash plus bank plus wallets, minus what is sitting unpaid on cards. */
    fun totalBalance(accounts: List<Account>, txns: List<Txn>): Paise =
        accounts.sumOf { balance(it, txns) }

    /** What is owed on a credit card. Never negative, even if you overpay. */
    fun cardOutstanding(account: Account, txns: List<Txn>): Paise =
        (-balance(account, txns)).coerceAtLeast(0)

    /** Principal still open on one debt. */
    fun outstanding(debtId: String, txns: List<Txn>): Paise {
        var total = 0L
        for (t in txns) {
            if (t.debtId != debtId) continue
            when (t.type) {
                TxnType.LEND, TxnType.BORROW -> total += t.amount
                TxnType.COLLECT, TxnType.SETTLE -> total -= t.principal
                else -> Unit
            }
        }
        return total
    }

    fun receivables(debts: List<Debt>, txns: List<Txn>): Paise =
        debts.filter { it.direction == DebtDirection.OWED_TO_ME }
            .sumOf { outstanding(it.id, txns).coerceAtLeast(0) }

    fun payables(debts: List<Debt>, txns: List<Txn>): Paise =
        debts.filter { it.direction == DebtDirection.I_OWE }
            .sumOf { outstanding(it.id, txns).coerceAtLeast(0) }

    fun netWorth(accounts: List<Account>, debts: List<Debt>, txns: List<Txn>): Paise =
        totalBalance(accounts, txns) + receivables(debts, txns) - payables(debts, txns)

    private fun inRange(t: Txn, from: LocalDate?, to: LocalDate?): Boolean =
        (from == null || !t.date.isBefore(from)) && (to == null || !t.date.isAfter(to))

    /**
     * Earned and spent over a window. Debt movements are excluded on purpose —
     * borrowing is not income and lending is not spending — but the interest
     * part of a repayment is real money and does count.
     */
    fun summary(txns: List<Txn>, from: LocalDate? = null, to: LocalDate? = null): Summary {
        var income = 0L
        var expense = 0L
        for (t in txns) {
            if (!inRange(t, from, to)) continue
            when (t.type) {
                TxnType.INCOME -> income += t.amount
                TxnType.EXPENSE -> expense += t.amount
                TxnType.COLLECT -> income += t.interest
                TxnType.SETTLE -> expense += t.interest
                else -> Unit
            }
        }
        return Summary(income, expense)
    }

    /**
     * Spending (or earning) grouped by category. Debt interest has no category
     * of its own, so it lands in the interest bucket — which keeps these totals
     * equal to the headline figures from [summary].
     */
    fun byCategory(
        kind: CategoryKind,
        txns: List<Txn>,
        categories: List<Category>,
        from: LocalDate? = null,
        to: LocalDate? = null,
        interestCategoryName: String = if (kind == CategoryKind.EXPENSE) "Interest Paid" else "Interest Received"
    ): List<CategorySlice> {
        val byId = categories.associateBy { it.id }
        val wanted = if (kind == CategoryKind.EXPENSE) TxnType.EXPENSE else TxnType.INCOME
        val interestFrom = if (kind == CategoryKind.EXPENSE) TxnType.SETTLE else TxnType.COLLECT

        val amounts = LinkedHashMap<String?, Long>()
        val counts = LinkedHashMap<String?, Int>()
        val labels = LinkedHashMap<String?, String>()
        val colors = LinkedHashMap<String?, Int>()

        fun add(id: String?, label: String, color: Int, amount: Paise) {
            amounts[id] = (amounts[id] ?: 0L) + amount
            counts[id] = (counts[id] ?: 0) + 1
            labels[id] = label
            colors[id] = color
        }

        val interestCategory = categories.firstOrNull { it.name == interestCategoryName }

        for (t in txns) {
            if (!inRange(t, from, to)) continue
            if (t.type == wanted) {
                val c = t.categoryId?.let { byId[it] }
                add(c?.id, c?.name ?: "Uncategorised", c?.color ?: UNCATEGORISED_COLOR, t.amount)
            } else if (t.type == interestFrom && t.interest > 0) {
                add(
                    interestCategory?.id ?: "interest",
                    interestCategoryName,
                    interestCategory?.color ?: UNCATEGORISED_COLOR,
                    t.interest
                )
            }
        }

        return amounts.entries
            .map { CategorySlice(it.key, labels[it.key]!!, colors[it.key]!!, it.value, counts[it.key]!!) }
            .sortedByDescending { it.amount }
    }

    /**
     * The financial month containing [ref], honouring a pay-cycle start day so
     * "this month" can begin on payday rather than the 1st.
     */
    fun monthRange(ref: LocalDate, startDay: Int = 1, offset: Int = 0): ClosedRange<LocalDate> {
        val day = startDay.coerceIn(1, 28)
        var anchor = ref.withDayOfMonth(1)
        if (ref.dayOfMonth < day) anchor = anchor.minusMonths(1)
        anchor = anchor.plusMonths(offset.toLong())
        val start = anchor.withDayOfMonth(day)
        val end = start.plusMonths(1).minusDays(1)
        return start..end
    }
}
