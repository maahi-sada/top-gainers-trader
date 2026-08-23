package app.paisa.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.paisa.PaisaApplication
import app.paisa.core.AppData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads bank messages already sitting in the inbox, so the app starts with
 * history rather than from today. Safe to run repeatedly: anything already
 * captured is recognised and skipped.
 */
object SmsBackfill {

    data class Report(
        val scanned: Int,
        val captured: Int,
        val logged: Int,
        val duplicates: Int,
        val ignored: Int,
        val cards: Int = 0
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun run(context: Context, days: Int = 90): Report = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext Report(0, 0, 0, 0, 0)

        val since = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        val bodies = mutableListOf<String>()

        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { cursor ->
            val bodyColumn = cursor.getColumnIndex(Telephony.Sms.BODY)
            if (bodyColumn >= 0) {
                while (cursor.moveToNext()) {
                    cursor.getString(bodyColumn)?.takeIf { it.isNotBlank() }?.let { bodies += it }
                }
            }
        }

        var captured = 0
        var logged = 0
        var duplicates = 0
        var ignored = 0
        var cards = 0

        for (body in bodies) {
            when (Capture.fromSms(context, body, notify = false).status) {
                AppData.IngestStatus.ADDED -> captured++
                AppData.IngestStatus.AUTO_LOGGED -> logged++
                AppData.IngestStatus.DUPLICATE -> duplicates++
                AppData.IngestStatus.REJECTED -> ignored++
                AppData.IngestStatus.CARD_UPDATED -> cards++
            }
        }

        PaisaApplication.from(context).secureStore.lastSmsScanMillis = System.currentTimeMillis()
        Report(bodies.size, captured, logged, duplicates, ignored, cards)
    }
}
