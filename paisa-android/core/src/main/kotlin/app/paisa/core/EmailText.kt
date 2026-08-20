package app.paisa.core

/**
 * Turning a bank alert email into something the message reader can handle.
 *
 * Alert emails are HTML, wrapped in navigation and legal footers, and usually
 * mention several amounts. So the body is flattened to text, then read both as
 * a whole and line by line, and the most confident reading wins.
 */
object EmailText {

    private val scriptOrStyle = Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val lineBreakTags = Regex("<(br|/p|/div|/tr|/li|/h[1-6]|/table)[^>]*>", RegexOption.IGNORE_CASE)
    private val cellBreakTags = Regex("</(td|th)>", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<[^>]+>")
    private val numericEntity = Regex("&#(x?)([0-9a-fA-F]+);")

    private val namedEntities = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "rsquo" to "'", "lsquo" to "'", "ldquo" to "\"", "rdquo" to "\"",
        "ndash" to "-", "mdash" to "-", "hellip" to "...", "rupee" to "₹", "inr" to "₹"
    )

    fun decodeEntities(text: String): String {
        var out = text
        for ((name, value) in namedEntities) {
            out = out.replace("&$name;", value, ignoreCase = true)
        }
        return numericEntity.replace(out) { m ->
            val radix = if (m.groupValues[1].isEmpty()) 10 else 16
            val code = m.groupValues[2].toIntOrNull(radix)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
    }

    /** Flattens an HTML email body into readable lines. Plain text passes through. */
    fun htmlToText(body: String?): String {
        if (body.isNullOrBlank()) return ""
        var text = body
        if (text.contains('<')) {
            text = scriptOrStyle.replace(text, " ")
            text = lineBreakTags.replace(text, "\n")
            text = cellBreakTags.replace(text, " ¦ ")   // keep table cells on one line, separated
            text = anyTag.replace(text, " ")
        }
        text = decodeEntities(text)
        return text.split("\n")
            .map { it.replace("\\s+".toRegex(), " ").replace(" ¦ ", ": ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private val bankSenders = listOf(
        "hdfcbank", "icicibank", "sbi", "onlinesbi", "sbicard", "axisbank", "kotak",
        "idfcfirstbank", "indusind", "yesbank", "federalbank", "rblbank", "aubank",
        "pnb", "bankofbaroda", "canarabank", "unionbankofindia", "paytm", "phonepe",
        "amazonpay", "onecard", "slicepay", "americanexpress", "citibank", "hsbc",
        "standardchartered", "bobcard", "billdesk", "npci"
    )

    private val alertSubjects = Regex(
        "\\b(transaction|txn|debit|credit|debited|credited|spent|payment|alert|purchase|statement|e-?statement|received)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * Worth reading? Keeps the mailbox scan cheap and avoids parsing newsletters.
     * [extraSenders] lets the user add their own bank if it is not listed.
     */
    fun looksLikeBankMail(from: String?, subject: String?, extraSenders: List<String> = emptyList()): Boolean {
        val sender = (from ?: "").lowercase()
        val knownSender = (bankSenders + extraSenders.map { it.lowercase() }).any { it.isNotBlank() && sender.contains(it) }
        if (knownSender) return true
        /* An unknown sender still counts if the subject reads like an alert and
         * the mail is automated. */
        val automated = sender.contains("alert") || sender.contains("no-reply") ||
            sender.contains("noreply") || sender.contains("donotreply") || sender.contains("statement")
        return automated && alertSubjects.containsMatchIn(subject ?: "")
    }

    /**
     * Reads a whole email. Tries the body as one block and each line on its own,
     * keeping whichever gives the most confident transaction.
     */
    fun bestParse(subject: String?, body: String?): ParsedMessage {
        val text = htmlToText(body)
        val combined = listOfNotNull(subject?.trim()?.ifEmpty { null }, text).joinToString("\n")
        if (combined.isBlank()) return ParsedMessage(ok = false, why = "Empty message")

        val candidates = mutableListOf<ParsedMessage>()
        candidates += MessageParser.parse(combined.replace("\n", " "))

        for (line in combined.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.length < 12) continue
            candidates += MessageParser.parse(trimmed)
        }

        /* Two consecutive lines often split "Amount: Rs 640.50" from
         * "Merchant: SWIGGY", so pairs are worth a look too. */
        val lines = combined.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (i in 0 until lines.size - 1) {
            candidates += MessageParser.parse(lines[i] + " " + lines[i + 1])
        }

        val best = candidates.filter { it.ok }.maxByOrNull { it.confidence }
        return best ?: candidates.firstOrNull { it.why != null } ?: ParsedMessage(ok = false, why = "Nothing readable in this mail")
    }
}
