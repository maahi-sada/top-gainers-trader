package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardStatementParserTest {

    private val today = LocalDate.of(2026, 8, 23)

    private fun read(subject: String?, body: String?) = CardStatementParser.read(subject, body, today)
    private fun sms(text: String) = CardStatementParser.readMessage(text, today)

    // ---------- email statements ----------

    @Test
    fun `reads an HDFC statement email`() {
        val body = """
            <p>Dear Customer,</p>
            <table>
              <tr><td>Card No</td><td>XXXX XXXX XXXX 4321</td></tr>
              <tr><td>Statement Date</td><td>18/08/2026</td></tr>
              <tr><td>Payment Due Date</td><td>07/09/2026</td></tr>
              <tr><td>Total Dues</td><td>Rs. 24,530.00</td></tr>
              <tr><td>Minimum Amount Due</td><td>Rs. 1,230.00</td></tr>
              <tr><td>Credit Limit</td><td>Rs. 3,00,000.00</td></tr>
              <tr><td>Available Credit Limit</td><td>Rs. 2,75,470.00</td></tr>
            </table>
        """.trimIndent()
        val s = read("Your HDFC Bank Credit Card Statement", body)

        assertTrue(s.ok, s.why)
        assertEquals("HDFC", s.bank)
        assertEquals("4321", s.last4)
        assertEquals(LocalDate.of(2026, 8, 18), s.statementDate)
        assertEquals(LocalDate.of(2026, 9, 7), s.dueDate)
        assertEquals(18, s.statementDay)
        assertEquals(7, s.dueDay)
        assertEquals(Money.parse("24530.00"), s.totalDue)
        assertEquals(Money.parse("1230.00"), s.minimumDue)
        assertEquals(Money.parse("300000"), s.creditLimit)
        assertEquals(Money.parse("275470"), s.availableLimit)
    }

    @Test
    fun `reads an SBI Card statement with month names`() {
        val body = """
            Your SBI Card statement for the period 19 Jul 2026 to 18 Aug 2026
            Total Amount Due: Rs 12,345.67
            Minimum Amount Due: Rs 620.00
            Payment Due Date: 07 Sep 2026
            Credit Limit: Rs 1,50,000
            Available Credit Limit: Rs 1,37,654.33
            Cash Limit: Rs 30,000
            Card ending 8890
        """.trimIndent()
        val s = read("SBI Card e-Statement", body)

        assertTrue(s.ok, s.why)
        assertEquals("SBI", s.bank)
        assertEquals("8890", s.last4)
        assertEquals(LocalDate.of(2026, 8, 18), s.statementDate)
        assertEquals(LocalDate.of(2026, 9, 7), s.dueDate)
        assertEquals(Money.parse("150000"), s.creditLimit)
        assertEquals(Money.parse("137654.33"), s.availableLimit)
        assertEquals(Money.parse("30000"), s.cashLimit)
        assertEquals(Money.parse("12345.67"), s.totalDue)
        assertEquals(Money.parse("620"), s.minimumDue)
    }

    @Test
    fun `reads an American Express statement written month first`() {
        val body = """
            Statement Date: August 18, 2026
            Payment Due Date: September 07, 2026
            New Balance: Rs 45,000.00
            Minimum Amount Due: Rs 2,250.00
            Credit Limit: Rs 5,00,000.00
            Your Card ending 1007
        """.trimIndent()
        val s = read("Your American Express Card statement is ready", body)

        assertTrue(s.ok, s.why)
        assertEquals("Amex", s.bank)
        assertEquals("1007", s.last4)
        assertEquals(LocalDate.of(2026, 8, 18), s.statementDate)
        assertEquals(LocalDate.of(2026, 9, 7), s.dueDate)
        assertEquals(Money.parse("500000"), s.creditLimit)
    }

    @Test
    fun `reads an ICICI statement laid out as a two column table`() {
        val body = "<table><tr><td>Total Amount due</td><td>Rs.8,940.55</td></tr>" +
            "<tr><td>Minimum Amount due</td><td>Rs.450.00</td></tr>" +
            "<tr><td>Due Date</td><td>02-Sep-2026</td></tr>" +
            "<tr><td>Statement Date</td><td>12-Aug-2026</td></tr></table>" +
            "<p>ICICI Bank Credit Card XX7788</p>"
        val s = read("ICICI Bank Credit Card Statement", body)

        assertTrue(s.ok, s.why)
        assertEquals("ICICI", s.bank)
        assertEquals("7788", s.last4)
        assertEquals(12, s.statementDay)
        assertEquals(2, s.dueDay)
        assertEquals(Money.parse("8940.55"), s.totalDue)
        assertEquals(Money.parse("450.00"), s.minimumDue)
    }

    @Test
    fun `available limit is never mistaken for the credit limit`() {
        val s = read("OneCard statement", """
            OneCard Credit Card ending 2244
            Statement Date 05 Aug 2026
            Payment Due Date 25 Aug 2026
            Available Credit Limit: Rs 40,000
            Credit Limit: Rs 2,00,000
        """.trimIndent())

        assertTrue(s.ok, s.why)
        assertEquals(Money.parse("200000"), s.creditLimit)
        assertEquals(Money.parse("40000"), s.availableLimit)
    }

    @Test
    fun `minimum due is never mistaken for the total due`() {
        val s = read("Kotak Card", """
            Kotak Credit Card XX3311
            Minimum Amount Due Rs 500.00
            Total Amount Due Rs 18,700.00
            Payment Due Date 10/09/2026
        """.trimIndent())

        assertTrue(s.ok, s.why)
        assertEquals(Money.parse("500"), s.minimumDue)
        assertEquals(Money.parse("18700"), s.totalDue)
    }

    @Test
    fun `reads a layout that puts each value on the line below its label`() {
        val s = read("Your card statement", """
            IDFC FIRST Bank Credit Card
            Card Number
            XXXX XXXX XXXX 6677
            Statement Date
            20 Aug 2026
            Payment Due Date
            08 Sep 2026
            Total Amount Due
            Rs. 9,875.40
            Credit Limit
            Rs. 2,50,000.00
        """.trimIndent())

        assertTrue(s.ok, s.why)
        assertEquals("IDFC", s.bank)
        assertEquals("6677", s.last4)
        assertEquals(20, s.statementDay)
        assertEquals(8, s.dueDay)
        assertEquals(Money.parse("9875.40"), s.totalDue)
        assertEquals(Money.parse("250000"), s.creditLimit)
    }

    @Test
    fun `a footer full of terms and conditions does not sink a real statement`() {
        val s = read("SBI Card Statement", """
            SBI Credit Card ending 8890
            Statement Date: 18 Aug 2026
            Payment Due Date: 07 Sep 2026
            Credit Limit: Rs 1,50,000
            Know more about our offers. T&C apply. Download the app today.
        """.trimIndent())

        assertTrue(s.ok, s.why)
        assertEquals(Money.parse("150000"), s.creditLimit)
    }

    // ---------- SMS ----------

    @Test
    fun `reads a bill reminder SMS`() {
        val s = sms(
            "Your HDFC Bank Credit Card ending 4321 bill of Rs.24530.00 is due on 07/09/2026. " +
                "Min Amt Due Rs.1230.00. Pay now to avoid charges."
        )
        assertTrue(s.ok, s.why)
        assertEquals("HDFC", s.bank)
        assertEquals("4321", s.last4)
        assertEquals(7, s.dueDay)
        assertEquals(Money.parse("1230"), s.minimumDue)
    }

    @Test
    fun `reads a statement generated SMS with no year on the date`() {
        val s = sms("Statement generated for your Axis Bank Credit Card XX9012. Total Due Rs 5,600. Due Date 05 Sep.")
        assertTrue(s.ok, s.why)
        assertEquals("Axis", s.bank)
        assertEquals("9012", s.last4)
        assertEquals(LocalDate.of(2026, 9, 5), s.dueDate)
        assertEquals(Money.parse("5600"), s.totalDue)
    }

    @Test
    fun `reads a limit increase SMS that carries no dates`() {
        val s = sms("Your ICICI Bank Credit Card XX1234 credit limit has been increased to Rs 2,00,000.")
        assertTrue(s.ok, s.why)
        assertEquals("ICICI", s.bank)
        assertEquals("1234", s.last4)
        assertEquals(Money.parse("200000"), s.creditLimit)
        assertNull(s.dueDate)
    }

    // ---------- what it must refuse ----------

    @Test
    fun `refuses a limit advert`() {
        val s = sms("Congratulations! You are pre-approved for a credit card with a limit of up to Rs 5,00,000. Apply now!")
        assertFalse(s.ok)
    }

    @Test
    fun `refuses an ordinary purchase alert`() {
        val s = sms(
            "Rs.640.50 spent on HDFC Bank Credit Card xx4321 at SWIGGY on 19-08-26. " +
                "Avl Limit Rs.45,000. Not you? Call 18002586161."
        )
        assertFalse(s.ok, "a purchase is not a statement")
    }

    @Test
    fun `refuses a purchase alert that quotes the credit limit`() {
        val s = sms(
            "Rs 1,200 spent on your Axis Bank Credit Card XX9012 at AMAZON on 19-08-26. " +
                "Credit Limit Rs 1,00,000, Available Limit Rs 88,000."
        )
        assertFalse(s.ok, "no statement or due date means this is not a statement")
    }

    @Test
    fun `refuses a message that names no card at all`() {
        val s = sms("Your savings account statement date is 18/08/2026. Balance Rs 45,000.")
        assertFalse(s.ok)
    }

    @Test
    fun `refuses an empty message`() {
        assertFalse(sms("").ok)
        assertFalse(CardStatementParser.read(null, null, today).ok)
    }

    // ---------- confidence ----------

    @Test
    fun `a full statement is more confident than a bare reminder`() {
        val full = read("HDFC statement", """
            HDFC Bank Credit Card ending 4321
            Statement Date 18/08/2026
            Payment Due Date 07/09/2026
            Credit Limit Rs 3,00,000
        """.trimIndent())
        val bare = sms("Your OneCard bill is due on 07 Sep 2026. Credit card account 5566.")

        assertTrue(full.ok && bare.ok)
        assertTrue(full.confidence > bare.confidence)
    }

    @Test
    fun `describes where the details came from`() {
        val s = read("HDFC statement", """
            HDFC Bank Credit Card ending 4321
            Statement Date 18/08/2026
            Payment Due Date 07/09/2026
        """.trimIndent())
        assertEquals("HDFC statement of 18 Aug 2026", s.describe())
    }
}
