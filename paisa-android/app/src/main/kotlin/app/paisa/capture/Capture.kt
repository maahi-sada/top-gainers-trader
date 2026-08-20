package app.paisa.capture

import android.content.Context
import app.paisa.PaisaApplication
import app.paisa.core.AppData
import app.paisa.core.CaptureSource
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
        ingest(context, MessageParser.parse(body), CaptureSource.SMS, notify)

    suspend fun fromEmail(context: Context, subject: String?, body: String?, notify: Boolean = true): Outcome =
        ingest(context, EmailText.bestParse(subject, body), CaptureSource.EMAIL, notify)

    /** A message shared into the app, or pasted by hand. May hold several. */
    suspend fun fromSharedText(context: Context, text: String): List<Outcome> =
        MessageParser.split(text).map { ingest(context, MessageParser.parse(it), CaptureSource.IMPORT, notify = false) }

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
        }
        return Outcome(settled.status, settled.reason, describe)
    }
}
