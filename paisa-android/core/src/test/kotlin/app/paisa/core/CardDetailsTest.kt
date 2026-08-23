package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Card limits, statement dates and due dates arriving on their own from email. */
class CardDetailsTest {

    private val today = LocalDate.of(2026, 8, 23)

    private val hdfcStatement = """
        HDFC Bank Credit Card ending 4321
        Statement Date: 18/08/2026
        Payment Due Date: 07/09/2026
        Total Dues: Rs. 24,530.00
        Minimum Amount Due: Rs. 1,230.00
        Credit Limit: Rs. 3,00,000.00
        Available Credit Limit: Rs. 2,75,470.00
    """.trimIndent()

    private fun statement(text: String = hdfcStatement) = CardStatementParser.readMessage(text, today)

    private fun withCard(card: Account) = AppData(accounts = listOf(card), categories = Seed.categories())

    @Test
    fun `fills in a card that was set up with nothing but a name`() {
        val card = Account(Ids.next("acc"), "HDFC Card", AccountType.CREDIT_CARD)
        val result = withCard(card).applyCardStatement(statement(), today)

        assertTrue(result.applied, result.reason)
        assertFalse(result.created)
        val updated = result.data.account(card.id)!!
        assertEquals("4321", updated.last4)
        assertEquals(Money.parse("300000"), updated.creditLimit)
        assertEquals(18, updated.statementDay)
        assertEquals(7, updated.dueDay)
        assertEquals(Money.parse("24530"), updated.lastStatementDue)
        assertEquals(Money.parse("1230"), updated.lastMinimumDue)
        assertEquals(LocalDate.of(2026, 8, 18), updated.lastStatementDate)
        assertEquals("HDFC statement of 18 Aug 2026", updated.detailsFrom)
    }

    @Test
    fun `routes by the digits, not the name`() {
        val hdfc = Account("a1", "Blue Card", AccountType.CREDIT_CARD, last4 = "4321")
        val other = Account("a2", "HDFC Card", AccountType.CREDIT_CARD, last4 = "9999")
        val data = AppData(accounts = listOf(hdfc, other))

        val result = data.applyCardStatement(statement(), today)

        assertTrue(result.applied, result.reason)
        assertEquals("a1", result.account?.id)
        assertEquals(1, result.data.account("a2")!!.dueDay, "the other card must be untouched")
    }

    @Test
    fun `will not guess between two cards from the same bank`() {
        val one = Account("a1", "HDFC Regalia", AccountType.CREDIT_CARD)
        val two = Account("a2", "HDFC Millennia", AccountType.CREDIT_CARD)
        val data = AppData(accounts = listOf(one, two))

        /* No digits in the message, two candidates: it creates a card rather
         * than writing a limit onto the wrong one. */
        val vague = CardStatementParser.readMessage(
            "Your HDFC Bank Credit Card statement is ready. Payment Due Date 07/09/2026. Total Amount Due Rs 900.",
            today
        )
        val result = data.applyCardStatement(vague, today)

        assertTrue(result.created)
        assertEquals(1, result.data.account("a1")!!.dueDay)
        assertEquals(1, result.data.account("a2")!!.dueDay)
    }

    @Test
    fun `creates a card it has never seen`() {
        val data = AppData(categories = Seed.categories())
        val result = data.applyCardStatement(statement(), today)

        assertTrue(result.applied, result.reason)
        assertTrue(result.created)
        assertEquals(1, result.data.cards.size)
        val card = result.data.cards.first()
        assertEquals("HDFC Card ••4321", card.name)
        assertEquals(AccountType.CREDIT_CARD, card.type)
        assertEquals(Money.parse("300000"), card.creditLimit)
        assertEquals(7, card.dueDay)
    }

    @Test
    fun `remembers the digits so later purchases land on that card`() {
        val result = AppData().applyCardStatement(statement(), today)
        val card = result.data.cards.first()
        assertEquals(card.id, result.data.settings.accountTails["4321"])
    }

    @Test
    fun `says nothing when it already knew everything`() {
        val first = AppData().applyCardStatement(statement(), today)
        val second = first.data.applyCardStatement(statement(), today)

        assertFalse(second.applied)
        assertEquals("Already knew all of that", second.reason)
        assertEquals(1, second.data.cards.size, "reading the same mail twice must not add a second card")
    }

    @Test
    fun `next month's statement moves only what changed`() {
        val first = AppData().applyCardStatement(statement(), today)
        val september = CardStatementParser.readMessage(
            """
            HDFC Bank Credit Card ending 4321
            Statement Date: 18/09/2026
            Payment Due Date: 07/10/2026
            Total Dues: Rs. 31,000.00
            Minimum Amount Due: Rs. 1,550.00
            Credit Limit: Rs. 3,00,000.00
            """.trimIndent(),
            LocalDate.of(2026, 9, 20)
        )
        val second = first.data.applyCardStatement(september, LocalDate.of(2026, 9, 20))

        assertTrue(second.applied)
        assertEquals(listOf("bill ₹31,000.00", "minimum ₹1,550.00"), second.changes)
        val card = second.data.cards.first()
        assertEquals(18, card.statementDay)
        assertEquals(7, card.dueDay)
        assertEquals(LocalDate.of(2026, 9, 18), card.lastStatementDate)
    }

    @Test
    fun `a limit increase updates the limit and leaves the dates alone`() {
        val first = AppData().applyCardStatement(statement(), today)
        val increase = CardStatementParser.readMessage(
            "Your HDFC Bank Credit Card XX4321 credit limit has been increased to Rs 4,50,000.",
            today
        )
        val second = first.data.applyCardStatement(increase, today)

        assertTrue(second.applied, second.reason)
        val card = second.data.cards.first()
        assertEquals(Money.parse("450000"), card.creditLimit)
        assertEquals(18, card.statementDay)
        assertEquals(7, card.dueDay)
    }

    @Test
    fun `refuses to act on something that is not a statement`() {
        val advert = CardStatementParser.readMessage(
            "You are pre-approved for a credit card with a limit of up to Rs 5,00,000. Apply now!",
            today
        )
        val result = AppData().applyCardStatement(advert, today)

        assertFalse(result.applied)
        assertTrue(result.data.cards.isEmpty())
    }

    @Test
    fun `the card cycle then reports the right dates`() {
        val data = AppData().applyCardStatement(statement(), today).data
        val status = data.cardStatuses(today).single()

        assertEquals(LocalDate.of(2026, 8, 18), status.lastStatement)
        assertEquals(LocalDate.of(2026, 9, 7), status.dueDate)
        assertEquals(Money.parse("300000"), status.creditLimit)
    }

    @Test
    fun `statement details survive a backup and restore`() {
        val data = AppData().applyCardStatement(statement(), today).data
        val restored = AppData.decode(SnapshotCodec.encode(data.toSnapshot()))
        val card = restored.cards.single()

        assertEquals("4321", card.last4)
        assertEquals(Money.parse("300000"), card.creditLimit)
        assertEquals(18, card.statementDay)
        assertEquals(7, card.dueDay)
        assertEquals(LocalDate.of(2026, 8, 18), card.lastStatementDate)
        assertEquals(Money.parse("24530"), card.lastStatementDue)
        assertEquals("HDFC statement of 18 Aug 2026", card.detailsFrom)
    }

    @Test
    fun `a statement email never becomes a transaction`() {
        val parsed = EmailText.bestParse("Your HDFC Bank Credit Card Statement", hdfcStatement)
        val result = AppData().ingest(parsed, CaptureSource.EMAIL, today)

        assertEquals(AppData.IngestStatus.REJECTED, result.status)
        assertTrue(result.data.transactions.isEmpty())
        assertTrue(result.data.inbox.isEmpty())
    }

    @Test
    fun `an unknown card with no bank name is left alone`() {
        val orphan = CardStatementParser.readMessage("Statement Date 18/08/2026 for card ending 4321", today)
        assertNotNull(orphan)
        val result = AppData().applyCardStatement(orphan, today)
        /* Digits alone are enough to file it — there is nothing else it could be. */
        assertTrue(result.applied, result.reason)
        assertEquals("Credit Card ••4321", result.data.cards.single().name)
    }

    @Test
    fun `an existing limit typed by hand gives way to the bank`() {
        val card = Account("a1", "HDFC Card", AccountType.CREDIT_CARD, creditLimit = Money.parse("100000")!!,
            statementDay = 1, dueDay = 20, last4 = "4321")
        val result = AppData(accounts = listOf(card)).applyCardStatement(statement(), today)

        assertTrue(result.applied)
        val updated = result.data.account("a1")!!
        assertEquals(Money.parse("300000"), updated.creditLimit)
        assertEquals(18, updated.statementDay)
        assertEquals(7, updated.dueDay)
    }

    @Test
    fun `no card details in an ordinary purchase message`() {
        val purchase = CardStatementParser.readMessage(
            "Rs.640.50 spent on HDFC Bank Credit Card xx4321 at SWIGGY on 19-08-26. Avl Limit Rs.45,000.",
            today
        )
        assertFalse(purchase.ok)
        assertNull(purchase.creditLimit)
    }
}
