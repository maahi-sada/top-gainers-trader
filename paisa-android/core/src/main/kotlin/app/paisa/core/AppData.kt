package app.paisa.core

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Everything the app knows, held as one immutable value.
 *
 * Changes are pure: each `with…` returns a new [AppData] rather than mutating,
 * so the Android layer only has to persist whatever it is handed. All the
 * decision-making — de-duplication, learning, confirming — lives here where it
 * can be tested without a device.
 */
data class AppData(
    val settings: SettingsDto = SettingsDto(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val transactions: List<Txn> = emptyList(),
    val debts: List<Debt> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val recurring: List<Recurring> = emptyList(),
    val inbox: List<InboxItem> = emptyList()
) {

    // ---------- reading ----------

    val liveAccounts: List<Account> get() = accounts.filter { !it.archived }
    val cards: List<Account> get() = liveAccounts.filter { it.type == AccountType.CREDIT_CARD }
    val spendingAccounts: List<Account> get() = liveAccounts.filter { it.type != AccountType.CREDIT_CARD }

    fun account(id: String?): Account? = accounts.firstOrNull { it.id == id }
    fun category(id: String?): Category? = categories.firstOrNull { it.id == id }
    fun debt(id: String?): Debt? = debts.firstOrNull { it.id == id }

    fun balance(accountId: String): Paise = account(accountId)?.let { Ledger.balance(it, transactions) } ?: 0

    /** Money you can actually spend: cards are debts, not balances. */
    val moneyInHand: Paise get() = Ledger.totalBalance(spendingAccounts, transactions)
    val cardDebt: Paise get() = cards.sumOf { Ledger.cardOutstanding(it, transactions) }
    val receivables: Paise get() = Ledger.receivables(debts, transactions)
    val payables: Paise get() = Ledger.payables(debts, transactions)
    /** Cash and bank, less what is owed on cards, plus lending, less borrowing. */
    val netWorth: Paise get() = Ledger.netWorth(accounts, debts, transactions)

    val targets: Targets get() = Targets(settings.dailyEarningTarget, settings.monthlyBudget)

    fun cardStatuses(today: LocalDate): List<CardStatus> =
        cards.map { CardCycle.status(it, transactions, today) }

    fun outstanding(debtId: String): Paise = Ledger.outstanding(debtId, transactions)

    fun monthRange(today: LocalDate, offset: Int = 0): ClosedRange<LocalDate> =
        Ledger.monthRange(today, settings.monthStartDay, offset)

    fun summary(today: LocalDate, offset: Int = 0): Summary {
        val range = monthRange(today, offset)
        return Ledger.summary(transactions, range.start, range.endInclusive)
    }

    fun todayProgress(today: LocalDate): DayProgress =
        DailyTarget.progress(today, settings.dailyEarningTarget, transactions)

    fun streak(today: LocalDate): Int =
        DailyTarget.streak(settings.dailyEarningTarget, transactions, today)

    fun monthPace(today: LocalDate): MonthPace =
        DailyTarget.monthPace(settings.dailyEarningTarget, transactions, today, settings.monthStartDay)

    /** Newest first, which is how every list in the app shows them. */
    fun ledger(): List<Txn> = transactions.sortedWith(compareByDescending<Txn> { it.date }.thenByDescending { it.id })

    private fun knownFingerprints(): Set<String> =
        (transactions.mapNotNull { it.fingerprint } + inbox.map { it.fingerprint }).toSet()

    // ---------- writing ----------

    fun withTransaction(txn: Txn): AppData = copy(transactions = transactions + txn)

    fun withoutTransaction(id: String): AppData = copy(transactions = transactions.filterNot { it.id == id })

    fun replacingTransaction(txn: Txn): AppData =
        copy(transactions = transactions.map { if (it.id == txn.id) txn else it })

    fun withAccount(account: Account): AppData =
        if (accounts.any { it.id == account.id }) copy(accounts = accounts.map { if (it.id == account.id) account else it })
        else copy(accounts = accounts + account)

    fun withCategory(category: Category): AppData =
        if (categories.any { it.id == category.id }) copy(categories = categories.map { if (it.id == category.id) category else it })
        else copy(categories = categories + category)

    fun withDebt(debt: Debt): AppData =
        if (debts.any { it.id == debt.id }) copy(debts = debts.map { if (it.id == debt.id) debt else it })
        else copy(debts = debts + debt)

    fun withoutDebt(id: String): AppData =
        copy(debts = debts.filterNot { it.id == id }, transactions = transactions.filterNot { it.debtId == id })

    fun withRecurring(item: Recurring): AppData =
        if (recurring.any { it.id == item.id }) copy(recurring = recurring.map { if (it.id == item.id) item else it })
        else copy(recurring = recurring + item)

    fun withoutRecurring(id: String): AppData = copy(recurring = recurring.filterNot { it.id == id })

    fun withoutRule(id: String): AppData = copy(rules = rules.filterNot { it.id == id })

    fun withSettings(update: (SettingsDto) -> SettingsDto): AppData = copy(settings = update(settings))

    /** Remembers a counterparty against a category, or updates what it knew. */
    fun learning(match: String?, categoryId: String?, accountId: String?): AppData {
        val key = match?.lowercase()?.replace("\\s+".toRegex(), " ")?.trim()
        if (key.isNullOrBlank() || categoryId == null) return this
        val existing = rules.firstOrNull { it.match == key }
        return if (existing != null) {
            copy(rules = rules.map {
                if (it.id == existing.id) it.copy(categoryId = categoryId, accountId = accountId ?: it.accountId, hits = it.hits + 1)
                else it
            })
        } else {
            copy(rules = rules + Rule(Ids.next("rule"), key, categoryId, accountId, hits = 1))
        }
    }

    /** Ties an account's last digits to the account, so later messages route themselves. */
    fun rememberingTail(tail: String?, accountId: String?): AppData {
        if (tail.isNullOrBlank() || accountId == null) return this
        return copy(settings = settings.copy(accountTails = settings.accountTails + (tail to accountId)))
    }

    // ---------- capture ----------

    enum class IngestStatus { ADDED, AUTO_LOGGED, DUPLICATE, REJECTED }

    data class IngestResult(
        val data: AppData,
        val status: IngestStatus,
        val reason: String? = null,
        val item: InboxItem? = null,
        val txn: Txn? = null
    )

    /**
     * Takes one captured message. Refuses anything unreadable or already seen;
     * logs it outright only when a learned rule says where it belongs and the
     * user has allowed that.
     */
    fun ingest(
        parsed: ParsedMessage,
        source: CaptureSource,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): IngestResult {
        if (!parsed.ok) return IngestResult(this, IngestStatus.REJECTED, parsed.why ?: "Could not read that message")

        val fingerprint = MessageParser.fingerprint(parsed)
        if (fingerprint in knownFingerprints()) {
            return IngestResult(this, IngestStatus.DUPLICATE, "Already logged")
        }

        val item = InboxItem(Ids.next("in"), parsed, source, nowMillis, fingerprint)

        if (settings.autoConfirm && Suggest.matchRule(parsed, rules) != null) {
            val queued = copy(inbox = inbox + item)
            val confirmed = queued.confirmInbox(item.id, today)
            return IngestResult(confirmed.first, IngestStatus.AUTO_LOGGED, txn = confirmed.second)
        }
        return IngestResult(copy(inbox = inbox + item), IngestStatus.ADDED, item = item)
    }

    fun suggestionFor(item: InboxItem, today: LocalDate): Suggestion =
        Suggest.forMessage(item.parsed, rules, liveAccounts, categories, today)
            .let { base ->
                /* A tail learned earlier beats the generic fallback. */
                val learned = item.parsed.accountTail?.let { settings.accountTails[it] }
                if (learned != null && account(learned) != null && !base.routedByTail) {
                    base.copy(accountId = learned, routedByTail = true)
                } else base
            }

    /** Turns a waiting capture into a real entry, and learns from the choice. */
    fun confirmInbox(
        itemId: String,
        today: LocalDate,
        categoryId: String? = null,
        accountId: String? = null,
        amount: Paise? = null,
        date: LocalDate? = null,
        note: String? = null
    ): Pair<AppData, Txn?> {
        val item = inbox.firstOrNull { it.id == itemId } ?: return this to null
        val suggestion = suggestionFor(item, today)

        val txn = Txn(
            id = Ids.next("txn"),
            type = suggestion.type,
            amount = amount ?: suggestion.amount,
            date = date ?: suggestion.date,
            time = item.parsed.time,
            accountId = accountId ?: suggestion.accountId,
            categoryId = categoryId ?: suggestion.categoryId,
            note = note ?: suggestion.note,
            reference = item.parsed.reference,
            method = item.parsed.method,
            merchant = item.parsed.counterparty,
            vpa = item.parsed.vpa,
            bank = item.parsed.bank,
            accountTail = item.parsed.accountTail,
            balanceAfter = item.parsed.balance,
            rawMessage = item.parsed.raw.ifBlank { null },
            capturedAtMillis = item.receivedAtMillis,
            fingerprint = item.fingerprint,
            source = item.source
        )

        val next = withTransaction(txn)
            .learning(item.parsed.vpa ?: item.parsed.counterparty, txn.categoryId, txn.accountId)
            .rememberingTail(item.parsed.accountTail, txn.accountId)
            .copy(inbox = inbox.filterNot { it.id == itemId })

        return next to txn
    }

    fun discardInbox(itemId: String): AppData = copy(inbox = inbox.filterNot { it.id == itemId })

    /**
     * Posts anything a repeating entry owes, catching up days the app was shut.
     * Auto-post templates go straight to the ledger; the rest queue for review.
     */
    fun runRecurring(today: LocalDate, nowMillis: Long = System.currentTimeMillis()): Pair<AppData, Int> {
        var data = this
        var count = 0

        for (template in recurring) {
            val dueDates = Schedule.due(template, today)
            if (dueDates.isEmpty()) continue

            for (due in dueDates) {
                val fingerprint = Schedule.fingerprint(template.id, due)
                if (fingerprint in data.knownFingerprints()) continue
                count++

                data = if (template.autoPost) {
                    data.withTransaction(
                        Txn(
                            id = Ids.next("txn"), type = template.type, amount = template.amount,
                            date = due, accountId = template.accountId, categoryId = template.categoryId,
                            note = template.note.ifBlank { template.label },
                            fingerprint = fingerprint, source = CaptureSource.RECURRING
                        )
                    )
                } else {
                    val parsed = ParsedMessage(
                        ok = true, confidence = 1.0, raw = template.label,
                        type = template.type, amount = template.amount, date = due,
                        counterparty = template.label
                    )
                    data.copy(inbox = data.inbox + InboxItem(
                        Ids.next("in"), parsed, CaptureSource.RECURRING, nowMillis, fingerprint
                    ))
                }
            }

            val last = dueDates.last()
            data = data.withRecurring(
                template.copy(nextDate = Schedule.advance(template.frequency, template.day, last))
            )
        }
        return data to count
    }

    // ---------- storage ----------

    fun toSnapshot(): Snapshot = SnapshotCodec.build(
        settings, accounts, categories, transactions, debts, rules, recurring, inbox
    )

    companion object {
        fun fromSnapshot(snapshot: Snapshot) = AppData(
            settings = snapshot.settings,
            accounts = SnapshotCodec.accounts(snapshot),
            categories = SnapshotCodec.categories(snapshot),
            transactions = SnapshotCodec.transactions(snapshot),
            debts = SnapshotCodec.debts(snapshot),
            rules = SnapshotCodec.rules(snapshot),
            recurring = SnapshotCodec.recurring(snapshot),
            inbox = SnapshotCodec.inbox(snapshot)
        )

        fun decode(text: String): AppData = fromSnapshot(SnapshotCodec.decode(text))

        /** A first run: the accounts and categories most people need on day one. */
        fun seed(): AppData = AppData(
            accounts = listOf(
                Account(Ids.next("acc"), "Cash", AccountType.CASH),
                Account(Ids.next("acc"), "Bank Account", AccountType.BANK),
                Account(Ids.next("acc"), "UPI / Wallet", AccountType.WALLET)
            ),
            categories = Seed.categories()
        )
    }
}

object Ids {
    fun next(prefix: String): String = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(14)
}

internal object Seed {
    /* Named for how the money is actually thought about, not a generic list.
     * Card bill payments are not here on purpose: paying a card is a transfer
     * between your own accounts, so it never counts as spending twice. */
    private val expense = listOf(
        "Home" to 0xFF8B5CF6,
        "Maahi" to 0xFFEC4899,
        "EMI Payments" to 0xFFDC2626,
        "Utility Bills" to 0xFF06B6D4,
        "Groceries" to 0xFF84CC16,
        "Food & Dining" to 0xFFF97316,
        "Transport & Fuel" to 0xFF0EA5E9,
        "Health" to 0xFFEF4444,
        "Shopping" to 0xFFA855F7,
        "Card Fees & Charges" to 0xFFB45309,
        "Interest Paid" to 0xFF991B1B,
        "Other Expense" to 0xFF94A3B8
    )
    private val income = listOf(
        "Day Trading" to 0xFF22C55E,
        "Credit Card Swiping" to 0xFF10B981,
        "Salary" to 0xFF34D399,
        "Business" to 0xFF059669,
        "Interest Received" to 0xFF16A34A,
        "Refund" to 0xFF65A30D,
        "Other Income" to 0xFF94A3B8
    )

    fun categories(): List<Category> =
        expense.map { Category(Ids.next("cat"), it.first, CategoryKind.EXPENSE, it.second.toInt()) } +
            income.map { Category(Ids.next("cat"), it.first, CategoryKind.INCOME, it.second.toInt()) }
}
