package app.paisa.capture

import android.content.Context
import app.paisa.PaisaApplication
import app.paisa.core.AppData
import app.paisa.core.CaptureSource
import app.paisa.core.CardStatement
import app.paisa.core.CardStatementParser
import app.paisa.core.EmailText
import app.paisa.core.MessageParser
import app.paisa.core.Money
import app.paisa.core.ParsedMessage
import java.time.LocalDate

/**
 * The single door every automatic capture comes through, whether it started as
 * an SMS, an email or a shared message. Reads it, refuses anything unusable or
 * already seen, and either queues it for review or — when a learned rule covers
 * it and the user has allowed that — logs it outright.
 */
object Capture {

    data class Outcome(val status: AppData.IngestStatus, val reason: String?, val describe: String)

    suspend fun fromSms(context: Context, body: String, notify: Boolean = true): Outcome =
        applyCard(context, CardStatementParser.readMessage(body), notify)
            ?: ingest(context, MessageParser.parse(body), CaptureSource.SMS, notify)

    suspend fun fromEmail(context: Context, subject: String?, body: String?, notify: Boolean = true): Outcome =
        applyCard(context, CardStatementParser.read(subject, body), notify)
            ?: ingest(context, EmailText.bestParse(subject, body), CaptureSource.EMAIL, notify)

    /** A message shared into the app, or pasted by hand. May hold several. */
    suspend fun fromSharedText(context: Context, text: String): List<Outcome> =
        MessageParser.split(text).map { piece ->
            applyCard(context, CardStatementParser.readMessage(piece), notify = false)
                ?: ingest(context, MessageParser.parse(piece), CaptureSource.IMPORT, notify = false)
        }

    /**
     * A statement is not a transaction: it reports the card's limit and dates,
     * and the purchases on it were already alerted one by one. So when a
     * message reads as a statement the card is updated and nothing is logged.
     *
     * Returns null when this was not a statement, leaving it to the message
     * reader.
     */
    private suspend fun applyCard(context: Context, statement: CardStatement, notify: Boolean): Outcome? {
        if (!statement.ok) return null

        val app = PaisaApplication.from(context)
        val today = LocalDate.now()

        var update: AppData.CardUpdate? = null
        app.store.mutate { current ->
            val result = current.applyCardStatement(statement, today)
            update = result
            result.data
        }

        val settled = update ?: return null
        if (!settled.applied) {
            val reason = settled.reason ?: "Card details already known"
            return Outcome(AppData.IngestStatus.DUPLICATE, reason, reason)
        }

        val describe = settled.describe()
        if (notify) {
            Notifier.captured(
                context,
                if (settled.created) "New card found" else "Card details updated",
                describe
            )
        }
        return Outcome(AppData.IngestStatus.CARD_UPDATED, null, describe)
    }

    /**
     * [notify] is off for bulk work — reading ninety days of inbox history
     * should not produce ninety notifications.
     */
    suspend fun ingest(
        context: Context,
        parsed: ParsedMessage,
        source: CaptureSource,
        notify: Boolean = true
    ): Outcome {
        val app = PaisaApplication.from(context)
        val today = LocalDate.now()

        var result: AppData.IngestResult? = null
        app.store.mutate { current ->
            val outcome = current.ingest(parsed, source, today)
            result = outcome
            outcome.data
        }

        val settled = result ?: return Outcome(AppData.IngestStatus.REJECTED, "Nothing happened", "Nothing happened")
        val describe = when (settled.status) {
            AppData.IngestStatus.ADDED -> {
                val amount = parsed.amount?.let { Money.format(it) } ?: "An entry"
                val who = parsed.counterparty?.let { " at $it" } ?: ""
                if (notify) Notifier.captured(context, "$amount$who", "Tap to review it")
                "$amount$who waiting for review"
            }
            AppData.IngestStatus.AUTO_LOGGED -> {
                val amount = settled.txn?.amount?.let { Money.format(it) } ?: "An entry"
                val who = parsed.counterparty?.let { " at $it" } ?: ""
                if (notify) Notifier.captured(context, "$amount$who logged", "Filed the way you did last time")
                "$amount$who logged"
            }
            AppData.IngestStatus.DUPLICATE -> "Already logged"
            AppData.IngestStatus.REJECTED -> settled.reason ?: "Not a transaction"
            /* Reached only through applyCard, which returns before this point. */
            AppData.IngestStatus.CARD_UPDATED -> "Card details updated"
        }
        return Outcome(settled.status, settled.reason, describe)
    }
}
