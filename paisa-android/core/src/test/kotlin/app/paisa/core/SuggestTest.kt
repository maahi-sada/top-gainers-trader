package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestTest {

    private val today = LocalDate.of(2026, 8, 20)
    private val bank = Account("acc_bank", "HDFC Savings", AccountType.BANK, last4 = "1234")
    private val card = Account("acc_card", "HDFC Regalia", AccountType.CREDIT_CARD, last4 = "5678", statementDay = 18, dueDay = 7)
    private val cash = Account("acc_cash", "Cash", AccountType.CASH)
    private val accounts = listOf(cash, bank, card)

    private val food = Category("cat_food", "Food & Dining", CategoryKind.EXPENSE, 1)
    private val shopping = Category("cat_shop", "Shopping", CategoryKind.EXPENSE, 2)
    private val salary = Category("cat_salary", "Salary", CategoryKind.INCOME, 3)
    private val categories = listOf(food, shopping, salary)

    @Test fun `account digits route a bank message`() {
        val parsed = MessageParser.parse("Rs.640.50 debited from A/c XX1234 on 19-08-26 to VPA swiggy@icici")
        val s = Suggest.forMessage(parsed, emptyList(), accounts, categories, today)
        assertEquals(bank.id, s.accountId)
        assertTrue(s.routedByTail)
    }

    @Test fun `a card message goes to the card`() {
        val parsed = MessageParser.parse("Rs 2,499.00 spent on Card XX5678 at AMAZON on 14-08-2026")
        val s = Suggest.forMessage(parsed, emptyList(), accounts, categories, today)
        assertEquals(card.id, s.accountId)
        assertTrue(s.routedByTail)
    }

    @Test fun `a card and an account sharing digits are told apart`() {
        val twins = listOf(
            bank.copy(last4 = "9999"),
            card.copy(last4 = "9999")
        )
        val cardMsg = MessageParser.parse("Rs 500 spent on Card XX9999 at BIGBASKET on 14-08-2026")
        assertEquals("acc_card", Suggest.forMessage(cardMsg, emptyList(), twins, categories, today).accountId)

        val bankMsg = MessageParser.parse("Rs 500 debited from A/c XX9999 on 14-08-2026 to VPA x@ybl")
        assertEquals("acc_bank", Suggest.forMessage(bankMsg, emptyList(), twins, categories, today).accountId)
    }

    @Test fun `an unknown card falls back to the only card there is`() {
        val parsed = MessageParser.parse("Rs 800 spent on your Credit Card at DOMINOS on 14-08-2026")
        val s = Suggest.forMessage(parsed, emptyList(), accounts, categories, today)
        assertEquals(card.id, s.accountId)
        assertFalse(s.routedByTail)
    }

    @Test fun `a learned rule fills in the category`() {
        val rules = listOf(Rule("r1", "swiggy@icici", food.id, bank.id, hits = 3))
        val parsed = MessageParser.parse("Rs.640.50 debited from A/c XX1234 on 19-08-26 to VPA swiggy@icici")
        val s = Suggest.forMessage(parsed, rules, accounts, categories, today)
        assertEquals(food.id, s.categoryId)
        assertTrue(s.matchedRule)
    }

    @Test fun `the most specific rule wins`() {
        val rules = listOf(
            Rule("r1", "amazon", shopping.id, null),
            Rule("r2", "amazon fresh", food.id, null)
        )
        val parsed = MessageParser.parse("Rs 500 spent on Card XX5678 at AMAZON FRESH on 14-08-2026")
        assertEquals(food.id, Suggest.forMessage(parsed, rules, accounts, categories, today).categoryId)
    }

    @Test fun `with no rule it falls back to the first category of the right kind`() {
        val expense = Suggest.forMessage(
            MessageParser.parse("Rs 500 debited from A/c XX1234 on 14-08-2026 to SOMEONE NEW"),
            emptyList(), accounts, categories, today
        )
        assertEquals(food.id, expense.categoryId)
        assertEquals(TxnType.EXPENSE, expense.type)

        val income = Suggest.forMessage(
            MessageParser.parse("Rs 500 credited to A/c XX1234 on 14-08-2026 from EMPLOYER"),
            emptyList(), accounts, categories, today
        )
        assertEquals(salary.id, income.categoryId)
        assertEquals(TxnType.INCOME, income.type)
    }

    @Test fun `a message with no date is dated today`() {
        val parsed = MessageParser.parse("Rs.320 debited from A/c XX1234 to VPA blinkit@ybl")
        assertNull(parsed.date)
        assertEquals(today, Suggest.forMessage(parsed, emptyList(), accounts, categories, today).date)
    }

    @Test fun `archived accounts are never suggested`() {
        val onlyArchived = listOf(bank.copy(archived = true), cash.copy(archived = true))
        val parsed = MessageParser.parse("Rs.320 debited from A/c XX1234 to VPA blinkit@ybl")
        assertNull(Suggest.forMessage(parsed, emptyList(), onlyArchived, categories, today).accountId)
    }
}
