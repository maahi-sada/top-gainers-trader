package app.paisa.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/** What a bank message turned out to mean. */
data class ParsedMessage(
    val ok: Boolean,
    val why: String? = null,
    val confidence: Double = 0.0,
    val raw: String = "",
    val type: TxnType? = null,
    val amount: Paise? = null,
    val date: LocalDate? = null,
    /** Time of day, when the message states one. */
    val time: LocalTime? = null,
    val counterparty: String? = null,
    val vpa: String? = null,
    val accountTail: String? = null,
    val bank: String? = null,
    val method: String? = null,
    val reference: String? = null,
    val balance: Paise? = null,
    /** The message describes a credit card transaction rather than a bank one. */
    val onCard: Boolean = false
)

/**
 * Reads Indian bank, UPI and wallet messages — SMS or the text of an email
 * alert — and works out what happened.
 *
 * Deliberately conservative: it would rather return `ok = false` with a reason
 * than invent an entry. Amount and direction are both required; everything
 * else only raises confidence.
 */
object MessageParser {

    private fun ci(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Mentions money but is not a completed transaction. */
    private val junk: List<Pair<Regex, String>> = listOf(
        ci("\\b(otp|one[- ]time password|verification code)\\b") to "OTP message",
        ci("\\bdo not share\\b.*\\b(otp|pin|cvv)\\b") to "OTP message",
        ci("\\b(will be|shall be) (debited|charged|deducted)\\b") to "Upcoming charge, not done yet",
        ci("\\b(due|payable|outstanding) (on|by|amount)\\b") to "Bill reminder",
        ci("\\b(failed|declined|reversed|unsuccessful|could not be processed)\\b") to "Transaction did not go through",
        ci("\\b(request(ed)? (money|payment)|collect request|has requested)\\b") to "Payment request, not a payment",
        ci("\\b(offer|cashback|apply now|click here|loan offer)\\b") to "Promotional message",
        ci("\\b(eligible|pre-?qualified|pre-?approved)\\b") to "Advert, not a transaction",
        ci("\\b(personal|instant|business|gold|home|car|education)\\s+loan\\b") to "Loan advert",
        ci("\\b(up ?to|upto)\\s*(rs\\.?|inr|₹)") to "Advert, not a transaction",
        ci("%\\s*p\\.?\\s?a\\.?") to "Advert quoting an interest rate",
        ci("\\b(limited period|valid till|valid until|know more|hurry|t&c|terms (and|&) conditions)\\b") to "Promotional message",
        ci("\\b(download|install)\\s+(our|the)?\\s?app\\b") to "Promotional message",
        ci("\\b(will be|shall be|would be)\\s+(credited|deposited)\\b") to "Not credited yet",
        ci("\\b(mini statement|statement is ready|e-statement)\\b") to "Statement notice"
    )

    private val outWords = ci("\\b(debited|debit(?:ed)? from|spent|paid to|paid at|paid|sent|withdrawn|withdrawal|purchase(?:d)?|deducted|transferred to|utilised|charged)\\b")
    private val inWords = ci("\\b(credited|credit(?:ed)? to|received|deposited|refund(?:ed)?|added to|has been credited|money received)\\b")

    private val banks: List<Pair<String, Regex>> = listOf(
        "HDFC" to ci("hdfc"), "ICICI" to ci("icici"), "Axis" to ci("axis"), "Kotak" to ci("kotak"),
        "IDFC" to ci("idfc"), "IndusInd" to ci("indusind"), "Canara" to ci("canara"),
        "SBI" to ci("\\bsbi\\b|state bank"), "PNB" to ci("\\bpnb\\b|punjab national"),
        "BoB" to ci("\\bbob\\b|bank of baroda"), "Union" to ci("union bank"),
        "Yes Bank" to ci("yes bank"), "Federal" to ci("federal bank"), "RBL" to ci("\\brbl\\b"),
        "AU" to ci("\\bau small\\b"), "Paytm" to ci("paytm"), "PhonePe" to ci("phonepe"),
        "Google Pay" to ci("google pay|\\bgpay\\b|g-pay"), "Amazon Pay" to ci("amazon pay"),
        "Airtel" to ci("airtel payments"),
        // Card issuers that are not banks the user holds an account with.
        "Amex" to ci("american express|\\bamex\\b"),
        "OneCard" to ci("\\bonecard\\b|one ?card"),
        "Slice" to ci("\\bslice\\b"),
        "Citi" to ci("\\bciti(?:bank)?\\b"),
        "HSBC" to ci("\\bhsbc\\b"),
        "StanChart" to ci("standard chartered|\\bsc\\s?bank\\b"),
        "BOBCARD" to ci("\\bbobcard\\b"),
        "IDBI" to ci("\\bidbi\\b"),
        "Bajaj" to ci("bajaj (?:finserv|finance)"),
        "IndianBank" to ci("indian bank"),
        "Central Bank" to ci("central bank of india")
    )

    private val methods: List<Pair<String, Regex>> = listOf(
        "upi" to ci("\\b(upi|vpa)\\b|@[a-z]{2,}\\b"),
        "card" to ci("\\b(card|credit card|debit card|pos)\\b"),
        "atm" to ci("\\b(atm|cash withdrawal)\\b"),
        "netbanking" to ci("\\b(net ?banking|imps|neft|rtgs)\\b")
    )

    private val cardHint = ci("\\b(credit card|card\\s*(?:no\\.?|number)?\\s*[xX*•]|on card|your card|card ending)\\b")

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    // ---------- amount ----------

    private val amountPattern = ci("(?:(?:rs|inr)\\.?\\s*|₹\\s*)([\\d,]+(?:\\.\\d{1,2})?)|([\\d,]+(?:\\.\\d{1,2})?)\\s*(?:rs\\b|inr\\b|rupees)")
    private val bareAmountPattern = ci("\\b(?:debited|credited|paid|sent|received|withdrawn|spent|transferred)\\s+(?:by|for|of|with|amount)?\\s*([\\d,]+(?:\\.\\d{1,2})?)\\b")
    private val balanceLeadIn = ci("\\b(bal|balance|avl|available|limit|due|emi|remaining|left)\\b[^.]{0,12}$")

    private data class Amount(val paise: Paise, val index: Int)

    /**
     * A message usually carries the transaction figure and an available balance,
     * sometimes a credit limit too. Anything introduced by a balance word is
     * discarded and the first survivor wins.
     */
    private fun findAmounts(text: String): List<Amount> {
        val found = mutableListOf<Amount>()
        for (m in amountPattern.findAll(text)) {
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val paise = Money.parse(raw) ?: continue
            val before = text.substring(maxOf(0, m.range.first - 28), m.range.first)
            if (balanceLeadIn.containsMatchIn(before)) continue
            found += Amount(paise, m.range.first)
        }
        if (found.isEmpty()) {
            val bare = bareAmountPattern.find(text)
            if (bare != null) {
                Money.parse(bare.groupValues[1])?.let { found += Amount(it, bare.range.first) }
            }
        }
        return found
    }

    // ---------- date ----------

    private val isoDate = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")
    private val monthNameDate = ci("\\b(\\d{1,2})[-/. ]?([A-Za-z]{3,9})[-/. ]?(\\d{2,4})\\b")
    private val numericDate = Regex("\\b(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})\\b")

    private fun findDate(text: String): LocalDate? {
        isoDate.find(text)?.let { m ->
            return build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        monthNameDate.find(text)?.let { m ->
            val month = months[m.groupValues[2].take(3).lowercase()]
            if (month != null) return build(fullYear(m.groupValues[3]), month, m.groupValues[1].toInt())
        }
        /* Indian messages are day-first. */
        numericDate.find(text)?.let { m ->
            return build(fullYear(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        return null
    }

    /**
     * Time of day from the message: "at 14:32:11", "on 19-08-26 14:32",
     * "at 02:35 PM". Bank alerts quote it often enough to be worth keeping.
     */
    private val timePattern =
        ci("\\b(?:at\\s+)?([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\s*([AaPp][Mm])?\\b")

    private fun findTime(text: String): LocalTime? {
        val m = timePattern.find(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        val second = m.groupValues[3].toIntOrNull() ?: 0
        val meridiem = m.groupValues[4].lowercase()

        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        if (hour > 23) return null

        return runCatching { LocalTime.of(hour, minute, second) }.getOrNull()
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

    // ---------- counterparty ----------

    private val noise = ci("^(your|the|a|an|account|a/c|ac|bank|upi|vpa|ref|txn|info|card|no|dear|customer|avl|bal|my|me|atm|pos)$")
    private val leadingFiller = ci("^(your|my|the|a|an)\\s+")
    /** A capture holding these describes an account, not a person or a shop. */
    private val notAName = ci("\\b(bank|card|a/c|acct?|account|wallet|balance|limit)\\b|[xX*•]{2,}\\d")
    private val startsWithMoney = ci("^(rs|inr|₹)\\b")
    private val companySuffix = ci("\\b(pvt|private|ltd|limited|india|technologies|services)\\b\\.?")
    private val vpaPattern = ci("\\b([a-z0-9][a-z0-9._-]{1,})@([a-z]{2,})\\b")
    private val domainSuffix = ci("\\.(com|in|org|net)$")

    private const val STOP =
        "(?:on|for|via|using|from|by|with|towards|ref|refno|upi|dt|date|to|in|at|of|and|your|a/c|acct?|account|card|bank|wallet|avl|bal|info|txn|trf)"
    private const val NAME = "([A-Za-z0-9&'.\\- ]{2,40}?)"

    private fun namePattern(prefix: String): Regex =
        ci("$prefix\\s+(?:vpa\\s+)?$NAME(?=\\s+$STOP\\b|\\s*[.,;:()\\[\\]/]|$)")

    private val namePatterns: List<Regex> = listOf(
        namePattern("\\b(?:spent|paid|purchase(?:d)?)\\s+(?:at|to)"),
        namePattern("\\b(?:trf|transferred)\\s+to"),
        namePattern("\\b(?:to|towards)"),
        namePattern("\\bby"),
        namePattern("\\bfrom"),
        namePattern("\\bat"),
        ci("\\bUPI\\s*[/:-]\\s*(?:[A-Z]{2,3}\\s*[/:-]\\s*)?(?:\\d+\\s*[/:-]\\s*)?([A-Za-z0-9&'.\\- ]{2,40}?)(?=[/:;,.]|$)"),
        ci("\\b(?:info|desc|narration)\\s*[:=-]\\s*([A-Za-z0-9&'.\\- ]{2,40}?)(?=[;,.]|$)"),
        Regex("[-–]\\s*([A-Z][A-Z ]{2,30})(?=\\s*[.,;]|\\s*$)")
    )

    private val accountish = ci("^\\s*(bank|card|a/c|acct?|account|wallet)\\b")

    internal fun cleanName(raw: String?): String? {
        if (raw == null) return null
        var s = raw.replace("[*_]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .trim('.', ',', ';', ':', '-', ' ')

        while (leadingFiller.containsMatchIn(s)) s = leadingFiller.replace(s, "")

        if (notAName.containsMatchIn(s)) return null
        if (startsWithMoney.containsMatchIn(s)) return null
        if (s.isNotEmpty() && (s[0].isDigit() || s[0] == '.' || s[0] == ',')) return null

        s = companySuffix.replace(s, "").replace("\\s+".toRegex(), " ").trim()

        if (s.length < 2) return null
        if (noise.matches(s)) return null
        if (s.all { it.isDigit() }) return null

        /* SHOUTING MERCHANT and lowercase vpa handles both become Title Case. */
        if (s == s.uppercase() || s == s.lowercase()) s = titleCase(s)
        return s
    }

    private fun titleCase(s: String): String = s.lowercase().split(" ").joinToString(" ") { word ->
        if (word.isEmpty()) word else word.replaceFirstChar { it.uppercase() }
    }

    private data class Who(val name: String?, val vpa: String?)

    private fun findCounterparty(text: String): Who {
        /* A UPI handle is the most reliable signal there is. */
        val handleMatch = vpaPattern.find(text)
        if (handleMatch != null && !domainSuffix.containsMatchIn(handleMatch.value)) {
            val handle = handleMatch.groupValues[1]
            if (!handle.matches(Regex("\\d{6,}"))) {
                val pretty = cleanName(handle.replace("[._-]+".toRegex(), " ").replace("\\d{4,}".toRegex(), "").trim())
                if (pretty != null) return Who(pretty, handleMatch.value.lowercase())
            }
            return Who(cleanName(handle) ?: handle, handleMatch.value.lowercase())
        }

        for (pattern in namePatterns) {
            val m = pattern.find(text) ?: continue
            /* "refunded to your Kotak Bank Card XX5678" — what follows shows the
             * capture was describing an account, not naming a shop. */
            val tailStart = m.range.last + 1
            val tail = text.substring(tailStart, minOf(text.length, tailStart + 14))
            if (accountish.containsMatchIn(tail)) continue
            val name = cleanName(m.groupValues[1])
            if (name != null) return Who(name, null)
        }
        return Who(null, null)
    }

    // ---------- other fields ----------

    private val maskedTail = ci("\\b(?:a/c|acc?t?|account|card|ac)\\.?\\s*(?:no\\.?|number)?\\s*[:#]?\\s*[xX*•]+\\s*(\\d{3,6})\\b")
    private val plainTail = ci("\\b(?:a/c|acc?t?|account|card|ac)\\.?\\s*(?:no\\.?|number)?\\s*[:#]?\\s*(\\d{3,6})\\b(?!\\d)")
    private val looseTail = ci("\\bX+(\\d{3,6})\\b")
    private val refPattern = ci("\\b(?:ref(?:erence)?|txn|transaction|utr|rrn|upi ref(?: no)?)\\.?\\s*(?:no\\.?|id|#)?\\s*[:.\\-]?\\s*([A-Za-z0-9]{6,25})\\b")
    private val balancePattern = ci("\\b(?:avl|available|a/c|closing)?\\s*(?:bal|balance)\\b[^\\d₹]{0,14}(?:rs|inr|₹)?\\.?\\s*([\\d,]+(?:\\.\\d{1,2})?)")

    private fun findAccountTail(text: String): String? =
        maskedTail.find(text)?.groupValues?.get(1)
            ?: plainTail.find(text)?.groupValues?.get(1)
            ?: looseTail.find(text)?.groupValues?.get(1)

    /** Which bank or issuer this text is about, ignoring any UPI handle in it. */
    fun bankNamed(text: String?): String? {
        val raw = (text ?: "").ifBlank { return null }
        return firstLabel(banks, vpaPattern.replace(raw, " "))
    }

    private fun firstLabel(list: List<Pair<String, Regex>>, text: String): String? =
        list.firstOrNull { it.second.containsMatchIn(text) }?.first

    /**
     * A real bank alert always says which account or instrument moved — masked
     * digits, a UPI handle, a reference number, or at least the words "a/c" or
     * "card". An advert quotes an amount and nothing else, which is exactly how
     * a loan offer ends up looking like a large credit.
     */
    private val instrumentWords =
        ci("\\b(a/c|acct|account|card|wallet|vpa|upi|imps|neft|rtgs|atm|ref|utr|rrn|txn)\\b")

    private fun namesAnInstrument(
        text: String,
        tail: String?,
        vpa: String?,
        reference: String?,
        bank: String?
    ): Boolean = tail != null || vpa != null || reference != null || bank != null ||
        instrumentWords.containsMatchIn(text)

    // ---------- main ----------

    fun parse(text: String?): ParsedMessage {
        val raw = (text ?: "").replace("\\s+".toRegex(), " ").trim()
        if (raw.isEmpty()) return ParsedMessage(ok = false, why = "Empty message")

        junk.firstOrNull { it.first.containsMatchIn(raw) }?.let {
            return ParsedMessage(ok = false, why = it.second, raw = raw)
        }

        val amounts = findAmounts(raw)
        if (amounts.isEmpty()) return ParsedMessage(ok = false, why = "No amount found", raw = raw)

        val outMatch = outWords.find(raw)
        val inMatch = inWords.find(raw)
        val type = when {
            outMatch != null && inMatch != null ->
                if (outMatch.range.first < inMatch.range.first) TxnType.EXPENSE else TxnType.INCOME
            outMatch != null -> TxnType.EXPENSE
            inMatch != null -> TxnType.INCOME
            else -> return ParsedMessage(ok = false, why = "Could not tell if money went in or out", raw = raw)
        }

        val who = findCounterparty(raw)
        val date = findDate(raw)
        val time = findTime(raw)
        val tail = findAccountTail(raw)
        val reference = refPattern.find(raw)?.groupValues?.get(1)
        val bank = firstLabel(banks, vpaPattern.replace(raw, " "))

        /* Confidence: amount and direction are table stakes; the rest is how
         * much the user still has to fill in by hand. */
        if (!namesAnInstrument(raw, tail, who.vpa, reference, bank)) {
            return ParsedMessage(
                ok = false,
                why = "No account or reference — reads like an advert, not a transaction",
                raw = raw
            )
        }

        var confidence = 0.5
        if (date != null) confidence += 0.20
        if (who.name != null) confidence += 0.15
        if (tail != null) confidence += 0.10
        if (reference != null) confidence += 0.05

        return ParsedMessage(
            ok = true,
            confidence = minOf(1.0, confidence),
            raw = raw,
            type = type,
            amount = amounts.first().paise,
            date = date,
            time = time,
            counterparty = who.name,
            vpa = who.vpa,
            accountTail = tail,
            bank = bank,
            method = firstLabel(methods, raw),
            reference = reference,
            balance = balancePattern.find(raw)?.groupValues?.get(1)?.let { Money.parse(it) },
            onCard = cardHint.containsMatchIn(raw)
        )
    }

    /** Splits a pasted or forwarded blob into individual messages. */
    fun split(blob: String?): List<String> {
        val text = (blob ?: "").replace("\r", "")
        val paragraphs = text.split(Regex("\n\\s*\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.size > 1) return paragraphs

        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val moneyLines = lines.count { ci("(?:rs|inr|₹)\\.?\\s*[\\d,]").containsMatchIn(it) }
        if (lines.size > 1 && moneyLines > 1) return lines

        return paragraphs.ifEmpty { if (text.isBlank()) emptyList() else listOf(text.trim()) }
    }

    /** Stable identity for a message, so the same one is never logged twice. */
    fun fingerprint(p: ParsedMessage): String {
        p.reference?.let { return "ref:" + it.lowercase() }
        return listOf("fp", p.type?.name ?: "", p.amount?.toString() ?: "",
            p.date?.toString() ?: "", (p.counterparty ?: "").lowercase()).joinToString(":")
    }
}
