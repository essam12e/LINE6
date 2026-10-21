package sa.nitaaq.mirror.host

import sa.nitaaq.mirror.core.CommandGate

/**
 * What the host needs from the touch-control component. Only the "full" edition ships an implementation
 * (an AccessibilityService the user enables); the "controller" edition has none, so it declares no
 * accessibility service at all.
 */
interface TouchControl {
    fun onTouch(gate: CommandGate, gen: Int, payload: ByteArray, receivedAt: Long)
    fun endAllGestures()
    fun nav(action: Int)
    fun text(payload: ByteArray, reply: (Int) -> Unit)
    fun setKeepAwake(on: Boolean)
    val isKeepingAwake: Boolean

    companion object {
        /** Set while the user-enabled service is connected; null otherwise. */
        @Volatile var active: TouchControl? = null

        /** Class name of the service in the full edition (used to check whether the user enabled it). */
        const val SERVICE_CLASS = "sa.nitaaq.mirror.host.ControlService"
    }
}
