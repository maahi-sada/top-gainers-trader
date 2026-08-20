package app.paisa

import android.app.Application
import android.content.Context
import app.paisa.capture.EmailWorker
import app.paisa.capture.Notifier
import app.paisa.data.SecureStore
import app.paisa.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

class PaisaApplication : Application() {

    lateinit var store: Store
        private set

    lateinit var secureStore: SecureStore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        secureStore = SecureStore(this)
        Notifier.createChannel(this)

        scope.launch {
            store.ensureLoaded()
            /* Anything a repeating entry owes while the app was closed. */
            store.mutate { it.runRecurring(LocalDate.now()).first }
        }

        if (secureStore.emailSettings().let { it.enabled && it.configured }) {
            EmailWorker.schedule(this)
        }
    }

    companion object {
        fun from(context: Context): PaisaApplication =
            context.applicationContext as PaisaApplication
    }
}
