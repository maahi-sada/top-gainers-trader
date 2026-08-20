package app.paisa.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The fixtures here are produced by the web app itself
 * (`core/src/test/resources/web-backup.json` plus the totals it calculated).
 * If these pass, a backup moves between phone and browser without drift.
 */
class SnapshotTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing test resource $name" }
            .bufferedReader().readText()

    private val backup by lazy { SnapshotCodec.decode(resource("web-backup.json")) }
    private val expected by lazy { Json.parseToJsonElement(resource("web-expected.json")).jsonObject }

    private fun expectedLong(key: String): Long = expected.getValue(key).jsonPrimitive.long
    private fun expectedText(key: String): String = expected.getValue(key).jsonPrimitive.content

    @Test fun `a web backup opens with every record intact`() {
        assertEquals(3, SnapshotCodec.accounts(backup).size)
        assertTrue(SnapshotCodec.categories(backup).size >= 20)
        assertEquals(8, SnapshotCodec.transactions(backup).size)
        assertEquals(2, SnapshotCodec.debts(backup).size)
        assertEquals(1, SnapshotCodec.rules(backup).size)
        assertEquals(1, SnapshotCodec.recurring(backup).size)
    }

    @Test fun `balances match what the web app calculated`() {
        val accounts = SnapshotCodec.accounts(backup)
        val txns = SnapshotCodec.transactions(backup)
        val debts = SnapshotCodec.debts(backup)

        assertEquals(expectedLong("totalBalance"), Ledger.totalBalance(accounts, txns))
        assertEquals(expectedLong("receivables"), Ledger.receivables(debts, txns))
        assertEquals(expectedLong("payables"), Ledger.payables(debts, txns))
        assertEquals(expectedLong("netWorth"), Ledger.netWorth(accounts, debts, txns))

        val bank = accounts.first { it.name == "Bank Account" }
        val cash = accounts.first { it.name == "Cash" }
        assertEquals(expectedLong("bankBalance"), Ledger.balance(bank, txns))
        assertEquals(expectedLong("cashBalance"), Ledger.balance(cash, txns))
    }

    @Test fun `the pay-cycle month matches too`() {
        val txns = SnapshotCodec.transactions(backup)
        val startDay = backup.settings.monthStartDay
        val range = Ledger.monthRange(LocalDate.of(2026, 8, 20), startDay)

        assertEquals(expectedText("monthFrom"), range.start.toString())
        assertEquals(expectedText("monthTo"), range.endInclusive.toString())

        val summary = Ledger.summary(txns, range.start, range.endInclusive)
        assertEquals(expectedLong("income"), summary.income)
        assertEquals(expectedLong("expense"), summary.expense)
    }

    @Test fun `settings survive the trip`() {
        assertEquals(5, backup.settings.monthStartDay)
        assertEquals(Money.ofRupees(45_000.0), backup.settings.monthlyBudget)
        assertTrue(backup.settings.autoConfirm)
    }

    @Test fun `debt directions are read the same way round`() {
        val debts = SnapshotCodec.debts(backup)
        val ramesh = debts.first { it.person == "Ramesh" }
        val papa = debts.first { it.person == "Papa" }
        assertEquals(DebtDirection.OWED_TO_ME, ramesh.direction)
        assertEquals(DebtDirection.I_OWE, papa.direction)
        assertEquals(LocalDate.of(2026, 9, 1), ramesh.dueDate)
    }

    @Test fun `writing it back out changes nothing`() {
        val accounts = SnapshotCodec.accounts(backup)
        val txns = SnapshotCodec.transactions(backup)
        val debts = SnapshotCodec.debts(backup)

        val rebuilt = SnapshotCodec.build(
            settings = backup.settings,
            accounts = accounts,
            categories = SnapshotCodec.categories(backup),
            transactions = txns,
            debts = debts,
            rules = SnapshotCodec.rules(backup),
            recurring = SnapshotCodec.recurring(backup),
            inbox = emptyList()
        )
        val reread = SnapshotCodec.decode(SnapshotCodec.encode(rebuilt))

        assertEquals(Ledger.totalBalance(accounts, txns), Ledger.totalBalance(SnapshotCodec.accounts(reread), SnapshotCodec.transactions(reread)))
        assertEquals(Ledger.netWorth(accounts, debts, txns), Ledger.netWorth(SnapshotCodec.accounts(reread), SnapshotCodec.debts(reread), SnapshotCodec.transactions(reread)))
        assertEquals(SnapshotCodec.transactions(backup).map { it.id }, SnapshotCodec.transactions(reread).map { it.id })
    }

    @Test fun `colours survive as hex the web app understands`() {
        val category = SnapshotCodec.categories(backup).first { it.name == "Rent" }
        val hex = SnapshotCodec.formatColor(category.color)
        assertTrue(hex.matches(Regex("#[0-9a-f]{6}")), "unexpected colour: $hex")
        assertEquals(category.color, SnapshotCodec.parseColor(hex))
    }

    @Test fun `credit card details ride along in the same document`() {
        val card = Account(
            "acc_card", "HDFC Regalia", AccountType.CREDIT_CARD,
            creditLimit = Money.ofRupees(2_00_000.0), statementDay = 18, dueDay = 7, last4 = "5678"
        )
        val snapshot = SnapshotCodec.build(SettingsDto(), listOf(card), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val text = SnapshotCodec.encode(snapshot)
        assertTrue(text.contains("\"type\": \"card\""), "cards should serialise as the web app's card type")

        val back = SnapshotCodec.accounts(SnapshotCodec.decode(text)).first()
        assertEquals(AccountType.CREDIT_CARD, back.type)
        assertEquals(18, back.statementDay)
        assertEquals(7, back.dueDay)
        assertEquals("5678", back.last4)
        assertEquals(Money.ofRupees(2_00_000.0), back.creditLimit)
    }

    @Test fun `an unknown field from a newer version does not break the read`() {
        val text = """{"schema":1,"somethingNew":{"a":1},"accounts":[{"id":"a","name":"X","type":"bank","futureFlag":true}]}"""
        val snapshot = SnapshotCodec.decode(text)
        assertEquals(1, snapshot.accounts.size)
        assertNotNull(SnapshotCodec.accounts(snapshot).first())
    }
}
