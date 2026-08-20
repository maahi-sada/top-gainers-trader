package app.paisa.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bank alerts, read as they land.
 *
 * A long alert arrives as several parts, so the bodies are joined back together
 * before being read. The work continues past `onReceive` via `goAsync`, since
 * saving touches the disk.
 */
class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank()) return

        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                Capture.fromSms(appContext, body)
            } catch (e: Exception) {
                /* A malformed message must never crash the receiver. */
            } finally {
                pending.finish()
            }
        }
    }
}
