package sa.nitaaq.mirror.ctrl

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import sa.nitaaq.mirror.App
import sa.nitaaq.mirror.Prefs
import sa.nitaaq.mirror.core.ConnectionStateMachine
import sa.nitaaq.mirror.core.FailReason
import sa.nitaaq.mirror.core.Fingerprint
import sa.nitaaq.mirror.core.LinkState
import sa.nitaaq.mirror.core.PairingCrypto
import sa.nitaaq.mirror.core.RollingStats
import sa.nitaaq.mirror.net.Identity
import sa.nitaaq.mirror.net.LocalNet
import sa.nitaaq.mirror.net.PinnedTrustManager
import sa.nitaaq.mirror.net.Tls
import sa.nitaaq.mirror.net.TrustedPeer
import sa.nitaaq.mirror.net.TrustedStore
import sa.nitaaq.mirror.proto.FrameIO
import sa.nitaaq.mirror.proto.Hello
import sa.nitaaq.mirror.proto.HelloMode
import sa.nitaaq.mirror.proto.Msg
import sa.nitaaq.mirror.proto.PairResult
import sa.nitaaq.mirror.proto.Pointer
import sa.nitaaq.mirror.proto.StatusCodec
import sa.nitaaq.mirror.proto.TextOp
import sa.nitaaq.mirror.proto.TouchMsg
import sa.nitaaq.mirror.proto.VideoConfig
import sa.nitaaq.mirror.proto.VideoCtrl
import sa.nitaaq.mirror.proto.Welcome
import sa.nitaaq.mirror.proto.payload
import sa.nitaaq.mirror.proto.reader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

data class HostTarget(val name: String, val host: String, val port: Int, val fpHex: String?, val pairCode: String?)

/**
 * Controller-side connection to one host. Lives in the app process (not the Activity), so leaving the remote
 * screen only pauses video; coming back resumes it, reconnecting if the link died meanwhile.
 */
object RemoteSession {

    interface Listener {
        fun onState(state: LinkState) {}
        fun onVideoConfig(cfg: VideoConfig) {}
        fun onStatus(status: Map<String, String>) {}
        fun onTextResult(code: Int) {}
    }

    private lateinit var appCtx: Context
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val sm = ConnectionStateMachine()
    private val worker = HandlerThread("nm-client").apply { start() }
    private val workerHandler = Handler(worker.looper)

    @Volatile var target: HostTarget? = null
        private set
    @Volatile private var conn: Conn? = null
    @Volatile private var everConnected = false
    @Volatile var lastConfig: VideoConfig? = null
        private set
    @Volatile var lastStatus: Map<String, String> = emptyMap()
        private set
    @Volatile var hostName: String = ""
        private set
    @Volatile private var videoPaused = false

    val decoder = VideoDecoder { requestKeyFrame() }

    // diagnostics
    val rtt = RollingStats(60)
    val captureToArrival = RollingStats(120)
    @Volatile private var clockOffsetNs: Long? = null
    @Volatile private var bestRttNs = Long.MAX_VALUE
    @Volatile var rxBytes = 0L
        private set

    val state: LinkState get() = sm.state

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyState() {
        val s = sm.state
        App.main.post { listeners.forEach { it.onState(s) } }
    }

    fun connect(ctx: Context, t: HostTarget) {
        appCtx = ctx.applicationContext
        conn?.close()
        conn = null
        target = t
        everConnected = false
        hostName = t.name
        lastConfig = null
        resetStats()
        val id = sm.connectRequested()
        notifyState()
        launch(id)
    }

    /** Call when the remote screen becomes visible again. */
    fun ensureConnected() {
        if (target == null || !sm.wantsConnection) return
        if (conn == null || conn?.closed == true) {
            val s = sm.state
            if (s !is LinkState.Reconnecting && s !is LinkState.Connecting) {
                val delay = sm.connectionLost(sm.connectionId, SystemClock.elapsedRealtime(), everConnected) ?: return
                notifyState()
                workerHandler.postDelayed({ launch(sm.connectionId) }, delay)
            }
        }
    }

    fun disconnect() {
        sm.userDisconnected()
        conn?.close()
        conn = null
        decoder.release()
        notifyState()
    }

    private fun launch(id: Int) {
        val t = target ?: return
        if (id != sm.connectionId || !sm.wantsConnection) return
        val c = Conn(id, t)
        conn = c
        Thread({ c.run() }, "nm-client-conn").start()
    }

    private fun resetStats() {
        rtt.clear(); captureToArrival.clear(); clockOffsetNs = null; bestRttNs = Long.MAX_VALUE
    }

    // ------------------------------------------------------------------ commands

    private var seq = 0

    fun sendTouch(action: Int, changedId: Int, pointers: List<Pointer>) {
        val c = conn ?: return
        if (!c.ready) return
        c.send(Msg.TOUCH, TouchMsg(action, changedId, ++seq, SystemClock.uptimeMillis(), pointers).encode())
    }

    fun sendNav(action: Int) {
        conn?.takeIf { it.ready }?.send(Msg.NAV, byteArrayOf(action.toByte()))
    }

    fun sendText(op: Int, text: String = "") {
        conn?.takeIf { it.ready }?.send(Msg.TEXT, payload { writeByte(op); if (op == TextOp.INSERT || op == TextOp.REPLACE_ALL) writeUTF(text) })
    }

    fun requestKeyFrame() {
        conn?.takeIf { it.ready }?.send(Msg.VIDEO_CTRL, byteArrayOf(VideoCtrl.KEYFRAME.toByte()))
    }

    fun setVideoPaused(paused: Boolean) {
        videoPaused = paused
        conn?.takeIf { it.ready }?.send(Msg.VIDEO_CTRL, byteArrayOf((if (paused) VideoCtrl.PAUSE else VideoCtrl.RESUME).toByte()))
    }

    fun sendQualityIfConnected() {
        if (::appCtx.isInitialized) sendQuality()
    }

    fun sendQuality() {
        val p = Prefs.of(appCtx)
        conn?.takeIf { it.ready }?.send(Msg.VIDEO_CTRL, byteArrayOf(VideoCtrl.QUALITY.toByte(), p.quality.code.toByte(), p.fps.toByte()))
    }

    val isReady: Boolean get() = conn?.ready == true

    fun clockOffsetKnown() = clockOffsetNs != null

    // ------------------------------------------------------------------ connection

    private class PairFailure(val reason: FailReason) : Exception()

    private class Conn(val id: Int, val t: HostTarget) {
        private val queue = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        @Volatile var ready = false
        @Volatile var closed = false
        private var socket: SSLSocket? = null

        fun send(type: Int, p: ByteArray) {
            if (!closed) queue.offer(type to p)
        }

        fun close() {
            closed = true
            ready = false
            queue.offer(-1 to ByteArray(0))
            try { socket?.close() } catch (_: Exception) {}
        }

        fun run() {
            val ctx = appCtx
            val identity = Identity.get(ctx)
            var trust: PinnedTrustManager? = null
            var peerVerified = false
            try {
                var host = t.host
                var port = t.port
                // After a couple of failures, look the host up on the LAN (its IP may have changed).
                val st = sm.state
                if (t.fpHex != null && st is LinkState.Reconnecting && st.attempt >= 2) {
                    LocalNet.discover(ctx, 2500, t.fpHex).firstOrNull { it.hostIdPrefix.isNotEmpty() && t.fpHex.startsWith(it.hostIdPrefix) }?.let {
                        host = it.address; port = it.port
                        target = t.copy(host = host, port = port)
                    }
                }
                if (closed) return
                val tm = PinnedTrustManager { fp -> t.fpHex == null || Fingerprint.hex(fp) == t.fpHex }
                trust = tm
                val sslCtx = Tls.context(identity, tm)
                val raw = Socket()
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(host, port), 3000)
                val s = sslCtx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                socket = s
                if (closed) { s.close(); return }
                Tls.harden(s)
                s.useClientMode = true
                s.soTimeout = 8000
                s.startHandshake()
                peerVerified = true
                val fpHost = Tls.peerFingerprint(s) ?: throw IOException("no host cert")
                val input = DataInputStream(BufferedInputStream(s.inputStream, 64 * 1024))
                val out = BufferedOutputStream(s.outputStream, 16 * 1024)
                val nonceC = PairingCrypto.nonce()
                val pairing = t.pairCode != null
                FrameIO.write(out, Msg.HELLO, Hello(Msg.PROTOCOL_VERSION, App.deviceName(ctx), if (pairing) HelloMode.PAIR else HelloMode.TRUSTED, nonceC).encode())
                out.flush()

                if (pairing) pair(s, input, out, fpHost, identity.fingerprint, nonceC)

                val w = FrameIO.read(input)
                when (w.type) {
                    Msg.WELCOME -> {
                        val welcome = Welcome.decode(w.payload)
                        hostName = welcome.hostName
                        val store = TrustedStore.get(ctx)
                        val fpHex = Fingerprint.hex(fpHost)
                        val old = store.find(TrustedStore.ROLE_HOST, fpHex)
                        store.put((old ?: TrustedPeer(fpHex, welcome.hostName, TrustedStore.ROLE_HOST))
                            .copy(name = welcome.hostName, lastAddress = host, lastPort = port, hostId = welcome.hostId, lastSeen = System.currentTimeMillis()))
                    }
                    Msg.BYE -> { sm.hostSaidBye(id, w.payload.firstOrNull()?.toInt() ?: 0); notifyState(); close(); return }
                    else -> throw IOException("unexpected")
                }
                if (closed) return
                s.soTimeout = 6000 // host sends status every second
                seq = 0
                ready = true
                everConnected = true
                sm.connected(id)
                notifyState()
                Thread({ writerLoop(out) }, "nm-client-writer").apply { priority = Thread.MAX_PRIORITY }.start()
                sendQuality()
                if (videoPaused) send(Msg.VIDEO_CTRL, byteArrayOf(VideoCtrl.PAUSE.toByte()))
                readLoop(input)
            } catch (e: PairFailure) {
                sm.failed(id, e.reason); notifyState(); close(); return
            } catch (e: SSLException) {
                val seen = trust?.lastSeen
                val reason = when {
                    // The host presented a different certificate than the one we paired with.
                    t.fpHex != null && seen != null && Fingerprint.hex(seen) != t.fpHex -> FailReason.CERT_MISMATCH
                    // The host refused our certificate: pairing screen closed, or this device is no longer trusted.
                    peerVerified && t.pairCode != null -> FailReason.PAIRING_CLOSED
                    peerVerified && !everConnected -> FailReason.REJECTED
                    else -> null // transient (e.g. Wi-Fi dropped mid-handshake): retry
                }
                if (reason != null) { sm.failed(id, reason); notifyState(); close(); return }
            } catch (_: Exception) {
            }
            close()
            onLost()
        }

        private fun onLost() {
            if (id != sm.connectionId) return
            ready = false
            val delay = sm.connectionLost(id, SystemClock.elapsedRealtime(), everConnected)
            notifyState()
            if (delay != null) workerHandler.postDelayed({ launch(sm.connectionId) }, delay)
        }

        private fun pair(s: SSLSocket, input: DataInputStream, out: BufferedOutputStream, fpHost: ByteArray, fpMe: ByteArray, nonceC: ByteArray) {
            val code = t.pairCode!!
            var f = FrameIO.read(input)
            if (f.type == Msg.PAIR_RESULT) throw PairFailure(mapPair(f.payload))
            if (f.type != Msg.PAIR_NONCE || f.payload.size != 16) throw IOException("bad pairing")
            val nonceH = f.payload
            FrameIO.write(out, Msg.PAIR_PROOF, PairingCrypto.proof('C', code, fpHost, fpMe, nonceC, nonceH)); out.flush()
            s.soTimeout = 60_000
            f = FrameIO.read(input)
            if (f.type == Msg.PAIR_RESULT) throw PairFailure(mapPair(f.payload))
            if (f.type != Msg.PAIR_PROOF || !PairingCrypto.verify(PairingCrypto.proof('H', code, fpHost, fpMe, nonceC, nonceH), f.payload)) {
                throw PairFailure(FailReason.CERT_MISMATCH)
            }
            sm.pairing(id, PairingCrypto.verificationDigits(fpHost, fpMe, nonceC, nonceH))
            notifyState()
            s.soTimeout = 90_000
            f = FrameIO.read(input)
            if (f.type != Msg.PAIR_RESULT) throw IOException("bad pairing")
            val r = f.payload.firstOrNull()?.toInt() ?: -1
            if (r != PairResult.OK) throw PairFailure(mapPair(f.payload))
            val fpHex = Fingerprint.hex(fpHost)
            TrustedStore.get(appCtx).put(TrustedPeer(fpHex, t.name.ifBlank { "Host" }, TrustedStore.ROLE_HOST, t.host, t.port))
            target = t.copy(fpHex = fpHex, pairCode = null)
        }

        private fun mapPair(p: ByteArray): FailReason = when (p.firstOrNull()?.toInt()) {
            PairResult.WRONG_CODE -> FailReason.WRONG_CODE
            PairResult.TOO_MANY_ATTEMPTS -> FailReason.TOO_MANY_ATTEMPTS
            PairResult.WINDOW_CLOSED -> FailReason.PAIRING_CLOSED
            PairResult.REJECTED, PairResult.TIMEOUT -> FailReason.REJECTED
            else -> FailReason.PROTOCOL
        }

        private fun writerLoop(out: BufferedOutputStream) {
            var lastPing = 0L
            try {
                while (!closed) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPing >= 1000) {
                        FrameIO.write(out, Msg.PING, payload { writeLong(System.nanoTime()) })
                        out.flush()
                        lastPing = now
                    }
                    val m = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    if (m.first < 0) break
                    FrameIO.write(out, m.first, m.second)
                    // Drain whatever else is queued before flushing (one TLS record for a burst of moves).
                    while (true) {
                        val more = queue.poll() ?: break
                        if (more.first < 0) { out.flush(); return }
                        FrameIO.write(out, more.first, more.second)
                    }
                    out.flush()
                }
            } catch (_: Exception) {
                try { socket?.close() } catch (_: Exception) {}
            }
        }

        private fun readLoop(input: DataInputStream) {
            while (!closed) {
                val f = FrameIO.read(input)
                when (f.type) {
                    Msg.VIDEO_FRAME -> {
                        val arrival = System.nanoTime()
                        val p = f.payload
                        if (p.size <= 17) continue
                        rxBytes += p.size
                        val flags = p[0].toInt()
                        val pts = readLong(p, 1)
                        clockOffsetNs?.let { off ->
                            val ms = (arrival + off - pts * 1000) / 1_000_000.0
                            if (ms in 0.0..5000.0) captureToArrival.add(ms)
                        }
                        if (!videoPaused) decoder.feed(p, 17, p.size - 17, flags, pts, arrival)
                    }
                    Msg.VIDEO_CONFIG -> {
                        val cfg = VideoConfig.decode(f.payload)
                        lastConfig = cfg
                        decoder.configure(cfg.width, cfg.height)
                        App.main.post { listeners.forEach { it.onVideoConfig(cfg) } }
                    }
                    Msg.STATUS -> {
                        val m = StatusCodec.decode(f.payload)
                        lastStatus = m
                        val before = sm.state
                        if (m["proj"] == "0") sm.projectionStopped(id) else sm.projectionRunning(id)
                        if (sm.state != before) notifyState()
                        App.main.post { listeners.forEach { it.onStatus(m) } }
                    }
                    Msg.PONG -> {
                        val r = f.payload.reader()
                        val t0 = r.readLong(); val hostNs = r.readLong()
                        val now = System.nanoTime()
                        val rttNs = now - t0
                        rtt.add(rttNs / 1_000_000.0)
                        // Clock offset from the fastest round trips (NTP-style); error is at most rtt/2.
                        if (rttNs <= bestRttNs * 1.25 || clockOffsetNs == null) {
                            if (rttNs < bestRttNs) bestRttNs = rttNs
                            clockOffsetNs = hostNs - (t0 + rttNs / 2)
                        }
                    }
                    Msg.TEXT_RESULT -> {
                        val code = f.payload.firstOrNull()?.toInt() ?: 0
                        App.main.post { listeners.forEach { it.onTextResult(code) } }
                    }
                    Msg.BYE -> {
                        sm.hostSaidBye(id, f.payload.firstOrNull()?.toInt() ?: 0)
                        notifyState()
                        close()
                        return
                    }
                }
            }
        }

        private fun readLong(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xff)
            return v
        }
    }

    /** Keys/values for the diagnostics panel. */
    fun diagnostics(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val st = lastStatus
        val cfg = lastConfig
        cfg?.let { out.add("res" to "${it.width}×${it.height}") }
        out.add("fps" to (st["fps"] ?: "-"))
        st["br"]?.toIntOrNull()?.let { out.add("bitrate" to "%.1f Mbps".format(java.util.Locale.US, it / 1_000_000.0)) }
        if (rtt.size() > 0) out.add("rtt" to "%.0f / %.0f ms".format(java.util.Locale.US, rtt.average(), rtt.percentile(95.0)))
        if (captureToArrival.size() > 0) out.add("glass" to "%.0f / %.0f ms".format(java.util.Locale.US, captureToArrival.average(), captureToArrival.percentile(95.0)))
        if (decoder.decodeMs.size() > 0) out.add("decode" to "%.0f / %.0f ms".format(java.util.Locale.US, decoder.decodeMs.average(), decoder.decodeMs.percentile(95.0)))
        st["qd"]?.let { out.add("queue" to "$it ms") }
        out.add("drops" to "${st["drop"] ?: "0"} / ${decoder.droppedFrames}")
        out.add("enc" to "${st["enc"] ?: "-"} (${if (st["hw"] == "1") "HW" else "SW"})")
        out.add("dec" to "${decoder.decoderName} (${if (decoder.hardware) "HW" else "SW"}${if (decoder.lowLatency) ", low-latency" else ""})")
        st["temp"]?.let { out.add("temp" to "$it °C") }
        st["therm"]?.let { out.add("therm" to it) }
        st["mem"]?.let { out.add("mem" to "$it MB") }
        st["os"]?.let { out.add("host_os" to it) }
        out.add("this_os" to App.osLine())
        return out
    }
}
