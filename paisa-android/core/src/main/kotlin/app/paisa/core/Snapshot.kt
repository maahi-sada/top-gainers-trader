package app.paisa.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The whole database as one JSON document, in exactly the shape the Paisa web
 * app writes. A backup taken on the phone opens in the browser and the other
 * way round, so the two stay usable side by side.
 *
 * Fields the other side does not know about simply ride along untouched.
 */
@Serializable
data class Snapshot(
    val schema: Int = 1,
    val settings: SettingsDto = SettingsDto(),
    val accounts: List<AccountDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val transactions: List<TxnDto> = emptyList(),
    val debts: List<DebtDto> = emptyList(),
    val rules: List<RuleDto> = emptyList(),
    val recurring: List<RecurringDto> = emptyList(),
    val inbox: List<InboxDto> = emptyList()
)

@Serializable
data class SettingsDto(
    val currency: String = "INR",
    val locale: String = "en-IN",
    val theme: String = "auto",
    val monthStartDay: Int = 1,
    val monthlyBudget: Long = 0,
    /** Android only; the web app ignores it. */
    val dailyEarningTarget: Long = 0,
    val autoConfirm: Boolean = false,
    val accountTails: Map<String, String> = emptyMap()
)

@Serializable
data class AccountDto(
    val id: String,
    val name: String,
    val type: String = "bank",
    val openingBalance: Long = 0,
    val creditLimit: Long = 0,
    val statementDay: Int = 1,
    val dueDay: Int = 1,
    val last4: String? = null,
    val archived: Boolean = false
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val kind: String = "expense",
    /** "#rrggbb", as the web app writes it. */
    val color: String = "#94a3b8",
    val archived: Boolean = false
)

@Serializable
data class TxnDto(
    val id: String,
    val type: String,
    val amount: Long,
    val date: String,
    /** "HH:mm:ss", absent when the time was never known. */
    val time: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val debtId: String? = null,
    val interest: Long = 0,
    val note: String = "",
    val reference: String? = null,
    val method: String? = null,
    val merchant: String? = null,
    val vpa: String? = null,
    val bank: String? = null,
    val accountTail: String? = null,
    val balanceAfter: Long? = null,
    val rawMessage: String? = null,
    val capturedAtMillis: Long? = null,
    @SerialName("fp") val fingerprint: String? = null,
    val source: String = "manual",
    val createdAt: String? = null
)

@Serializable
data class DebtDto(
    val id: String,
    /** "owe" = I owe them, "owed" = they owe me. */
    val direction: String,
    val person: String,
    val note: String = "",
    val dueDate: String? = null,
    val archived: Boolean = false
)

@Serializable
data class RuleDto(
    val id: String,
    val match: String,
    val categoryId: String? = null,
    val accountId: String? = null,
    val hits: Int = 0
)

@Serializable
data class RecurringDto(
    val id: String,
    val label: String,
    val type: String = "expense",
    val amount: Long = 0,
    val categoryId: String? = null,
    val accountId: String? = null,
    val note: String = "",
    @SerialName("freq") val frequency: String = "monthly",
    val day: Int = 1,
    val nextDate: String,
    val autoPost: Boolean = false,
    val paused: Boolean = false
)

@Serializable
data class InboxDto(
    val id: String,
    @SerialName("fp") val fingerprint: String,
    val source: String = "sms",
    val receivedAt: String,
    val parsed: ParsedDto
)

@Serializable
data class ParsedDto(
    val ok: Boolean = true,
    val why: String? = null,
    val confidence: Double = 0.0,
    val raw: String = "",
    val type: String? = null,
    val amount: Long? = null,
    val date: String? = null,
    val time: String? = null,
    val counterparty: String? = null,
    val vpa: String? = null,
    val accountTail: String? = null,
    val bank: String? = null,
    val method: String? = null,
    @SerialName("ref") val reference: String? = null,
    val balance: Long? = null,
    val onCard: Boolean = false
)

/** Converts between the stored document and the domain types. */
object SnapshotCodec {

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(snapshot: Snapshot): String = json.encodeToString(Snapshot.serializer(), snapshot)

    fun decode(text: String): Snapshot = json.decodeFromString(Snapshot.serializer(), text)

    // ---------- small mappings ----------

    private fun accountType(raw: String) = when (raw.lowercase()) {
        "cash" -> AccountType.CASH
        "wallet" -> AccountType.WALLET
        "card", "credit_card", "creditcard" -> AccountType.CREDIT_CARD
        else -> AccountType.BANK
    }

    private fun accountType(type: AccountType) = when (type) {
        AccountType.CASH -> "cash"
        AccountType.WALLET -> "wallet"
        AccountType.CREDIT_CARD -> "card"
        AccountType.BANK -> "bank"
    }

    private fun txnType(raw: String) = when (raw.lowercase()) {
        "income" -> TxnType.INCOME
        "transfer" -> TxnType.TRANSFER
        "lend" -> TxnType.LEND
        "collect" -> TxnType.COLLECT
        "borrow" -> TxnType.BORROW
        "settle" -> TxnType.SETTLE
        else -> TxnType.EXPENSE
    }

    private fun source(raw: String) = when (raw.lowercase()) {
        "sms" -> CaptureSource.SMS
        "email" -> CaptureSource.EMAIL
        "recurring" -> CaptureSource.RECURRING
        "csv", "import", "share", "paste" -> CaptureSource.IMPORT
        else -> CaptureSource.MANUAL
    }

    private fun frequency(raw: String) = when (raw.lowercase()) {
        "daily" -> Frequency.DAILY
        "weekly" -> Frequency.WEEKLY
        else -> Frequency.MONTHLY
    }

    /** "#f97316" or "#fff97316" to an ARGB int; anything unreadable becomes grey. */
    fun parseColor(raw: String?): Int {
        val hex = (raw ?: "").trim().removePrefix("#")
        return try {
            when (hex.length) {
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> Ledger.UNCATEGORISED_COLOR
            }
        } catch (e: NumberFormatException) {
            Ledger.UNCATEGORISED_COLOR
        }
    }

    fun formatColor(argb: Int): String = "#%06x".format(argb and 0xFFFFFF)

    private fun date(raw: String?): LocalDate? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    private fun time(raw: String?): LocalTime? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    // ---------- document to domain ----------

    fun accounts(s: Snapshot): List<Account> = s.accounts.map {
        Account(
            id = it.id, name = it.name, type = accountType(it.type),
            openingBalance = it.openingBalance, creditLimit = it.creditLimit,
            statementDay = it.statementDay, dueDay = it.dueDay,
            last4 = it.last4, archived = it.archived
        )
    }

    fun categories(s: Snapshot): List<Category> = s.categories.map {
        Category(
            id = it.id, name = it.name,
            kind = if (it.kind.equals("income", true)) CategoryKind.INCOME else CategoryKind.EXPENSE,
            color = parseColor(it.color), archived = it.archived
        )
    }

    fun transactions(s: Snapshot): List<Txn> = s.transactions.mapNotNull { dto ->
        val on = date(dto.date) ?: return@mapNotNull null
        Txn(
            id = dto.id, type = txnType(dto.type), amount = dto.amount, date = on,
            time = time(dto.time),
            accountId = dto.accountId, toAccountId = dto.toAccountId,
            categoryId = dto.categoryId, debtId = dto.debtId, interest = dto.interest,
            note = dto.note,
            reference = dto.reference, method = dto.method, merchant = dto.merchant,
            vpa = dto.vpa, bank = dto.bank, accountTail = dto.accountTail,
            balanceAfter = dto.balanceAfter, rawMessage = dto.rawMessage,
            capturedAtMillis = dto.capturedAtMillis,
            fingerprint = dto.fingerprint, source = source(dto.source)
        )
    }

    fun debts(s: Snapshot): List<Debt> = s.debts.map {
        Debt(
            id = it.id,
            direction = if (it.direction.equals("owe", true)) DebtDirection.I_OWE else DebtDirection.OWED_TO_ME,
            person = it.person, note = it.note, dueDate = date(it.dueDate), archived = it.archived
        )
    }

    fun rules(s: Snapshot): List<Rule> = s.rules.map { Rule(it.id, it.match, it.categoryId, it.accountId, it.hits) }

    fun recurring(s: Snapshot): List<Recurring> = s.recurring.mapNotNull { dto ->
        val next = date(dto.nextDate) ?: return@mapNotNull null
        Recurring(
            id = dto.id, label = dto.label, type = txnType(dto.type), amount = dto.amount,
            categoryId = dto.categoryId, accountId = dto.accountId, note = dto.note,
            frequency = frequency(dto.frequency), day = dto.day, nextDate = next,
            autoPost = dto.autoPost, paused = dto.paused
        )
    }

    fun inbox(s: Snapshot): List<InboxItem> = s.inbox.map { dto ->
        InboxItem(
            id = dto.id,
            parsed = ParsedMessage(
                ok = dto.parsed.ok, why = dto.parsed.why, confidence = dto.parsed.confidence,
                raw = dto.parsed.raw,
                type = dto.parsed.type?.let { txnType(it) },
                amount = dto.parsed.amount, date = date(dto.parsed.date),
                time = time(dto.parsed.time),
                counterparty = dto.parsed.counterparty, vpa = dto.parsed.vpa,
                accountTail = dto.parsed.accountTail, bank = dto.parsed.bank,
                method = dto.parsed.method, reference = dto.parsed.reference,
                balance = dto.parsed.balance, onCard = dto.parsed.onCard
            ),
            source = source(dto.source),
            receivedAtMillis = runCatching { Instant.parse(dto.receivedAt).toEpochMilli() }.getOrDefault(0L),
            fingerprint = dto.fingerprint
        )
    }

    fun targets(s: Snapshot): Targets = Targets(s.settings.dailyEarningTarget, s.settings.monthlyBudget)

    // ---------- domain to document ----------

    fun build(
        settings: SettingsDto,
        accounts: List<Account>,
        categories: List<Category>,
        transactions: List<Txn>,
        debts: List<Debt>,
        rules: List<Rule>,
        recurring: List<Recurring>,
        inbox: List<InboxItem>
    ) = Snapshot(
        schema = 1,
        settings = settings,
        accounts = accounts.map {
            AccountDto(it.id, it.name, accountType(it.type), it.openingBalance, it.creditLimit,
                it.statementDay, it.dueDay, it.last4, it.archived)
        },
        categories = categories.map {
            CategoryDto(it.id, it.name, if (it.kind == CategoryKind.INCOME) "income" else "expense",
                formatColor(it.color), it.archived)
        },
        transactions = transactions.map {
            TxnDto(
                id = it.id, type = it.type.name.lowercase(), amount = it.amount,
                date = it.date.toString(), time = it.time?.toString(),
                accountId = it.accountId, toAccountId = it.toAccountId,
                categoryId = it.categoryId, debtId = it.debtId, interest = it.interest,
                note = it.note, reference = it.reference, method = it.method,
                merchant = it.merchant, vpa = it.vpa, bank = it.bank,
                accountTail = it.accountTail, balanceAfter = it.balanceAfter,
                rawMessage = it.rawMessage, capturedAtMillis = it.capturedAtMillis,
                fingerprint = it.fingerprint, source = it.source.name.lowercase()
            )
        },
        debts = debts.map {
            DebtDto(it.id, if (it.direction == DebtDirection.I_OWE) "owe" else "owed",
                it.person, it.note, it.dueDate?.toString(), it.archived)
        },
        rules = rules.map { RuleDto(it.id, it.match, it.categoryId, it.accountId, it.hits) },
        recurring = recurring.map {
            RecurringDto(it.id, it.label, it.type.name.lowercase(), it.amount, it.categoryId,
                it.accountId, it.note, it.frequency.name.lowercase(), it.day,
                it.nextDate.toString(), it.autoPost, it.paused)
        },
        inbox = inbox.map { item ->
            InboxDto(
                id = item.id, fingerprint = item.fingerprint, source = item.source.name.lowercase(),
                receivedAt = Instant.ofEpochMilli(item.receivedAtMillis).toString(),
                parsed = ParsedDto(
                    ok = item.parsed.ok, why = item.parsed.why, confidence = item.parsed.confidence,
                    raw = item.parsed.raw, type = item.parsed.type?.name?.lowercase(),
                    amount = item.parsed.amount, date = item.parsed.date?.toString(),
                    time = item.parsed.time?.toString(),
                    counterparty = item.parsed.counterparty, vpa = item.parsed.vpa,
                    accountTail = item.parsed.accountTail, bank = item.parsed.bank,
                    method = item.parsed.method, reference = item.parsed.reference,
                    balance = item.parsed.balance, onCard = item.parsed.onCard
                )
            )
        }
    )
}
