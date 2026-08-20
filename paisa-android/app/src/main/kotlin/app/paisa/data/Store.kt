package app.paisa.data

import android.content.Context
import app.paisa.core.AppData
import app.paisa.core.SnapshotCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The whole ledger as one JSON file, held in memory and written out after every
 * change.
 *
 * A personal ledger is small — years of daily entries come to a few hundred
 * kilobytes — so keeping it in memory keeps every screen instant and lets the
 * tested calculations in `core` work on plain lists. The file is written to a
 * temporary name and then renamed, so a crash mid-write cannot leave a
 * half-saved ledger behind.
 */
class Store(private val context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val temp = File(context.filesDir, "$FILE_NAME.tmp")
    private val mutex = Mutex()
    private var loaded = false

    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data.asStateFlow()

    /** Reads the file, seeding a fresh ledger on first run. Safe to call repeatedly. */
    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            _data.value = withContext(Dispatchers.IO) { readFile() }
            loaded = true
        }
    }

    private fun readFile(): AppData {
        if (!file.exists()) return AppData.seed()
        return try {
            AppData.decode(file.readText())
        } catch (e: Exception) {
            /* Never lose a ledger to a parse error: keep the bad file so it can
             * be recovered by hand, and carry on with a fresh one. */
            runCatching { file.copyTo(File(context.filesDir, "$FILE_NAME.broken"), overwrite = true) }
            AppData.seed()
        }
    }

    /** Applies a change and persists the result. Returns what was stored. */
    suspend fun mutate(block: (AppData) -> AppData): AppData {
        ensureLoaded()
        return mutex.withLock {
            val next = block(_data.value)
            _data.value = next
            withContext(Dispatchers.IO) { write(next) }
            next
        }
    }

    private fun write(value: AppData) {
        temp.writeText(SnapshotCodec.encode(value.toSnapshot()))
        if (!temp.renameTo(file)) {
            /* renameTo can refuse on some devices; fall back to a direct write. */
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    suspend fun export(): String {
        ensureLoaded()
        return SnapshotCodec.encode(_data.value.toSnapshot())
    }

    /**
     * Restores a backup — including one exported by the web app.
     * [replace] wipes what is here; otherwise records the device has not seen
     * are added and existing ids are left alone.
     */
    suspend fun import(text: String, replace: Boolean): Int {
        val incoming = AppData.decode(text)
        var added = 0
        mutate { current ->
            if (replace) {
                added = incoming.transactions.size
                incoming
            } else {
                val knownTxns = current.transactions.map { it.id }.toSet()
                val knownAccounts = current.accounts.map { it.id }.toSet()
                val knownCategories = current.categories.map { it.id }.toSet()
                val knownDebts = current.debts.map { it.id }.toSet()
                val newTxns = incoming.transactions.filterNot { it.id in knownTxns }
                added = newTxns.size
                current.copy(
                    accounts = current.accounts + incoming.accounts.filterNot { it.id in knownAccounts },
                    categories = current.categories + incoming.categories.filterNot { it.id in knownCategories },
                    debts = current.debts + incoming.debts.filterNot { it.id in knownDebts },
                    transactions = current.transactions + newTxns
                )
            }
        }
        return added
    }

    companion object {
        const val FILE_NAME = "paisa.json"
    }
}
