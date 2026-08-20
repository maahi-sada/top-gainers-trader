package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDataTest {

    private val today = LocalDate.of(2026, 8, 20)

    private fun seeded(): AppData {
        val base = AppData.seed()
        val card = Account(
            "acc_card", "HDFC Regalia", AccountType.CREDIT_CARD,
            creditLimit = Money.ofRupees(2_00_000.0), statementDay = 18, dueDay = 7, last4 = "5678"
        )
        val bank = base.accounts.first { it.type == AccountType.BANK }.copy(last4 = "1234")
        return base.withAccount(card).withAccount(bank)
            .withSettings { it.copy(dailyEarningTarget = Money.ofRupees(3_000.0)) }
    }

    private val swiggySms =
        "Rs.640.50 debited from A/c XX1234 on 19-08-26 to VPA swiggy@icici (UPI Ref 412345678901)"
    private val cardSms =
        "Rs 2,499.00 spent on Card XX5678 at AMAZON on 18-08-2026. Avl Lmt Rs 1,45,000.00"

    // ---------- capture ----------

    @Test fun `a readable message waits in the inbox`() {
        val result = seeded().ingest(MessageParser.parse(swiggySms), CaptureSource.SMS, today)
        assertEquals(AppData.IngestStatus.ADDED, result.status)
        assertEquals(1, result.data.inbox.size)
        assertTrue(result.data.transactions.isEmpty(), "nothing should reach the ledger unreviewed")
    }

    @Test fun `an OTP never becomes an entry`() {
        val result = seeded().ingest(
            MessageParser.parse("Your OTP for txn of Rs.4,500 at AMAZON is 483920. Do not share it."),
            CaptureSource.SMS, today
        )
        assertEquals(AppData.IngestStatus.REJECTED, result.status)
        assertEquals("OTP message", result.reason)
        assertTrue(result.data.inbox.isEmpty())
    }

    @Test fun `the same message twice is only captured once`() {
        val first = seeded().ingest(MessageParser.parse(swiggySms), CaptureSource.SMS, today)
        val second = first.data.ingest(MessageParser.parse(swiggySms), CaptureSource.SMS, today)
        assertEquals(AppData.IngestStatus.DUPLICATE, second.status)
        assertEquals(1, second.data.inbox.size)
    }

    @Test fun `a message already logged is not captured again`() {
        val captured = seeded().ingest(MessageParser.parse(swiggySms), CaptureSource.SMS, today)
        val (logged, _) = captured.data.confirmInbox(captured.data.inbox.first().id, today)
        assertTrue(logged.inbox.isEmpty())

        val again = logged.ingest(MessageParser.parse(swiggySms), CaptureSource.SMS, today)
        assertEquals(AppData.IngestStatus.DUPLICATE, again.status)
    }

    // ---------- learning ----------

    @Test fun `confirming teaches the shop and the account`() {
        val data = seeded()
        val captured = data.ingest(MessageParser.parse(swiggySms), CaptureSource.SMS, today)
        val food = data.categories.first { it.name == "Food & Dining" }
        val bank = data.accounts.first { it.type == AccountType.BANK }

        val (after, txn) = captured.data.confirmInbox(
            captured.data.inbox.first().id, today, categoryId = food.id, accountId = bank.id
        )
        assertNotNull(txn)
        assertEquals(Money.ofRupees(640.50), txn.amount)
        assertEquals(CaptureSource.SMS, txn.source)

        val rule = after.rules.firstOrNull { it.match == "swiggy@icici" }
        assertNotNull(rule, "the shop should have been remembered")
        assertEquals(food.id, rule.categoryId)
        assertEquals(bank.id, after.settings.accountTails["1234"], "the account digits should have been remembered")
    }

    @Test fun `a learned shop logs itself once that is switched on`() {
        var data = seeded().withSettings { it.copy(autoConfirm = true) }
        val food = data.categories.first { it.name == "Food & Dining" }
        data = data.learning("swiggy@icici", food.id, data.accounts.first { it.type == AccountType.BANK }.id)

        val result = data.ingest(
            MessageParser.parse("Rs.415.00 debited from A/c XX1234 on 20-08-26 to VPA swiggy@icici (UPI Ref 999000111222)"),
            CaptureSource.SMS, today
        )
        assertEquals(AppData.IngestStatus.AUTO_LOGGED, result.status)
        assertEquals(food.id, result.txn?.categoryId)
        assertTrue(result.data.inbox.isEmpty())
        assertEquals(1, result.data.transactions.size)
    }

    @Test fun `an unknown shop still waits even with auto-confirm on`() {
        val data = seeded().withSettings { it.copy(autoConfirm = true) }
        val result = data.ingest(MessageParser.parse(swiggySms), CaptureSource.SMS, today)
        assertEquals(AppData.IngestStatus.ADDED, result.status)
    }

    // ---------- credit cards ----------

    @Test fun `a card message lands on the card and shows up as card debt`() {
        val captured = seeded().ingest(MessageParser.parse(cardSms), CaptureSource.EMAIL, today)
        val item = captured.data.inbox.first()
        val suggestion = captured.data.suggestionFor(item, today)
        assertEquals("acc_card", suggestion.accountId)

        val (after, txn) = captured.data.confirmInbox(item.id, today)
        assertEquals("acc_card", txn?.accountId)
        assertEquals(Money.ofRupees(2_499.0), after.cardDebt)
        // card debt is not money in hand, and it lowers net worth
        assertEquals(0L, after.moneyInHand)
        assertEquals(Money.ofRupees(-2_499.0), after.netWorth)
    }

    @Test fun `card status separates billed from unbilled`() {
        var data = seeded()
        data = data.withTransaction(Txn("t1", TxnType.EXPENSE, Money.ofRupees(5_000.0), LocalDate.of(2026, 8, 17), "acc_card"))
        data = data.withTransaction(Txn("t2", TxnType.EXPENSE, Money.ofRupees(2_000.0), LocalDate.of(2026, 8, 19), "acc_card"))

        val status = data.cardStatuses(today).first()
        assertEquals(Money.ofRupees(5_000.0), status.billed)
        assertEquals(Money.ofRupees(2_000.0), status.unbilled)
        assertEquals(Money.ofRupees(7_000.0), status.outstanding)
        assertEquals(LocalDate.of(2026, 9, 7), status.dueDate)
    }

    // ---------- targets ----------

    @Test fun `today's earnings count towards the daily target`() {
        var data = seeded()
        data = data.withTransaction(Txn("t1", TxnType.INCOME, Money.ofRupees(2_000.0), today, "acc_bank"))
        data = data.withTransaction(Txn("t2", TxnType.EXPENSE, Money.ofRupees(9_000.0), today, "acc_bank"))

        val progress = data.todayProgress(today)
        assertEquals(Money.ofRupees(2_000.0), progress.earned)
        assertEquals(Money.ofRupees(1_000.0), progress.shortfall)
        assertFalse(progress.met)
    }

    // ---------- repeating entries ----------

    @Test fun `a due template posts once and reschedules`() {
        val data = seeded().withRecurring(
            Recurring(
                id = "rec_rent", label = "House rent", type = TxnType.EXPENSE,
                amount = Money.ofRupees(24_000.0), categoryId = null, accountId = "acc_card",
                frequency = Frequency.MONTHLY, day = 5, nextDate = LocalDate.of(2026, 8, 5), autoPost = true
            )
        )
        val (after, posted) = data.runRecurring(today)
        assertEquals(1, posted)
        assertEquals(1, after.transactions.size)
        assertEquals(LocalDate.of(2026, 9, 5), after.recurring.first().nextDate)

        // running again the same day must not post a second time
        val (again, postedAgain) = after.runRecurring(today)
        assertEquals(0, postedAgain)
        assertEquals(1, again.transactions.size)
    }

    @Test fun `missed months are caught up`() {
        val data = seeded().withRecurring(
            Recurring(
                id = "rec_netflix", label = "Netflix", type = TxnType.EXPENSE,
                amount = Money.ofRupees(649.0), categoryId = null, accountId = "acc_card",
                frequency = Frequency.MONTHLY, day = 12, nextDate = LocalDate.of(2026, 5, 12), autoPost = true
            )
        )
        val (after, posted) = data.runRecurring(today)
        assertEquals(4, posted, "May, June, July and August were all missed")
        assertEquals(LocalDate.of(2026, 9, 12), after.recurring.first().nextDate)
    }

    @Test fun `a template without auto-post queues for review instead`() {
        val data = seeded().withRecurring(
            Recurring(
                id = "rec_gym", label = "Gym", type = TxnType.EXPENSE,
                amount = Money.ofRupees(1_500.0), categoryId = null, accountId = "acc_bank",
                frequency = Frequency.MONTHLY, day = 1, nextDate = LocalDate.of(2026, 8, 1), autoPost = false
            )
        )
        val (after, _) = data.runRecurring(today)
        assertTrue(after.transactions.isEmpty())
        assertEquals(1, after.inbox.size)
        assertEquals(CaptureSource.RECURRING, after.inbox.first().source)
    }

    @Test fun `a paused template does nothing`() {
        val data = seeded().withRecurring(
            Recurring(
                id = "rec_paused", label = "Paused", type = TxnType.EXPENSE, amount = Money.ofRupees(100.0),
                categoryId = null, accountId = "acc_bank", frequency = Frequency.MONTHLY, day = 1,
                nextDate = LocalDate.of(2026, 1, 1), autoPost = true, paused = true
            )
        )
        assertEquals(0, data.runRecurring(today).second)
    }

    // ---------- persistence ----------

    @Test fun `a full round trip through storage keeps every number`() {
        var data = seeded()
        val captured = data.ingest(MessageParser.parse(cardSms), CaptureSource.EMAIL, today)
        data = captured.data.confirmInbox(captured.data.inbox.first().id, today).first
        data = data.withDebt(Debt("d1", DebtDirection.OWED_TO_ME, "Ramesh"))
            .withTransaction(Txn("t9", TxnType.LEND, Money.ofRupees(15_000.0), today, "acc_bank", debtId = "d1"))

        val reread = AppData.decode(SnapshotCodec.encode(data.toSnapshot()))
        assertEquals(data.moneyInHand, reread.moneyInHand)
        assertEquals(data.cardDebt, reread.cardDebt)
        assertEquals(data.receivables, reread.receivables)
        assertEquals(data.netWorth, reread.netWorth)
        assertEquals(data.transactions.size, reread.transactions.size)
        assertEquals(data.rules.map { it.match }, reread.rules.map { it.match })
        assertEquals(data.settings.accountTails, reread.settings.accountTails)
    }

    @Test fun `discarding a capture leaves no trace`() {
        val captured = seeded().ingest(MessageParser.parse(swiggySms), CaptureSource.SMS, today)
        val after = captured.data.discardInbox(captured.data.inbox.first().id)
        assertTrue(after.inbox.isEmpty())
        assertTrue(after.transactions.isEmpty())
        // discarding does not blacklist it: the user can capture it again on purpose
        assertEquals(AppData.IngestStatus.ADDED, after.ingest(MessageParser.parse(swiggySms), CaptureSource.SMS, today).status)
    }

    @Test fun `a fresh install has accounts and categories ready`() {
        val seed = AppData.seed()
        assertEquals(3, seed.accounts.size)
        assertTrue(seed.categories.any { it.name == "Salary" && it.kind == CategoryKind.INCOME })
        assertTrue(seed.categories.any { it.name == "Card Fees & Charges" })
        assertNull(seed.account("nope"))
    }
}
