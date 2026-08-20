package app.paisa.capture

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.paisa.PaisaApplication
import app.paisa.core.AppData
import java.util.concurrent.TimeUnit

/** Checks the mailbox on a schedule, and on demand from Settings. */
class EmailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = PaisaApplication.from(applicationContext)
        val settings = app.secureStore.emailSettings()
        if (!settings.enabled || !settings.configured) return Result.success()

        return try {
            val report = scan(applicationContext)
            if (report.captured > 0 || report.logged > 0) {
                Notifier.captured(
                    applicationContext,
                    "${report.captured + report.logged} from your email",
                    if (report.captured > 0) "Tap to review" else "Logged automatically"
                )
            }
            Result.success()
        } catch (e: Exception) {
            /* A flaky connection is worth one retry, not a stream of errors. */
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    data class Report(val read: Int, val captured: Int, val logged: Int, val duplicates: Int, val ignored: Int)

    companion object {
        private const val UNIQUE_NAME = "paisa-email-scan"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EmailWorker>(3, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        /**
         * Reads new mail and captures whatever it finds. Shared by the scheduled
         * run and the "check now" button, so both behave identically.
         */
        suspend fun scan(context: Context): Report {
            val app = PaisaApplication.from(context)
            val secure = app.secureStore
            val settings = secure.emailSettings()

            val lookbackMillis = settings.lookbackDays.coerceAtLeast(1).toLong() * 24 * 60 * 60 * 1000
            val since = maxOf(
                secure.lastEmailCheckMillis,
                System.currentTimeMillis() - lookbackMillis
            )

            val mails = EmailFetcher.fetch(settings, since)

            var captured = 0
            var logged = 0
            var duplicates = 0
            var ignored = 0

            for (mail in mails) {
                when (Capture.fromEmail(context, mail.subject, mail.body, notify = false).status) {
                    AppData.IngestStatus.ADDED -> captured++
                    AppData.IngestStatus.AUTO_LOGGED -> logged++
                    AppData.IngestStatus.DUPLICATE -> duplicates++
                    AppData.IngestStatus.REJECTED -> ignored++
                }
            }

            secure.lastEmailCheckMillis = System.currentTimeMillis()
            return Report(mails.size, captured, logged, duplicates, ignored)
        }
    }
}
