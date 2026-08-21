package app.paisa

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.paisa.capture.Capture
import app.paisa.capture.EmailWorker
import app.paisa.capture.SmsBackfill
import app.paisa.core.AppData
import app.paisa.core.CaptureSource
import app.paisa.core.DebtDirection
import app.paisa.core.EntryInput
import app.paisa.core.TxnType
import app.paisa.core.applyEntry
import app.paisa.data.EmailSettings
import app.paisa.ui.PaisaTheme
import app.paisa.ui.screens.AccountScreen
import app.paisa.ui.screens.CardsScreen
import app.paisa.ui.screens.DebtDetailScreen
import app.paisa.ui.screens.DebtsScreen
import app.paisa.ui.screens.EntryScreen
import app.paisa.ui.screens.EntryTarget
import app.paisa.ui.screens.InboxScreen
import app.paisa.ui.screens.LedgerScreen
import app.paisa.ui.screens.PasteScreen
import app.paisa.ui.screens.SettingsScreen
import app.paisa.ui.screens.TargetsScreen
import app.paisa.ui.screens.TodayScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openInbox = intent?.getBooleanExtra(EXTRA_OPEN_INBOX, false) == true
        val sharedText = if (intent?.action == Intent.ACTION_SEND) intent?.getStringExtra(Intent.EXTRA_TEXT) else null

        setContent {
            PaisaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PaisaRoot(startOnInbox = openInbox, sharedText = sharedText)
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_INBOX = "open_inbox"
    }
}

private enum class Tab(val label: String) {
    TODAY("Today"), LEDGER("Ledger"), INBOX("Inbox"), DEBTS("Debts"), CARDS("Cards")
}

private sealed interface Overlay {
    data object None : Overlay
    data class Entry(val target: EntryTarget, val preset: EntryInput?) : Overlay
    data class AccountEdit(val accountId: String?) : Overlay
    data class DebtDetail(val debtId: String) : Overlay
    data object Targets : Overlay
    data object Settings : Overlay
    data object Paste : Overlay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaisaRoot(startOnInbox: Boolean, sharedText: String?) {
    val context = LocalContext.current
    val app = remember { PaisaApplication.from(context) }
    val scope = rememberCoroutineScope()

    val data by app.store.data.collectAsState()
    val today = remember { LocalDate.now() }

    var tab by remember { mutableStateOf(if (startOnInbox) Tab.INBOX else Tab.TODAY) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var emailSettings by remember { mutableStateOf(app.secureStore.emailSettings()) }

    var smsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    fun mutate(block: (AppData) -> AppData) {
        scope.launch { app.store.mutate(block) }
    }

    LaunchedEffect(Unit) {
        app.store.ensureLoaded()
        app.store.mutate { it.runRecurring(today).first }
    }

    /* A message shared into Paisa from the SMS app or anywhere else. */
    LaunchedEffect(sharedText) {
        val text = sharedText?.trim().orEmpty()
        if (text.isNotEmpty()) {
            val outcomes = Capture.fromSharedText(context, text)
            val added = outcomes.count { it.status == AppData.IngestStatus.ADDED }
            status = if (added > 0) "$added captured from what you shared" else outcomes.firstOrNull()?.describe
            if (added > 0) tab = Tab.INBOX
        }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        smsGranted = granted[Manifest.permission.READ_SMS] == true
        status = if (smsGranted) "Message reading is on. Try reading your recent messages." else "Message reading was not allowed."
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notifications are a convenience; capture works either way */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = app.store.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
                status = "Backup saved"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    }
                    if (text.isNullOrBlank()) "Could not read that file"
                    else "${app.store.import(text, replace = false)} entries added"
                } catch (e: Exception) {
                    "That file could not be read as a Paisa backup"
                }
            }
        }
    }

    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (overlay == Overlay.None) tab.label else "Paisa") },
                actions = {
                    if (overlay == Overlay.None) {
                        IconButton(onClick = { overlay = Overlay.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (overlay == Overlay.None) {
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = {
                                if (entry == Tab.INBOX && data.inbox.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text(data.inbox.size.toString()) } }) {
                                        Icon(iconFor(entry), contentDescription = entry.label)
                                    }
                                } else {
                                    Icon(iconFor(entry), contentDescription = entry.label)
                                }
                            },
                            label = { Text(entry.label) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (overlay == Overlay.None) {
                FloatingActionButton(onClick = { overlay = Overlay.Entry(EntryTarget.New, null) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add an entry")
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = overlay) {
                is Overlay.None -> when (tab) {
                    Tab.TODAY -> TodayScreen(
                        data = data,
                        today = today,
                        onOpenTargets = { overlay = Overlay.Targets },
                        onOpenLedger = { tab = Tab.LEDGER },
                        onOpenDebts = { tab = Tab.DEBTS },
                        onOpenCards = { tab = Tab.CARDS },
                        onOpenInbox = { tab = Tab.INBOX },
                        onEditTxn = { overlay = Overlay.Entry(EntryTarget.Existing(it), null) }
                    )

                    Tab.LEDGER -> LedgerScreen(data, today) {
                        overlay = Overlay.Entry(EntryTarget.Existing(it), null)
                    }

                    Tab.INBOX -> InboxScreen(
                        data = data,
                        today = today,
                        onConfirm = { id -> mutate { it.confirmInbox(id, today).first } },
                        onConfirmAll = {
                            mutate { current2 ->
                                current2.inbox.map { it.id }.fold(current2) { acc, id -> acc.confirmInbox(id, today).first }
                            }
                        },
                        onDiscard = { id -> mutate { it.discardInbox(id) } },
                        onEdit = { overlay = Overlay.Entry(EntryTarget.Confirming(it), null) },
                        onPasteMessage = { overlay = Overlay.Paste }
                    )

                    Tab.DEBTS -> DebtsScreen(
                        data = data,
                        today = today,
                        onAddDebt = { direction ->
                            overlay = Overlay.Entry(
                                EntryTarget.New,
                                EntryInput(
                                    type = if (direction == DebtDirection.OWED_TO_ME) TxnType.LEND else TxnType.BORROW,
                                    amount = 0, date = today, accountId = data.liveAccounts.firstOrNull()?.id
                                )
                            )
                        },
                        onOpenDebt = { overlay = Overlay.DebtDetail(it) }
                    )

                    Tab.CARDS -> CardsScreen(
                        data = data,
                        today = today,
                        onAddCard = { overlay = Overlay.AccountEdit(null) },
                        onEditCard = { overlay = Overlay.AccountEdit(it) },
                        onPayBill = { cardId, amount ->
                            overlay = Overlay.Entry(
                                EntryTarget.New,
                                EntryInput(
                                    type = TxnType.TRANSFER, amount = amount, date = today,
                                    accountId = data.spendingAccounts.firstOrNull()?.id,
                                    toAccountId = cardId, note = "Card bill"
                                )
                            )
                        }
                    )
                }

                is Overlay.Entry -> key(current) {
                    val existingId = (current.target as? EntryTarget.Existing)?.txnId
                    EntryScreen(
                        data = data,
                        target = current.target,
                        today = today,
                        preset = current.preset,
                        onSave = { input ->
                            mutate {
                                it.applyEntry(
                                    input = input,
                                    editingTxnId = existingId,
                                    confirmingInboxId = (current.target as? EntryTarget.Confirming)?.inboxId
                                )
                            }
                            overlay = Overlay.None
                        },
                        onDelete = existingId?.let {
                            {
                                mutate { d -> d.withoutTransaction(it) }
                                overlay = Overlay.None
                            }
                        },
                        onCancel = { overlay = Overlay.None }
                    )
                }

                is Overlay.AccountEdit -> key(current.accountId) { AccountScreen(
                    data = data,
                    accountId = current.accountId,
                    onSave = { account ->
                        mutate { it.withAccount(account) }
                        overlay = Overlay.None
                    },
                    onCancel = { overlay = Overlay.None }
                ) }

                is Overlay.DebtDetail -> DebtDetailScreen(
                    data = data,
                    debtId = current.debtId,
                    today = today,
                    onRecordPayment = {
                        val debt = data.debt(current.debtId)
                        overlay = Overlay.Entry(
                            EntryTarget.New,
                            EntryInput(
                                type = if (debt?.direction == DebtDirection.OWED_TO_ME) TxnType.COLLECT else TxnType.SETTLE,
                                amount = data.outstanding(current.debtId), date = today,
                                accountId = data.spendingAccounts.firstOrNull()?.id,
                                debtId = current.debtId
                            )
                        )
                    },
                    onLendMore = {
                        val debt = data.debt(current.debtId)
                        overlay = Overlay.Entry(
                            EntryTarget.New,
                            EntryInput(
                                type = if (debt?.direction == DebtDirection.OWED_TO_ME) TxnType.LEND else TxnType.BORROW,
                                amount = 0, date = today,
                                accountId = data.spendingAccounts.firstOrNull()?.id,
                                debtId = current.debtId
                            )
                        )
                    },
                    onDelete = {
                        mutate { it.withoutDebt(current.debtId) }
                        overlay = Overlay.None
                    },
                    onBack = { overlay = Overlay.None },
                    onEditTxn = { overlay = Overlay.Entry(EntryTarget.Existing(it), null) }
                )

                is Overlay.Targets -> TargetsScreen(data, today) { daily, budget ->
                    mutate { it.withSettings { s -> s.copy(dailyEarningTarget = daily, monthlyBudget = budget) } }
                    status = "Targets saved"
                    overlay = Overlay.None
                }

                is Overlay.Paste -> PasteScreen(
                    onCapture = { text ->
                        scope.launch {
                            val outcomes = Capture.fromSharedText(context, text)
                            val added = outcomes.count { it.status == AppData.IngestStatus.ADDED }
                            status = if (added > 0) "$added captured" else outcomes.firstOrNull()?.describe
                            overlay = Overlay.None
                            tab = Tab.INBOX
                        }
                    },
                    onCancel = { overlay = Overlay.None }
                )

                is Overlay.Settings -> SettingsScreen(
                    data = data,
                    smsGranted = smsGranted,
                    emailSettings = emailSettings,
                    captureStatus = status,
                    busy = busy,
                    onRequestSmsPermission = {
                        smsPermissionLauncher.launch(
                            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                        )
                    },
                    onBackfillSms = {
                        scope.launch {
                            busy = true
                            val report = SmsBackfill.run(context)
                            busy = false
                            status = "Read ${report.scanned} messages: ${report.captured} to review, " +
                                "${report.logged} logged, ${report.duplicates} already had, ${report.ignored} not transactions"
                        }
                    },
                    onSaveEmail = { updated ->
                        emailSettings = updated
                        app.secureStore.save(updated)
                        if (updated.enabled && updated.configured) EmailWorker.schedule(context) else EmailWorker.cancel(context)
                        status = "Email settings saved"
                    },
                    onCheckEmailNow = {
                        scope.launch {
                            busy = true
                            status = try {
                                val report = withContext(Dispatchers.IO) { EmailWorker.scan(context) }
                                "Read ${report.read} emails: ${report.captured} to review, ${report.logged} logged, " +
                                    "${report.duplicates} already had"
                            } catch (e: Exception) {
                                "Could not reach the mailbox: ${e.message}"
                            }
                            busy = false
                        }
                    },
                    onToggleAutoConfirm = { on ->
                        mutate { it.withSettings { s -> s.copy(autoConfirm = on) } }
                    },
                    onMonthStartDay = { day ->
                        mutate { it.withSettings { s -> s.copy(monthStartDay = day) } }
                    },
                    onOpenTargets = { overlay = Overlay.Targets },
                    onPasteMessage = { overlay = Overlay.Paste },
                    onAddAccount = { overlay = Overlay.AccountEdit(null) },
                    onEditAccount = { overlay = Overlay.AccountEdit(it) },
                    onForgetRule = { id -> mutate { it.withoutRule(id) } },
                    onExport = { exportLauncher.launch("paisa-backup-$today.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                )
            }
        }
    }
}

private fun iconFor(tab: Tab) = when (tab) {
    Tab.TODAY -> Icons.Filled.Home
    Tab.LEDGER -> Icons.AutoMirrored.Filled.List
    Tab.INBOX -> Icons.Filled.Notifications
    Tab.DEBTS -> Icons.Filled.SwapHoriz
    Tab.CARDS -> Icons.Filled.CreditCard
}
