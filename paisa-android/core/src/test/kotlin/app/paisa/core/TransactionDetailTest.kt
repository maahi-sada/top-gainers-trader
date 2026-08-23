package app.paisa.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every rupee has to arrive with its full story: when to the minute, who, by
 * what route, under what reference, and the bank's own words to check against.
 */
class TransactionDetailTest {

    private val today = LocalDate.of(2026, 8, 21)

    // ---------- time of day ----------

    @Test fun `reads a 24-hour time`() {
        val p = MessageParser.parse("Rs.640.50 debited from A/c XX1234 on 19-08-26 at 14:32:11 to VPA swiggy@icici")
        assertEquals(LocalTime.of(14, 32, 11), p.time)
    }

    @Test fun `reads a time without seconds`() {
        val p = MessageParser.parse("Rs.200 debited from A/c XX1234 on 19-08-26 09:05 to VPA a@ybl")
        assertEquals(LocalTime.of(9, 5, 0), p.time)
    }

    @Test fun `reads an afternoon time written with pm`() {
        val p = MessageParser.parse("Rs 2,499.00 spent on Card XX5678 at AMAZON on 18-08-26 at 02:35 PM")
        assertEquals(LocalTime.of(14, 35, 0), p.time)
    }

    @Test fun `midnight written as 12 am is hour zero`() {
        val p = MessageParser.parse("Rs 100 debited from A/c XX1234 on 18-08-26 at 12:15 AM to VPA a@ybl")
        assertEquals(LocalTime.of(0, 15, 0), p.time)
    }

    @Test fun `a message with no time leaves it unknown`() {
        val p = MessageParser.parse("Rs.320 debited from A/c XX1234 on 14-08-26 to VPA blinkit@ybl")
        assertNull(p.time)
    }

    // ---------- the details survive to the ledger ----------

    private val fullMessage =
        "Rs.2,499.00 debited from A/c XX1234 on 18-08-26 at 14:32:11 to VPA swiggy@icici " +
            "(UPI Ref 412345678901). Avl Bal Rs.98,765.43"

    @Test fun `confirming a capture keeps everything the bank said`() {
        val start = AppData.seed()
        val captured = start.ingest(MessageParser.parse(fullMessage), CaptureSource.SMS, today, nowMillis = 1_755_000_000_000)
        val (after, txn) = captured.data.confirmInbox(captured.data.inbox.single().id, today)

        assertNotNull(txn)
        assertEquals(LocalTime.of(14, 32, 11), txn.time, "time of day must reach the ledger")
        assertEquals(LocalDate.of(2026, 8, 18), txn.date)
        assertEquals("412345678901", txn.reference, "the reference to quote in a dispute")
        assertEquals("upi", txn.method)
        assertEquals("Swiggy", txn.merchant)
        assertEquals("swiggy@icici", txn.vpa)
        assertEquals("1234", txn.accountTail)
        assertEquals(Money.ofRupees(98_765.43), txn.balanceAfter)
        assertEquals(1_755_000_000_000, txn.capturedAtMillis)
        assertTrue(txn.rawMessage!!.contains("412345678901"), "the original message is kept verbatim")

        // and it survives being written to storage and read back
        val reread = AppData.decode(SnapshotCodec.encode(after.toSnapshot())).transactions.single()
        assertEquals(txn.time, reread.time)
        assertEquals(txn.reference, reread.reference)
        assertEquals(txn.balanceAfter, reread.balanceAfter)
        assertEquals(txn.rawMessage, reread.rawMessage)
        assertEquals(txn.capturedAtMillis, reread.capturedAtMillis)
    }

    @Test fun `an entry typed in today is stamped with the time it was typed`() {
        val data = AppData.seed()
        val account = data.liveAccounts.first()
        val after = data.applyEntry(
            EntryInput(TxnType.EXPENSE, Money.ofRupees(120.0), today, account.id),
            today = today
        )
        val txn = after.transactions.single()
        assertNotNull(txn.time, "a same-day entry should carry the moment it was recorded")
        assertNotNull(txn.capturedAtMillis)
    }

    @Test fun `a backdated entry is not given a made-up time`() {
        val data = AppData.seed()
        val account = data.liveAccounts.first()
        val after = data.applyEntry(
            EntryInput(TxnType.EXPENSE, Money.ofRupees(120.0), today.minusDays(3), account.id),
            today = today
        )
        assertNull(after.transactions.single().time)
    }

    @Test fun `editing an entry does not lose the captured details`() {
        val start = AppData.seed()
        val captured = start.ingest(MessageParser.parse(fullMessage), CaptureSource.SMS, today)
        val item = captured.data.inbox.single()
        var data = captured.data.confirmInbox(item.id, today).first
        val original = data.transactions.single()

        data = data.applyEntry(
            EntryInput(TxnType.EXPENSE, Money.ofRupees(2_600.0), original.date, original.accountId),
            editingTxnId = original.id,
            today = today
        )
        val edited = data.transactions.single()
        assertEquals(Money.ofRupees(2_600.0), edited.amount, "the correction applies")
        assertEquals("412345678901", edited.reference, "the audit trail stays")
        assertEquals(LocalTime.of(14, 32, 11), edited.time)
        assertEquals(original.rawMessage, edited.rawMessage)
    }

    // ---------- the spreadsheet ----------

    @Test fun `csv carries every column for every movement`() {
        val start = AppData.seed()
        val captured = start.ingest(MessageParser.parse(fullMessage), CaptureSource.SMS, today)
        val data = captured.data.confirmInbox(captured.data.inbox.single().id, today).first

        val csv = CsvExport.ledger(data)
        val lines = csv.split("\n")
        val header = lines.first()

        listOf("Date", "Time", "Amount (INR)", "Merchant", "Reference", "Balance after (INR)", "Original message")
            .forEach { assertTrue(header.contains(it), "missing column: $it") }

        val row = lines[1]
        assertTrue(row.contains("2026-08-18"), row)
        assertTrue(row.contains("14:32:11"), "the time belongs in the export")
        assertTrue(row.contains("2499.00"), row)
        assertTrue(row.contains("412345678901"), row)
        assertTrue(row.contains("98765.43"), "the reported balance belongs in the export")
        assertEquals(2, lines.size, "one header, one movement")
    }

    @Test fun `a narration full of commas survives the export`() {
        val data = AppData.seed().withTransaction(
            Txn(
                id = "t1", type = TxnType.EXPENSE, amount = Money.ofRupees(500.0),
                date = today, time = LocalTime.of(10, 0), accountId = "acc",
                note = "Lunch, chai, and a \"treat\"",
                rawMessage = "Rs.500 spent at CAFE, MG ROAD on 21-08-26"
            )
        )
        val row = CsvExport.ledger(data).split("\n")[1]
        assertTrue(row.contains("\"Lunch, chai, and a \"\"treat\"\"\""), "quotes and commas must be escaped: $row")
        assertTrue(row.contains("\"Rs.500 spent at CAFE, MG ROAD on 21-08-26\""), row)
    }
}
