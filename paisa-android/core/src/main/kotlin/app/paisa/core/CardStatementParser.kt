package app.paisa.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * What a card statement told us about the card itself.
 *
 * Separate from [ParsedMessage] on purpose: a statement is not a transaction.
 * It carries no money that moved, only the standing facts of the card — its
 * limit, when the bill closes and when it has to be paid.
 */
data class CardStatement(
    val ok: Boolean,
    val why: String? = null,
    val confidence: Double = 0.0,
    val bank: String? = null,
    val last4: String? = null,
    val creditLimit: Paise? = null,
    val availableLimit: Paise? = null,
    val cashLimit: Paise? = null,
    val statementDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val totalDue: Paise? = null,
    val minimumDue: Paise? = null,
    val raw: String = ""
) {
    val statementDay: Int? get() = statementDate?.dayOfMonth
    val dueDay: Int? get() = dueDate?.dayOfMonth

    /** "HDFC statement of 18 Aug 2026", for showing where a card's details came from. */
    fun describe(): String {
        val who = bank ?: "Card"
        val on = statementDate ?: dueDate
        val dated = on?.let { " of " + it.dayOfMonth + " " + monthShort(it.monthValue) + " " + it.year } ?: ""
        return "$who statement$dated"
    }

    private fun monthShort(month: Int): String = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )[(month - 1).coerceIn(0, 11)]
}

/**
 * Reads card statements and bill reminders — from email or SMS — for the
 * card's limit, statement date and due date.
 *
 * Indian issuers all write the same handful of labels ("Total Amount Due",
 * "Payment Due Date", "Credit Limit"), so the reader looks for labels and
 * takes the value that follows, rather than trying to understand sentences.
 */
object CardStatementParser {

    private fun ci(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private enum class Label {
        CREDIT_LIMIT, AVAILABLE_LIMIT, CASH_LIMIT,
        TOTAL_DUE, MIN_DUE,
        DUE_DATE, STATEMENT_DATE, STATEMENT_PERIOD
    }

    /* Order matters only for readability; overlaps are resolved by span, so the
     * longer, more specific label always wins over the one nested inside it. */
    private val labels: List<Pair<Label, Regex>> = listOf(
        Label.AVAILABLE_LIMIT to ci("\\b(?:available|avl\\.?|unutilised|unutilized|remaining|open to buy)\\s*(?:credit\\s*)?limit\\b"),
        Label.CASH_LIMIT to ci("\\b(?:available\\s+)?cash\\s*(?:withdrawal\\s*)?limit\\b"),
        Label.CREDIT_LIMIT to ci("\\b(?:total\\s+|sanctioned\\s+|permanent\\s+|your\\s+|revised\\s+|new\\s+)?credit\\s*limit\\b"),
        Label.MIN_DUE to ci("\\bmin(?:imum)?\\.?\\s*(?:amt\\.?|amount)?\\s*(?:due|payable)\\b"),
        Label.TOTAL_DUE to ci("\\b(?:total\\s*(?:amt\\.?|amount)?\\s*(?:dues?|payable|outstanding)|net\\s*(?:amt\\.?|amount)\\s*due|(?:amt\\.?|amount)\\s*due|new\\s*balance|statement\\s*balance|closing\\s*balance|bill\\s*amount)\\b"),
        Label.DUE_DATE to ci("\\b(?:payment\\s*)?due\\s*date\\b|\\bdue\\s+(?:on|by)\\b|\\bpay(?:able)?\\s+(?:by|before|on or before)\\b|\\blast\\s+date\\s+(?:of|for)\\s+payment\\b"),
        Label.STATEMENT_DATE to ci("\\b(?:statement|bill(?:ing)?)\\s*(?:date|generation\\s*date|generated\\s*on|dated)\\b|\\bdate\\s+of\\s+statement\\b"),
        Label.STATEMENT_PERIOD to ci("\\b(?:statement|bill(?:ing)?)\\s*period\\b|\\bfor\\s+the\\s+period(?:\\s+ending)?\\b|\\bperiod\\s+ending\\b|\\bstatement\\s+for\\b")
    )

    private data class Hit(val label: Label, val start: Int, val end: Int)

    /**
     * Every label in the text, with anything nested inside a longer label
     * dropped: "Available Credit Limit" must not also read as "Credit Limit".
     */
    private fun hits(text: String): List<Hit> {
        val all = mutableListOf<Hit>()
        for ((label, regex) in labels) {
            for (m in regex.findAll(text)) all += Hit(label, m.range.first, m.range.last + 1)
        }
        val ordered = all.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
        val kept = mutableListOf<Hit>()
        for (hit in ordered) {
            if (kept.any { hit.start < it.end && hit.end > it.start }) continue
            kept += hit
        }
        return kept
    }

    /**
     * The text belonging to a label: what follows it, stopping at the next
     * label, at the second line break, or after 90 characters — whichever
     * comes first. Statement tables put the value on the same line or the one
     * below, never further away.
     */
    private fun window(text: String, hit: Hit, all: List<Hit>): String {
        val nextLabel = all.filter { it.start >= hit.end }.minOfOrNull { it.start } ?: text.length
        var end = minOf(text.length, hit.end + 90, nextLabel)
        var breaks = 0
        for (i in hit.end until end) {
            if (text[i] == '\n') {
                breaks++
                if (breaks == 2) { end = i; break }
            }
        }
        return text.substring(hit.end, end)
    }

    // ---------- values ----------

    private val moneyPattern =
        ci("(?:rs|inr|₹)\\.?\\s*([\\d,]+(?:\\.\\d{1,2})?)|([\\d,]+(?:\\.\\d{1,2})?)")
    private val looksLikeDate = ci("^\\s*[\\d,.]*\\s*[-/]")

    /** The first amount in a label's window. Skips anything that is really a date. */
    private fun moneyIn(chunk: String): Paise? {
        for (m in moneyPattern.findAll(chunk)) {
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (raw.isBlank()) continue
            val after = chunk.substring(m.range.last + 1)
            if (m.groupValues[1].isEmpty() && looksLikeDate.containsMatchIn(after)) continue
            val before = chunk.substring(0, m.range.first)
            if (before.endsWith("x", ignoreCase = true) || before.endsWith("*")) continue
            return Money.parse(raw)
        }
        return null
    }

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    private val isoDate = Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b")
    private val dayMonthName = ci("\\b(\\d{1,2})(?:st|nd|rd|th)?[-/.\\s]*([A-Za-z]{3,9})\\.?,?[-/.\\s]*(\\d{2,4})\\b")
    private val monthNameDay = ci("\\b([A-Za-z]{3,9})\\.?[-/.\\s]+(\\d{1,2})(?:st|nd|rd|th)?,?[-/.\\s]+(\\d{2,4})\\b")
    private val numericDate = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b")
    private val dayMonthOnly = ci("\\b(\\d{1,2})(?:st|nd|rd|th)?[-/.\\s]*([A-Za-z]{3,9})\\b")
    private val monthDayOnly = ci("\\b([A-Za-z]{3,9})\\.?[-/.\\s]+(\\d{1,2})(?:st|nd|rd|th)?\\b")

    /**
     * A date anywhere in [chunk]. Indian statements are day-first, but the
     * international issuers write "September 07, 2026", so both are read. When
     * the year is missing the one nearest [today] is used.
     */
    internal fun dateIn(chunk: String, today: LocalDate): LocalDate? {
        isoDate.find(chunk)?.let { m ->
            build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it }
        }
        dayMonthName.find(chunk)?.let { m ->
            val month = months[m.groupValues[2].take(3).lowercase()]
            if (month != null) {
                build(fullYear(m.groupValues[3]), month, m.groupValues[1].toInt())?.let { return it }
            }
        }
        monthNameDay.find(chunk)?.let { m ->
            val month = months[m.groupValues[1].take(3).lowercase()]
            if (month != null) {
                build(fullYear(m.groupValues[3]), month, m.groupValues[2].toInt())?.let { return it }
            }
        }
        numericDate.find(chunk)?.let { m ->
            build(fullYear(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { return it }
        }
        /* "Due Date 05 Sep" — the year is left out often enough to be worth guessing. */
        dayMonthOnly.find(chunk)?.let { m ->
            val month = months[m.groupValues[2].take(3).lowercase()]
            if (month != null) nearestYear(month, m.groupValues[1].toInt(), today)?.let { return it }
        }
        monthDayOnly.find(chunk)?.let { m ->
            val month = months[m.groupValues[1].take(3).lowercase()]
            if (month != null) nearestYear(month, m.groupValues[2].toInt(), today)?.let { return it }
        }
        return null
    }

    /**
     * The end of a billing period: "19 Jul 2026 to 18 Aug 2026" closes on the
     * later of the two, whichever order the issuer wrote them in.
     */
    private fun lastDateIn(chunk: String, today: LocalDate): LocalDate? {
        val found = mutableListOf<LocalDate>()
        for (m in isoDate.findAll(chunk)) {
            build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { found += it }
        }
        for (m in dayMonthName.findAll(chunk)) {
            val month = months[m.groupValues[2].take(3).lowercase()] ?: continue
            build(fullYear(m.groupValues[3]), month, m.groupValues[1].toInt())?.let { found += it }
        }
        for (m in monthNameDay.findAll(chunk)) {
            val month = months[m.groupValues[1].take(3).lowercase()] ?: continue
            build(fullYear(m.groupValues[3]), month, m.groupValues[2].toInt())?.let { found += it }
        }
        for (m in numericDate.findAll(chunk)) {
            build(fullYear(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { found += it }
        }
        return found.maxOrNull() ?: dateIn(chunk, today)
    }

    private fun nearestYear(month: Int, day: Int, today: LocalDate): LocalDate? {
        val candidates = listOf(today.year - 1, today.year, today.year + 1)
            .mapNotNull { build(it, month, day) }
        return candidates.minByOrNull { abs(ChronoUnit.DAYS.between(today, it)) }
    }

    private fun fullYear(raw: String): Int {
        val n = raw.toInt()
        return if (n < 100) 2000 + n else n
    }

    private fun build(year: Int, month: Int, day: Int): LocalDate? = try {
        if (year in 2000..2099) LocalDate.of(year, month, day) else null
    } catch (e: DateTimeException) {
        null
    }

    // ---------- the card ----------

    private val maskedTail = ci("[xX*•]{2,}\\s*(\\d{4})\\b")
    private val endingTail = ci("\\bending(?:\\s+(?:in|with))?\\s*[:#]?\\s*(?:[xX*•]{2,}\\s*)?(\\d{4})\\b")
    private val numberedTail = ci("\\bcard\\s*(?:no\\.?|number)?\\s*[:#]?\\s*(?:[xX*•\\s]{2,})?(\\d{4})\\b(?!\\d)")

    private fun findLast4(text: String): String? =
        endingTail.find(text)?.groupValues?.get(1)
            ?: maskedTail.find(text)?.groupValues?.get(1)
            ?: numberedTail.find(text)?.groupValues?.get(1)

    private val namesACard = ci("\\b(credit card|card statement|statement of your card|cardmember|card account|card no|card ending)\\b")

    /**
     * Adverts sell a limit; statements report one. A real statement always
     * carries a date, so marketing language is only fatal when no date is
     * anywhere in sight.
     */
    private val advert: List<Pair<Regex, String>> = listOf(
        ci("\\b(eligible|pre-?qualified|pre-?approved)\\b") to "Advert, not a statement",
        ci("\\b(up ?to|upto)\\s*(rs\\.?|inr|₹)") to "Advert, not a statement",
        ci("\\b(apply now|click here|loan offer|personal loan|know more)\\b") to "Advert, not a statement",
        ci("\\b(you can (get|avail)|avail (a|an|your))\\b") to "Advert, not a statement"
    )

    /** A limit quoted outside a statement is only believable if the bank says it changed. */
    private val limitChanged =
        ci("\\blimit\\b[^.\\n]{0,40}\\b(?:is|has been|was|now|revised|increased|enhanced|reduced|updated|set)\\b")

    // ---------- reading ----------

    /** An email: subject and body together, the body flattened out of HTML. */
    fun read(subject: String?, body: String?, today: LocalDate = LocalDate.now()): CardStatement {
        val text = listOfNotNull(subject?.trim()?.ifEmpty { null }, EmailText.htmlToText(body))
            .joinToString("\n")
        return readMessage(text, today)
    }

    /** An SMS, or any already-flattened text. */
    fun readMessage(text: String?, today: LocalDate = LocalDate.now()): CardStatement {
        val raw = (text ?: "").replace("\r", "").trim()
        if (raw.isBlank()) return CardStatement(ok = false, why = "Empty message")

        val found = hits(raw)
        if (found.isEmpty()) return CardStatement(ok = false, why = "No card details in this message", raw = raw)

        var creditLimit: Paise? = null
        var availableLimit: Paise? = null
        var cashLimit: Paise? = null
        var totalDue: Paise? = null
        var minimumDue: Paise? = null
        var statementDate: LocalDate? = null
        var dueDate: LocalDate? = null

        for (hit in found) {
            val chunk = window(raw, hit, found)
            when (hit.label) {
                Label.CREDIT_LIMIT -> creditLimit = creditLimit ?: moneyIn(chunk)
                Label.AVAILABLE_LIMIT -> availableLimit = availableLimit ?: moneyIn(chunk)
                Label.CASH_LIMIT -> cashLimit = cashLimit ?: moneyIn(chunk)
                Label.TOTAL_DUE -> totalDue = totalDue ?: moneyIn(chunk)
                Label.MIN_DUE -> minimumDue = minimumDue ?: moneyIn(chunk)
                Label.DUE_DATE -> dueDate = dueDate ?: dateIn(chunk, today)
                Label.STATEMENT_DATE -> statementDate = statementDate ?: dateIn(chunk, today)
                /* A period reads "19 Jul to 18 Aug": the statement closes at the end of it. */
                Label.STATEMENT_PERIOD -> statementDate = statementDate ?: lastDateIn(chunk, today)
            }
        }

        val last4 = findLast4(raw)
        val bank = MessageParser.bankNamed(raw)
        val hasDate = statementDate != null || dueDate != null

        if (!hasDate) {
            advert.firstOrNull { it.first.containsMatchIn(raw) }?.let {
                return CardStatement(ok = false, why = it.second, raw = raw)
            }
        }

        if (last4 == null && !namesACard.containsMatchIn(raw)) {
            return CardStatement(ok = false, why = "Nothing here names a card", raw = raw)
        }

        val limitStated = creditLimit != null && (hasDate || limitChanged.containsMatchIn(raw))
        if (!hasDate && !limitStated) {
            return CardStatement(ok = false, why = "No statement date, due date or limit", raw = raw)
        }
        if (bank == null && last4 == null) {
            return CardStatement(ok = false, why = "Could not tell which card this is", raw = raw)
        }

        var confidence = 0.4
        if (last4 != null) confidence += 0.20
        if (dueDate != null) confidence += 0.15
        if (statementDate != null) confidence += 0.15
        if (creditLimit != null) confidence += 0.10

        return CardStatement(
            ok = true,
            confidence = minOf(1.0, confidence),
            bank = bank,
            last4 = last4,
            creditLimit = if (limitStated) creditLimit else null,
            availableLimit = availableLimit,
            cashLimit = cashLimit,
            statementDate = statementDate,
            dueDate = dueDate,
            totalDue = totalDue,
            minimumDue = minimumDue,
            raw = raw
        )
    }
}
