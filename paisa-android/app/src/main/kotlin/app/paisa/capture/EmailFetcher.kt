package app.paisa.capture

import app.paisa.core.EmailText
import app.paisa.data.EmailSettings
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/**
 * Pulls recent bank alert emails over IMAP.
 *
 * Read-only: the folder is opened READ_ONLY so nothing is marked read, moved or
 * deleted in the mailbox. Only messages that look like bank alerts are opened
 * at all.
 */
object EmailFetcher {

    data class Mail(val from: String, val subject: String, val body: String, val receivedAtMillis: Long)

    class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun fetch(settings: EmailSettings, sinceMillis: Long, limit: Int = 100): List<Mail> {
        if (!settings.configured) throw FetchException("Mailbox details are incomplete")

        val properties = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", settings.host)
            put("mail.imaps.port", settings.port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "30000")
        }

        val session = Session.getInstance(properties)
        val store = session.getStore("imaps")
        val collected = mutableListOf<Mail>()

        try {
            store.connect(settings.host, settings.port, settings.username, settings.password)
            val folder = store.getFolder(settings.folder.ifBlank { "INBOX" })
            folder.open(Folder.READ_ONLY)
            try {
                val cutoff = java.util.Date(sinceMillis)
                val matches = folder.search(ReceivedDateTerm(ComparisonTerm.GE, cutoff))

                for (message in matches.takeLast(limit)) {
                    val from = senderOf(message)
                    val subject = message.subject.orEmpty()
                    if (!EmailText.looksLikeBankMail(from, subject, settings.senderList)) continue

                    val body = runCatching { bodyOf(message) }.getOrDefault("")
                    if (body.isBlank() && subject.isBlank()) continue

                    collected += Mail(
                        from = from,
                        subject = subject,
                        body = body,
                        receivedAtMillis = message.receivedDate?.time ?: System.currentTimeMillis()
                    )
                }
            } finally {
                runCatching { folder.close(false) }
            }
        } catch (e: Exception) {
            throw FetchException(e.message ?: "Could not reach the mailbox", e)
        } finally {
            runCatching { store.close() }
        }

        return collected
    }

    private fun senderOf(message: Message): String {
        val addresses = runCatching { message.from }.getOrNull() ?: return ""
        return addresses.filterIsInstance<InternetAddress>().joinToString(" ") { it.address.orEmpty() }
            .ifBlank { addresses.joinToString(" ") { it.toString() } }
    }

    /** Walks a possibly nested message, preferring the HTML part. */
    private fun bodyOf(part: Part): String {
        if (part.isMimeType("text/plain")) return part.content?.toString().orEmpty()
        if (part.isMimeType("text/html")) return part.content?.toString().orEmpty()

        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as? Multipart ?: return ""
            var plain = ""
            for (index in 0 until multipart.count) {
                val child = multipart.getBodyPart(index)
                if (child.isMimeType("text/html")) return child.content?.toString().orEmpty()
                if (child.isMimeType("text/plain") && plain.isEmpty()) plain = child.content?.toString().orEmpty()
                if (child.isMimeType("multipart/*")) {
                    val nested = bodyOf(child)
                    if (nested.isNotBlank()) return nested
                }
            }
            return plain
        }
        return ""
    }
}
