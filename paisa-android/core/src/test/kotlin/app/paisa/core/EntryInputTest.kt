package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryInputTest {

    private val today = LocalDate.of(2026, 8, 20)
    private fun base(): AppData = AppData.seed()

    @Test fun `lending to someone new starts a debt for them`() {
        val data = base()
        val account = data.liveAccounts.first()
        val after = data.applyEntry(
            EntryInput(TxnType.LEND, Money.ofRupees(15_000.0), today, account.id, personName = "Ramesh")
        )

        val debt = after.debts.singleOrNull()
        assertNotNull(debt)
        assertEquals("Ramesh", debt.person)
        assertEquals(DebtDirection.OWED_TO_ME, debt.direction)
        assertEquals(Money.ofRupees(15_000.0), after.receivables)
        assertEquals(debt.id, after.transactions.single().debtId)
    }

    @Test fun `lending again to the same person reuses their debt`() {
        var data = base()
        val account = data.liveAccounts.first()
        data = data.applyEntry(EntryInput(TxnType.LEND, Money.ofRupees(5_000.0), today, account.id, personName = "Ramesh"))
        data = data.applyEntry(EntryInput(TxnType.LEND, Money.ofRupees(3_000.0), today, account.id, personName = "ramesh"))

        assertEquals(1, data.debts.size, "the same person should not appear twice")
        assertEquals(Money.ofRupees(8_000.0), data.receivables)
    }

    @Test fun `borrowing and lending to the same name stay separate`() {
        var data = base()
        val account = data.liveAccounts.first()
        data = data.applyEntry(EntryInput(TxnType.LEND, Money.ofRupees(5_000.0), today, account.id, personName = "Ramesh"))
        data = data.applyEntry(EntryInput(TxnType.BORROW, Money.ofRupees(2_000.0), today, account.id, personName = "Ramesh"))

        assertEquals(2, data.debts.size)
        assertEquals(Money.ofRupees(5_000.0), data.receivables)
        assertEquals(Money.ofRupees(2_000.0), data.payables)
    }

    @Test fun `repaying with interest splits principal from expense`() {
        var data = base()
        val account = data.liveAccounts.first()
        data = data.applyEntry(EntryInput(TxnType.BORROW, Money.ofRupees(60_000.0), today, account.id, personName = "Papa"))
        val debtId = data.debts.single().id

        data = data.applyEntry(
            EntryInput(
                TxnType.SETTLE, Money.ofRupees(10_400.0), today, account.id,
                debtId = debtId, interest = Money.ofRupees(400.0)
            )
        )
        assertEquals(Money.ofRupees(50_000.0), data.payables)
        assertEquals(Money.ofRupees(400.0), Ledger.summary(data.transactions).expense)
    }

    @Test fun `editing an entry replaces it rather than adding another`() {
        var data = base()
        val account = data.liveAccounts.first()
        val category = data.categories.first { it.kind == CategoryKind.EXPENSE }
        data = data.applyEntry(EntryInput(TxnType.EXPENSE, Money.ofRupees(500.0), today, account.id, categoryId = category.id))
        val id = data.transactions.single().id

        data = data.applyEntry(
            EntryInput(TxnType.EXPENSE, Money.ofRupees(750.0), today, account.id, categoryId = category.id),
            editingTxnId = id
        )
        assertEquals(1, data.transactions.size)
        assertEquals(Money.ofRupees(750.0), data.transactions.single().amount)
        assertEquals(id, data.transactions.single().id)
    }

    @Test fun `confirming a capture clears it and learns from the choice`() {
        val start = base()
        val captured = start.ingest(
            MessageParser.parse("Rs.640.50 debited from A/c XX1234 on 19-08-26 to VPA swiggy@icici (UPI Ref 412345678901)"),
            CaptureSource.SMS, today
        )
        val item = captured.data.inbox.single()
        val account = captured.data.liveAccounts.first()
        val category = captured.data.categories.first { it.name == "Food & Dining" }

        val after = captured.data.applyEntry(
            EntryInput(TxnType.EXPENSE, Money.ofRupees(640.50), today, account.id, categoryId = category.id),
            confirmingInboxId = item.id
        )

        assertTrue(after.inbox.isEmpty())
        assertEquals(CaptureSource.SMS, after.transactions.single().source)
        assertEquals(item.fingerprint, after.transactions.single().fingerprint)
        assertEquals(category.id, after.rules.single { it.match == "swiggy@icici" }.categoryId)
        assertEquals(account.id, after.settings.accountTails["1234"])

        // and the same message cannot come back in
        assertEquals(
            AppData.IngestStatus.DUPLICATE,
            after.ingest(
                MessageParser.parse("Rs.640.50 debited from A/c XX1234 on 19-08-26 to VPA swiggy@icici (UPI Ref 412345678901)"),
                CaptureSource.SMS, today
            ).status
        )
    }

    @Test fun `a transfer keeps its destination and drops any category`() {
        val data = base()
        val from = data.liveAccounts[0]
        val to = data.liveAccounts[1]
        val category = data.categories.first()

        val after = data.applyEntry(
            EntryInput(TxnType.TRANSFER, Money.ofRupees(5_000.0), today, from.id, toAccountId = to.id, categoryId = category.id)
        )
        val txn = after.transactions.single()
        assertEquals(to.id, txn.toAccountId)
        assertNull(txn.categoryId, "a transfer is not spending, so it carries no category")
        assertEquals(Money.ofRupees(-5_000.0), Ledger.balance(from, after.transactions))
        assertEquals(Money.ofRupees(5_000.0), Ledger.balance(to, after.transactions))
    }

    @Test fun `paying a card bill moves money without counting as spending`() {
        var data = base().withAccount(
            Account("acc_card", "Amex", AccountType.CREDIT_CARD, creditLimit = Money.ofRupees(1_00_000.0), statementDay = 18, dueDay = 7)
        )
        val bank = data.liveAccounts.first { it.type == AccountType.BANK }
        data = data.applyEntry(EntryInput(TxnType.EXPENSE, Money.ofRupees(9_000.0), today, "acc_card", categoryId = data.categories.first().id))
        assertEquals(Money.ofRupees(9_000.0), data.cardDebt)

        data = data.applyEntry(EntryInput(TxnType.TRANSFER, Money.ofRupees(9_000.0), today, bank.id, toAccountId = "acc_card"))
        assertEquals(0L, data.cardDebt)
        assertEquals(Money.ofRupees(9_000.0), Ledger.summary(data.transactions).expense, "the spend counts once, the payment never")
    }
}
