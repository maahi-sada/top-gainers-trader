package app.paisa.core

import java.time.LocalDate

/** What the app proposes to log for a captured message, before the user agrees. */
data class Suggestion(
    val type: TxnType,
    val amount: Paise,
    val date: LocalDate,
    val accountId: String?,
    val categoryId: String?,
    val note: String,
    /** A previously learned rule filled in the category. */
    val matchedRule: Boolean,
    /** The last digits in the message picked out the account. */
    val routedByTail: Boolean
)

/**
 * Fills in everything a captured message does not say: which account it belongs
 * to and how it should be filed. Never invents an amount or a direction.
 */
object Suggest {

    private fun normalise(text: String?): String =
        (text ?: "").lowercase().replace("\\s+".toRegex(), " ").trim()

    /** The most specific matching rule: "swiggy instamart" beats plain "swiggy". */
    fun matchRule(parsed: ParsedMessage, rules: List<Rule>): Rule? {
        val haystack = normalise(listOfNotNull(parsed.counterparty, parsed.vpa, parsed.raw).joinToString(" "))
        return rules
            .filter { it.match.isNotBlank() && haystack.contains(normalise(it.match)) }
            .maxByOrNull { it.match.length }
    }

    /**
     * Which of my accounts the message is about. The digits in the message are
     * the strongest signal — and when the message mentions a card, a credit card
     * with those digits is preferred over a bank account sharing them.
     */
    fun accountFor(parsed: ParsedMessage, accounts: List<Account>, rule: Rule?): Pair<String?, Boolean> {
        val live = accounts.filter { !it.archived }
        val tail = parsed.accountTail
        if (tail != null) {
            val matching = live.filter { it.last4 == tail }
            if (matching.isNotEmpty()) {
                val preferred = if (parsed.onCard) {
                    matching.firstOrNull { it.type == AccountType.CREDIT_CARD } ?: matching.first()
                } else {
                    matching.firstOrNull { it.type != AccountType.CREDIT_CARD } ?: matching.first()
                }
                return preferred.id to true
            }
        }
        /* No digits we recognise: a card message still belongs on a card if there
         * is exactly one. */
        if (parsed.onCard) {
            val cards = live.filter { it.type == AccountType.CREDIT_CARD }
            if (cards.size == 1) return cards.first().id to false
        }
        rule?.accountId?.let { return it to false }
        return live.firstOrNull()?.id to false
    }

    fun forMessage(
        parsed: ParsedMessage,
        rules: List<Rule>,
        accounts: List<Account>,
        categories: List<Category>,
        today: LocalDate
    ): Suggestion {
        val rule = matchRule(parsed, rules)
        val (accountId, routedByTail) = accountFor(parsed, accounts, rule)
        val kind = if (parsed.type == TxnType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        val categoryId = rule?.categoryId
            ?: categories.firstOrNull { it.kind == kind && !it.archived }?.id

        return Suggestion(
            type = parsed.type ?: TxnType.EXPENSE,
            amount = parsed.amount ?: 0,
            date = parsed.date ?: today,
            accountId = accountId,
            categoryId = categoryId,
            note = parsed.counterparty ?: parsed.raw.take(60),
            matchedRule = rule != null,
            routedByTail = routedByTail
        )
    }
}
