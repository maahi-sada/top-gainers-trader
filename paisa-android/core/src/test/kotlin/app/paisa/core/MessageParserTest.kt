package app.paisa.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same corpus the web reader is held to, so both stay in step. */
class MessageParserTest {

    private fun rupees(p: Paise?) = p?.let { Money.toRupees(it).toPlainString() }

    // ---------- UPI debits ----------

    @Test fun `hdfc upi to a vpa`() {
        val p = MessageParser.parse(
            "Rs.1,234.56 debited from A/c XX1234 on 19-08-26 to VPA ramesh.kumar@okhdfcbank (UPI Ref 123456789012). Not you? Call 18002586161"
        )
        assertTrue(p.ok)
        assertEquals(TxnType.EXPENSE, p.type)
        assertEquals("1234.56", rupees(p.amount))
        assertEquals(LocalDate.of(2026, 8, 19), p.date)
        assertEquals("Ramesh Kumar", p.counterparty)
        assertEquals("ramesh.kumar@okhdfcbank", p.vpa)
        assertEquals("1234", p.accountTail)
        assertEquals("123456789012", p.reference)
        assertEquals("upi", p.method)
    }

    @Test fun `kotak sent`() {
        val p = MessageParser.parse("Sent Rs.100.00 From Kotak Bank AC X1234 To ramesh@ybl On 19-08-26 Ref 502312345678")
        assertTrue(p.ok)
        assertEquals(TxnType.EXPENSE, p.type)
        assertEquals("100.00", rupees(p.amount))
        assertEquals("Kotak", p.bank)
        assertEquals("1234", p.accountTail)
    }

    @Test fun `sbi debit with a bare amount`() {
        val p = MessageParser.parse(
            "Dear UPI user A/C X1234 debited by 240.0 on date 12Aug26 trf to BLINKIT Refno 522398765432. If not u? call 1800111109 -SBI"
        )
        assertTrue(p.ok)
        assertEquals(TxnType.EXPENSE, p.type)
        assertEquals("240.00", rupees(p.amount))
        assertEquals("Blinkit", p.counterparty)
        assertEquals(LocalDate.of(2026, 8, 12), p.date)
        assertEquals("SBI", p.bank)
    }

    @Test fun `merchant followed by a bracket`() {
        val p = MessageParser.parse("Rs.2,310.75 debited from A/c XX1234 on 15-08-26 to RELIANCE FRESH (UPI Ref 512345678904)")
        assertTrue(p.ok)
        assertEquals("Reliance Fresh", p.counterparty)
        assertEquals("2310.75", rupees(p.amount))
    }

    // ---------- card and ATM ----------

    @Test fun `card spend marks the card flag`() {
        val p = MessageParser.parse("HDFC Bank: Rs 640.50 spent on Card XX5678 at SWIGGY on 11-08-2026. Avl Lmt Rs 84,359.50")
        assertTrue(p.ok)
        assertEquals("640.50", rupees(p.amount))
        assertEquals("Swiggy", p.counterparty)
        assertEquals("5678", p.accountTail)
        assertTrue(p.onCard, "a card message should be routable to a credit card")
    }

    @Test fun `atm withdrawal is not a merchant`() {
        val p = MessageParser.parse("Rs.5000.00 withdrawn from A/c XX9012 at ATM on 09/08/26. Avl Bal Rs.23,410.00")
        assertTrue(p.ok)
        assertEquals("5000.00", rupees(p.amount))
        assertNull(p.counterparty)
        assertEquals("atm", p.method)
        assertEquals(2341000L, p.balance)
    }

    // ---------- credits ----------

    @Test fun `salary credit`() {
        val p = MessageParser.parse("Your a/c no. XXXXXXXX4321 is credited by Rs.82,000.00 on 01/08/26 - SALARY AUG. Avl Bal Rs.1,04,556.20")
        assertTrue(p.ok)
        assertEquals(TxnType.INCOME, p.type)
        assertEquals("82000.00", rupees(p.amount))
        assertEquals(LocalDate.of(2026, 8, 1), p.date)
        assertEquals("4321", p.accountTail)
        assertEquals("Salary Aug", p.counterparty)
    }

    @Test fun `money received over upi`() {
        val p = MessageParser.parse("INR 2,500.00 credited to your A/c XX1234 on 05-08-2026 from priya@okaxis. Ref 445566778899")
        assertTrue(p.ok)
        assertEquals(TxnType.INCOME, p.type)
        assertEquals("Priya", p.counterparty)
    }

    @Test fun `refund names the shop not the card`() {
        val p = MessageParser.parse("Rs 1,890.00 has been refunded to your Kotak Bank Card XX5678 on 14-08-26 by AMAZON")
        assertTrue(p.ok)
        assertEquals(TxnType.INCOME, p.type)
        assertEquals("Amazon", p.counterparty)
    }

    // ---------- wallets ----------

    @Test fun `paytm wallet payment`() {
        val p = MessageParser.parse("Paytm: Rs.240 paid to Blinkit from your Paytm Wallet on 12-08-2026")
        assertTrue(p.ok)
        assertEquals(TxnType.EXPENSE, p.type)
        assertEquals("Blinkit", p.counterparty)
        assertEquals("Paytm", p.bank)
    }

    @Test fun `google pay receipt`() {
        val p = MessageParser.parse("You've received Rs 500 from Priya Sharma via Google Pay on 08-08-2026")
        assertTrue(p.ok)
        assertEquals(TxnType.INCOME, p.type)
        assertEquals("Priya Sharma", p.counterparty)
        assertEquals("Google Pay", p.bank)
    }

    // ---------- things that must never become entries ----------

    @Test fun `rejects non transactions`() {
        val cases = mapOf(
            "Your OTP for txn of Rs.4,500 at AMAZON is 483920. Do not share it." to "OTP message",
            "Rs.499 will be debited from your A/c XX1234 on 25-08-26 towards NETFLIX autopay" to "Upcoming charge, not done yet",
            "Your transaction of Rs.2,000 to ramesh@ybl has failed. Amount will be refunded." to "Transaction did not go through",
            "Your credit card bill of Rs 12,450 is due on 27-08-2026. Pay now to avoid charges." to "Bill reminder",
            "Pre-approved loan offer of Rs 5,00,000! Apply now. T&C apply." to "Promotional message",
            "ramesh@ybl has requested Rs.300 via UPI. Approve in your app." to "Payment request, not a payment"
        )
        cases.forEach { (message, why) ->
            val p = MessageParser.parse(message)
            assertFalse(p.ok, "should have been rejected: $message")
            assertEquals(why, p.why)
        }
    }

    @Test fun `loan adverts written in credit language are refused`() {
        // These fill an Indian inbox and outnumber real alerts. Read as income
        // they would swamp the earnings figure, which is exactly what happened.
        val adverts = listOf(
            "Get Rs.5,00,000 credited to your account in just 10 minutes! Personal loan at 10.49% p.a. Click hdfcbk.io/l/9x",
            "You are eligible for Rs 2,00,000. Amount will be credited instantly. Minimal docs. Call 18001234567",
            "Rs 10,00,000 pre-qualified loan. Receive funds same day. Visit bit.ly/abc to know more",
            "Instant loan upto Rs.50,000 credited within 5 mins. Download our app now!",
            "Spend Rs 5,000 on your card and get Rs 500 cashback credited. Offer valid till 30 Sep",
            "Add Rs 1,000 to your wallet and receive Rs 100 extra! Limited period"
        )
        adverts.forEach { advert ->
            val parsed = MessageParser.parse(advert)
            assertFalse(parsed.ok, "advert should never be captured: $advert")
        }
    }

    @Test fun `an amount with no account or reference is not a transaction`() {
        val parsed = MessageParser.parse("Congratulations! Rs 25,000 has been credited as your reward.")
        assertFalse(parsed.ok)
        assertEquals("No account or reference — reads like an advert, not a transaction", parsed.why)
    }

    @Test fun `genuine alerts still survive the advert filter`() {
        // The filter must not start refusing the messages that matter.
        val genuine = listOf(
            "Rs.640.50 debited from A/c XX1234 on 19-08-26 to VPA swiggy@icici (UPI Ref 412345678901)" to TxnType.EXPENSE,
            "Your a/c no. XXXXXXXX4321 is credited by Rs.82,000.00 on 01-08-26 - SALARY AUG" to TxnType.INCOME,
            "INR 2,500.00 credited to your A/c XX1234 on 05-08-2026 from priya@okaxis. Ref 445566778899" to TxnType.INCOME,
            "Rs 2,499.00 spent on HDFC Bank Card XX5678 at AMAZON on 18-08-26. Avl Lmt Rs 1,45,000" to TxnType.EXPENSE,
            "Paytm: Rs.240 paid to Blinkit from your Paytm Wallet on 12-08-2026" to TxnType.EXPENSE,
            "Rs.5000.00 withdrawn from A/c XX9012 at ATM on 09/08/26. Avl Bal Rs.23,410.00" to TxnType.EXPENSE,
            "Sent Rs.100.00 From Kotak Bank AC X1234 To ramesh@ybl On 19-08-26 Ref 502312345678" to TxnType.EXPENSE
        )
        genuine.forEach { (message, expected) ->
            val parsed = MessageParser.parse(message)
            assertTrue(parsed.ok, "should still be captured: $message (${parsed.why})")
            assertEquals(expected, parsed.type, "wrong direction for: $message")
        }
    }

    @Test fun `a balance statement is not a transaction`() {
        val p = MessageParser.parse("Your A/c XX1234 balance is Rs.45,000.00 as on 19-08-26")
        assertFalse(p.ok)
    }

    @Test fun `available balance is never mistaken for the amount`() {
        val p = MessageParser.parse("Rs.320.00 debited from A/c XX1234 on 19-08-26. Avl Bal Rs.98,765.43")
        assertTrue(p.ok)
        assertEquals("320.00", rupees(p.amount))
        assertEquals(9876543L, p.balance)
    }

    // ---------- splitting and identity ----------

    @Test fun `splits one message per line`() {
        val blob = """
            Rs.100 debited from A/c XX1234 on 01-08-26 to VPA a@ybl
            Rs.200 debited from A/c XX1234 on 02-08-26 to VPA b@ybl
            Rs.300 credited to A/c XX1234 on 03-08-26 from c@ybl
        """.trimIndent()
        assertEquals(3, MessageParser.split(blob).size)
    }

    @Test fun `splits on blank lines`() {
        val blob = "Rs.100 debited from A/c XX1234 on 01-08-26\n\nRs.200 credited to A/c XX1234 on 02-08-26"
        assertEquals(2, MessageParser.split(blob).size)
    }

    @Test fun `the same message always fingerprints the same`() {
        val message = "Rs.100 debited from A/c XX1234 on 01-08-26 to VPA a@ybl Ref 999888777666"
        val a = MessageParser.fingerprint(MessageParser.parse(message))
        val b = MessageParser.fingerprint(MessageParser.parse(message))
        assertEquals(a, b)

        val other = MessageParser.parse("Rs.150 debited from A/c XX1234 on 01-08-26 to VPA a@ybl Ref 111222333444")
        assertTrue(a != MessageParser.fingerprint(other))
    }

    @Test fun `messages without a reference still fingerprint distinctly`() {
        val one = MessageParser.parse("Rs.100 debited from A/c XX1234 on 01-08-26 to SWIGGY")
        val two = MessageParser.parse("Rs.100 debited from A/c XX1234 on 02-08-26 to SWIGGY")
        assertNotNull(one.amount)
        assertTrue(MessageParser.fingerprint(one) != MessageParser.fingerprint(two))
    }
}
