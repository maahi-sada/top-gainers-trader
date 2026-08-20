package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailTextTest {

    @Test fun `html is flattened and entities decoded`() {
        val html = """
            <html><head><style>.x{color:red}</style></head>
            <body><div>Dear Customer,</div><br>
            <p>Rs.&nbsp;640.50 has been debited</p>
            <script>track()</script>
            <table><tr><td>Merchant</td><td>SWIGGY</td></tr></table>
            &#8377;100 &amp; more</body></html>
        """.trimIndent()
        val text = EmailText.htmlToText(html)
        assertFalse(text.contains("<"), "tags should be gone: $text")
        assertFalse(text.contains("color:red"), "style contents should be gone")
        assertFalse(text.contains("track()"), "script contents should be gone")
        assertTrue(text.contains("Rs. 640.50 has been debited"), text)
        assertTrue(text.contains("SWIGGY"), text)
        assertTrue(text.contains("₹100 & more"), text)
    }

    @Test fun `plain text passes through unharmed`() {
        assertEquals("Rs.100 debited from A/c XX1234", EmailText.htmlToText("Rs.100 debited from A/c XX1234"))
    }

    @Test fun `bank senders are recognised`() {
        assertTrue(EmailText.looksLikeBankMail("alerts@hdfcbank.net", "Transaction alert"))
        assertTrue(EmailText.looksLikeBankMail("no-reply@sbicard.com", "Your card was used"))
        assertTrue(EmailText.looksLikeBankMail("statements@onecard.in", "Statement ready"))
        // unknown sender, but automated and clearly an alert
        assertTrue(EmailText.looksLikeBankMail("noreply@somebank.co.in", "Debit transaction on your account"))
        // a user-added sender
        assertTrue(EmailText.looksLikeBankMail("mail@mycoopbank.in", "hello", listOf("mycoopbank")))
    }

    @Test fun `ordinary mail is left alone`() {
        assertFalse(EmailText.looksLikeBankMail("friend@gmail.com", "Dinner on Friday?"))
        assertFalse(EmailText.looksLikeBankMail("newsletter@shop.com", "50% off this weekend"))
    }

    @Test fun `reads a structured card alert`() {
        val body = """
            <p>Dear Cardmember,</p>
            <table>
              <tr><td>Amount</td><td>Rs. 2,499.00</td></tr>
              <tr><td>Card</td><td>XX5678</td></tr>
              <tr><td>Merchant</td><td>AMAZON</td></tr>
            </table>
            <p>Rs. 2,499.00 was spent on your Credit Card XX5678 at AMAZON on 14-08-2026.</p>
            <p>Available limit: Rs. 1,45,000.00</p>
        """.trimIndent()
        val parsed = EmailText.bestParse("Transaction alert on your HDFC Credit Card", body)
        assertTrue(parsed.ok, "should have read the alert: ${parsed.why}")
        assertEquals(TxnType.EXPENSE, parsed.type)
        assertEquals(Money.ofRupees(2_499.0), parsed.amount)
        assertEquals("Amazon", parsed.counterparty)
        assertEquals("5678", parsed.accountTail)
        assertEquals(LocalDate.of(2026, 8, 14), parsed.date)
        assertTrue(parsed.onCard)
    }

    @Test fun `the available limit never becomes the amount`() {
        val body = "Rs. 2,499.00 was spent on Card XX5678 at AMAZON on 14-08-2026. Available limit Rs. 1,45,000.00"
        val parsed = EmailText.bestParse("Card alert", body)
        assertEquals(Money.ofRupees(2_499.0), parsed.amount)
    }

    @Test fun `a promotional mail is refused even with an amount in it`() {
        val parsed = EmailText.bestParse("Big savings!", "<p>Get cashback up to Rs.5,000! Apply now. T&amp;C apply.</p>")
        assertFalse(parsed.ok)
    }

    @Test fun `an empty body is refused politely`() {
        val parsed = EmailText.bestParse(null, null)
        assertFalse(parsed.ok)
        assertEquals("Empty message", parsed.why)
    }
}
