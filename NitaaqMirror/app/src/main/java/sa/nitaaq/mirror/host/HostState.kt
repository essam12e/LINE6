package sa.nitaaq.mirror.host

import sa.nitaaq.mirror.App
import java.util.concurrent.CopyOnWriteArraySet

/** Observable snapshot of the host service for the UI (all callbacks on the main thread). */
object HostState {
    enum class Phase { STOPPED, RUNNING, PROJECTION_STOPPED }

    data class Approval(val name: String, val digits: String, val fpShort: String)

    @Volatile var phase = Phase.STOPPED
    @Volatile var controllerName: String? = null
    @Volatile var port = 0
    @Volatile var pairingCode: String? = null
    @Volatile var pairingExpiresAt = 0L
    @Volatile var pendingApproval: Approval? = null
    @Volatile var lastError: String? = null
    @Volatile var diagnostics: Map<String, String> = emptyMap()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun observe(l: () -> Unit) { listeners.add(l) }
    fun unobserve(l: () -> Unit) { listeners.remove(l) }

    fun changed() {
        App.main.post { for (l in listeners) l() }
    }
}
