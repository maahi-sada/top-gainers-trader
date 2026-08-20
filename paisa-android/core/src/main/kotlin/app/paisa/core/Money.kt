package app.paisa.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Every amount in Paisa is an integer count of paise. Never a Double: ₹420.50
 * must still be ₹420.50 after a thousand round trips. */
typealias Paise = Long

object Money {

    private val INDIA = Locale.Builder().setLanguage("en").setRegion("IN").build()

    /** "1,234.56", "1234.5", " ₹ 1,20,000 " -> paise. Null when it is not a number. */
    fun parse(text: String?): Paise? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.replace("[₹,\\s]".toRegex(), "").trim()
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return null
        val negative = cleaned.startsWith("(") && cleaned.endsWith(")")
        val bare = cleaned.trim('(', ')')
        return try {
            val value = BigDecimal(bare).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toLong()
            if (negative) -value else value
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun ofRupees(rupees: Double): Paise = BigDecimal.valueOf(rupees)
        .multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toLong()

    fun toRupees(paise: Paise): BigDecimal =
        BigDecimal(paise).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)

    /** ₹1,23,456.78 — Indian digit grouping, which en-IN gives us for free. */
    fun format(paise: Paise, withPaise: Boolean = true): String {
        val nf = NumberFormat.getCurrencyInstance(INDIA)
        nf.minimumFractionDigits = if (withPaise) 2 else 0
        nf.maximumFractionDigits = if (withPaise) 2 else 0
        return nf.format(toRupees(paise))
    }

    /** +₹500.00 / −₹500.00, using a real minus sign rather than a hyphen. */
    fun formatSigned(paise: Paise, sign: Int): String {
        val body = format(kotlin.math.abs(paise))
        return when {
            sign > 0 -> "+$body"
            sign < 0 -> "−$body"
            else -> body
        }
    }

    /** ₹1.2L / ₹45.3K / ₹2.4Cr, for tiles too small for the full number. */
    fun formatShort(paise: Paise): String {
        val rupees = kotlin.math.abs(paise) / 100.0
        val sign = if (paise < 0) "−" else ""
        val body = when {
            rupees >= 1_00_00_000 -> trimZeros(rupees / 1_00_00_000) + "Cr"
            rupees >= 1_00_000 -> trimZeros(rupees / 1_00_000) + "L"
            rupees >= 1_000 -> trimZeros(rupees / 1_000) + "K"
            else -> if (rupees % 1.0 == 0.0) rupees.toLong().toString() else String.format(Locale.ROOT, "%.2f", rupees)
        }
        return "$sign₹$body"
    }

    private fun trimZeros(value: Double): String =
        String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
}
