package app.paisa.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** How to reach the mailbox that receives bank alerts. */
data class EmailSettings(
    val enabled: Boolean = false,
    val host: String = "imap.gmail.com",
    val port: Int = 993,
    val username: String = "",
    val password: String = "",
    val folder: String = "INBOX",
    /** Extra sender fragments to treat as a bank, comma separated. */
    val extraSenders: String = "",
    val lookbackDays: Int = 3
) {
    val senderList: List<String>
        get() = extraSenders.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val configured: Boolean get() = username.isNotBlank() && password.isNotBlank() && host.isNotBlank()
}

/**
 * Mailbox credentials, kept in EncryptedSharedPreferences so the password is
 * held under a key in the device keystore rather than in plain text. It is
 * excluded from cloud backup.
 */
class SecureStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "paisa_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun emailSettings(): EmailSettings = EmailSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        host = prefs.getString(KEY_HOST, "imap.gmail.com").orEmpty(),
        port = prefs.getInt(KEY_PORT, 993),
        username = prefs.getString(KEY_USER, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        folder = prefs.getString(KEY_FOLDER, "INBOX").orEmpty(),
        extraSenders = prefs.getString(KEY_SENDERS, "").orEmpty(),
        lookbackDays = prefs.getInt(KEY_LOOKBACK, 3)
    )

    fun save(settings: EmailSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_HOST, settings.host)
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_USER, settings.username)
            .putString(KEY_PASSWORD, settings.password)
            .putString(KEY_FOLDER, settings.folder)
            .putString(KEY_SENDERS, settings.extraSenders)
            .putInt(KEY_LOOKBACK, settings.lookbackDays)
            .apply()
    }

    /** Newest message already read, so a scan never re-reads the whole mailbox. */
    var lastEmailCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    var lastSmsScanMillis: Long
        get() = prefs.getLong(KEY_LAST_SMS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SMS, value).apply()

    private companion object {
        const val KEY_ENABLED = "email_enabled"
        const val KEY_HOST = "email_host"
        const val KEY_PORT = "email_port"
        const val KEY_USER = "email_user"
        const val KEY_PASSWORD = "email_password"
        const val KEY_FOLDER = "email_folder"
        const val KEY_SENDERS = "email_senders"
        const val KEY_LOOKBACK = "email_lookback"
        const val KEY_LAST_CHECK = "email_last_check"
        const val KEY_LAST_SMS = "sms_last_scan"
    }
}
