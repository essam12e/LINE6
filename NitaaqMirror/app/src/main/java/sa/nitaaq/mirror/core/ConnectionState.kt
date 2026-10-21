package sa.nitaaq.mirror.core

import sa.nitaaq.mirror.proto.ByeReason

/** What the controller shows to the user. */
sealed class LinkState {
    object Idle : LinkState()
    object Connecting : LinkState()
    /** Pairing in progress; [digits] must match what the host shows. */
    data class Pairing(val digits: String) : LinkState()
    object Connected : LinkState()
    data class Reconnecting(val attempt: Int) : LinkState()
    /** Connected, but the host stopped capturing (user stopped it, screen lock, ...). Needs approval on the host. */
    object HostNeedsApproval : LinkState()
    /** The host ended sharing or removed this device. No automatic retry. */
    data class Ended(val reason: Int) : LinkState()
    /** Gave up (unreachable, wrong code, ...). */
    data class Failed(val reason: FailReason) : LinkState()
}

enum class FailReason { UNREACHABLE, NETWORK_BLOCKED, WRONG_CODE, REJECTED, PAIRING_CLOSED, CERT_MISMATCH, PROTOCOL, TOO_MANY_ATTEMPTS }

/**
 * Controller connection state machine. Pure logic: the networking code feeds it events tagged with the
 * connection id they came from, and events from older connections are ignored (no stale state after a reconnect).
 */
class ConnectionStateMachine(private val maxRetryWindowMs: Long = 120_000) {

    var state: LinkState = LinkState.Idle
        private set
    var connectionId = 0
        private set
    private var userStopped = true
    private var attempt = 0
    private var retryStartedAt = 0L

    /** User pressed connect (or resumed). Returns the id the new connection must use. */
    fun connectRequested(): Int {
        userStopped = false
        attempt = 0
        connectionId++
        state = LinkState.Connecting
        return connectionId
    }

    fun pairing(conn: Int, digits: String) {
        if (conn == connectionId && !userStopped) state = LinkState.Pairing(digits)
    }

    fun connected(conn: Int) {
        if (conn != connectionId || userStopped) return
        attempt = 0
        state = LinkState.Connected
    }

    fun projectionStopped(conn: Int) {
        if (conn == connectionId && !userStopped) state = LinkState.HostNeedsApproval
    }

    fun projectionRunning(conn: Int) {
        if (conn == connectionId && !userStopped && state == LinkState.HostNeedsApproval) state = LinkState.Connected
    }

    /**
     * The connection broke. Returns the delay before the next attempt (and the new connection id via
     * [connectionId]), or null when we must not retry.
     */
    fun connectionLost(conn: Int, nowMs: Long, everConnected: Boolean): Long? {
        if (conn != connectionId || userStopped) return null
        when (state) {
            is LinkState.Ended, is LinkState.Failed, LinkState.Idle -> return null
            else -> {}
        }
        if (attempt == 0) retryStartedAt = nowMs
        // A device that never answered gets a short window (likely wrong network / client isolation);
        // a session that worked before gets the full window (Wi-Fi blips, host app restarting).
        val window = if (everConnected) maxRetryWindowMs else maxRetryWindowMs / 8
        if (nowMs - retryStartedAt > window) {
            state = LinkState.Failed(if (everConnected) FailReason.UNREACHABLE else FailReason.NETWORK_BLOCKED)
            return null
        }
        attempt++
        connectionId++
        state = LinkState.Reconnecting(attempt)
        return backoff(attempt)
    }

    fun hostSaidBye(conn: Int, reason: Int) {
        if (conn != connectionId) return
        userStopped = true
        state = when (reason) {
            ByeReason.NOT_TRUSTED -> LinkState.Failed(FailReason.REJECTED)
            else -> LinkState.Ended(reason)
        }
    }

    fun failed(conn: Int, reason: FailReason) {
        if (conn != connectionId) return
        userStopped = true
        state = LinkState.Failed(reason)
    }

    fun userDisconnected() {
        userStopped = true
        connectionId++
        state = LinkState.Idle
    }

    val wantsConnection: Boolean get() = !userStopped

    companion object {
        private val BACKOFF = longArrayOf(250, 500, 1000, 2000, 3000, 5000)
        fun backoff(attempt: Int): Long = BACKOFF[(attempt - 1).coerceIn(0, BACKOFF.size - 1)]
    }
}
