package sa.nitaaq.mirror.host

import android.content.Context
import android.os.SystemClock
import sa.nitaaq.mirror.App
import sa.nitaaq.mirror.core.CommandGate
import sa.nitaaq.mirror.core.Fingerprint
import sa.nitaaq.mirror.core.PairingCrypto
import sa.nitaaq.mirror.core.RollingStats
import sa.nitaaq.mirror.net.Identity
import sa.nitaaq.mirror.net.LocalNet
import sa.nitaaq.mirror.net.PinnedTrustManager
import sa.nitaaq.mirror.net.Tls
import sa.nitaaq.mirror.net.TrustedPeer
import sa.nitaaq.mirror.net.TrustedStore
import sa.nitaaq.mirror.proto.ByeReason
import sa.nitaaq.mirror.proto.FrameFlags
import sa.nitaaq.mirror.proto.FrameIO
import sa.nitaaq.mirror.proto.Hello
import sa.nitaaq.mirror.proto.HelloMode
import sa.nitaaq.mirror.proto.Msg
import sa.nitaaq.mirror.proto.PairResult
import sa.nitaaq.mirror.proto.Welcome
import sa.nitaaq.mirror.proto.payload
import sa.nitaaq.mirror.proto.reader
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * TLS server on the host. Only two kinds of peers get past the TLS handshake:
 *  - controllers whose certificate fingerprint is in the trusted list, and
 *  - while the pairing screen is open, anyone — who must then prove the 8-digit code and be approved on screen.
 * One controller session at a time; a new authenticated connection replaces the old one.
 */
class HostServer(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onSessionStarted(session: Session)
        fun onSessionEnded(session: Session)
        fun onTouch(gen: Int, payload: ByteArray, receivedAt: Long)
        fun onNav(gen: Int, action: Int)
        fun onText(gen: Int, payload: ByteArray, session: Session)
        fun onVideoCtrl(session: Session, payload: ByteArray)
        /** Blocks until the user answers (or timeout). */
        fun askApproval(name: String, digits: String, fpShort: String): Boolean
        fun onPairingChanged()
    }

    private val identity = Identity.get(ctx)
    private val trusted = TrustedStore.get(ctx)
    val gate = CommandGate()

    @Volatile private var server: SSLServerSocket? = null
    @Volatile var port = 0
        private set
    @Volatile var session: Session? = null
        private set

    // Pairing window
    @Volatile var pairingCode: String? = null
        private set
    @Volatile var pairingExpiresAt = 0L
        private set
    private val failedAttempts = AtomicInteger(0)
    private val pairingBusy = AtomicInteger(0)

    fun isPairingOpen() = pairingCode != null && SystemClock.elapsedRealtime() < pairingExpiresAt

    fun openPairing(durationMs: Long = 5 * 60_000L) {
        pairingCode = PairingCrypto.newCode()
        pairingExpiresAt = SystemClock.elapsedRealtime() + durationMs
        failedAttempts.set(0)
        listener.onPairingChanged()
    }

    fun closePairing() {
        pairingCode = null
        pairingExpiresAt = 0
        listener.onPairingChanged()
    }

    fun start() {
        if (server != null) return
        val trust = PinnedTrustManager { fp ->
            trusted.isTrusted(TrustedStore.ROLE_CONTROLLER, Fingerprint.hex(fp)) || isPairingOpen()
        }
        val sslCtx = Tls.context(identity, trust)
        val ss = sslCtx.serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(LocalNet.DEFAULT_PORT), 4)
        } catch (_: IOException) {
            ss.bind(InetSocketAddress(0), 4)
        }
        ss.needClientAuth = true
        val protos = ss.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (protos.isNotEmpty()) ss.enabledProtocols = protos.toTypedArray()
        server = ss
        port = ss.localPort
        Thread({ acceptLoop(ss) }, "nm-accept").start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        session?.close(ByeReason.HOST_STOPPED)
        session = null
    }

    /** Ends the session of a controller whose trust was just revoked. */
    fun kick(fpHex: String) {
        val s = session ?: return
        if (s.peerFpHex == fpHex) s.close(ByeReason.TRUST_REVOKED)
    }

    private fun acceptLoop(ss: SSLServerSocket) {
        while (!ss.isClosed) {
            val sock = try { ss.accept() as SSLSocket } catch (_: IOException) { break }
            Thread({ handle(sock) }, "nm-conn").start()
        }
    }

    private fun handle(sock: SSLSocket) {
        try {
            sock.tcpNoDelay = true
            sock.sendBufferSize = 192 * 1024 // bounded, so stale video can't hide in kernel buffers
            sock.soTimeout = 10_000
            sock.startHandshake()
            val fp = Tls.peerFingerprint(sock) ?: throw IOException("no peer cert")
            val fpHex = Fingerprint.hex(fp)
            val input = DataInputStream(sock.inputStream.buffered(64 * 1024))
            val out = BufferedOutputStream(sock.outputStream, 64 * 1024)
            val first = FrameIO.read(input)
            if (first.type != Msg.HELLO) throw IOException("expected hello")
            val hello = Hello.decode(first.payload)

            var peerName = hello.deviceName
            if (hello.mode == HelloMode.PAIR) {
                peerName = pair(sock, input, out, hello, fp) ?: return
            } else if (!trusted.isTrusted(TrustedStore.ROLE_CONTROLLER, fpHex)) {
                writeNow(out, Msg.BYE, payload { writeByte(ByeReason.NOT_TRUSTED) })
                sock.close()
                return
            }
            trusted.touch(TrustedStore.ROLE_CONTROLLER, fpHex)

            // Replace any previous session (prevents duplicates after quick reconnects).
            session?.close(ByeReason.REPLACED)
            val gen = gate.startSession()
            val s = Session(sock, input, out, gen, fpHex, peerName)
            session = s
            sock.soTimeout = 15_000 // controller pings every second
            writeNow(out, Msg.WELCOME, Welcome(App.deviceName(ctx), identity.fingerprintHex, gen).encode())
            s.startWriter()
            listener.onSessionStarted(s)
            s.readLoop()
        } catch (_: Exception) {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** Returns the controller name on success; null if pairing failed (socket closed). */
    private fun pair(sock: SSLSocket, input: DataInputStream, out: BufferedOutputStream, hello: Hello, fpCtrl: ByteArray): String? {
        val code = pairingCode
        if (code == null || !isPairingOpen()) {
            writeNow(out, Msg.PAIR_RESULT, payload { writeByte(PairResult.WINDOW_CLOSED) }); sock.close(); return null
        }
        if (pairingBusy.getAndIncrement() > 0) { // one pairing at a time
            pairingBusy.decrementAndGet()
            writeNow(out, Msg.PAIR_RESULT, payload { writeByte(PairResult.WINDOW_CLOSED) }); sock.close(); return null
        }
        try {
            val nonceH = PairingCrypto.nonce()
            writeNow(out, Msg.PAIR_NONCE, nonceH)
            sock.soTimeout = 60_000
            val proofFrame = FrameIO.read(input)
            if (proofFrame.type != Msg.PAIR_PROOF) throw IOException("expected proof")
            val fpHost = identity.fingerprint
            val expected = PairingCrypto.proof('C', code, fpHost, fpCtrl, hello.nonce, nonceH)
            if (!PairingCrypto.verify(expected, proofFrame.payload)) {
                val n = failedAttempts.incrementAndGet()
                val result = if (n >= 5) PairResult.TOO_MANY_ATTEMPTS else PairResult.WRONG_CODE
                if (n >= 5) openPairing() // burn the code
                writeNow(out, Msg.PAIR_RESULT, payload { writeByte(result) }); sock.close(); return null
            }
            writeNow(out, Msg.PAIR_PROOF, PairingCrypto.proof('H', code, fpHost, fpCtrl, hello.nonce, nonceH))
            val digits = PairingCrypto.verificationDigits(fpHost, fpCtrl, hello.nonce, nonceH)
            val approved = listener.askApproval(hello.deviceName, digits, Fingerprint.short(fpCtrl))
            if (!approved) {
                writeNow(out, Msg.PAIR_RESULT, payload { writeByte(PairResult.REJECTED) }); sock.close(); return null
            }
            trusted.put(TrustedPeer(Fingerprint.hex(fpCtrl), hello.deviceName, TrustedStore.ROLE_CONTROLLER))
            closePairing()
            writeNow(out, Msg.PAIR_RESULT, payload { writeByte(PairResult.OK) })
            return hello.deviceName
        } finally {
            pairingBusy.decrementAndGet()
        }
    }

    private fun writeNow(out: BufferedOutputStream, type: Int, p: ByteArray) {
        FrameIO.write(out, type, p)
        out.flush()
    }

    /** Statistics of the video sender, reset every second by the service. */
    class SendStats {
        val queueDelay = RollingStats(120)
        @Volatile var dropped = 0
        @Volatile var bytes = 0L
        @Volatile var frames = 0
    }

    inner class Session(
        private val sock: SSLSocket,
        private val input: DataInputStream,
        private val out: BufferedOutputStream,
        val gen: Int,
        val peerFpHex: String,
        val peerName: String,
    ) {
        private val lock = Object()
        private val ctrl = ArrayDeque<Pair<Int, ByteArray>>()
        private val video = ArrayDeque<VideoItem>()
        private var videoBytes = 0
        private var waitForKey = true
        @Volatile var paused = false
        @Volatile private var closed = false
        val stats = SendStats()
        private val ended = CountDownLatch(1)

        private inner class VideoItem(val data: ByteArray, val flags: Int, val ptsUs: Long, val enqueuedNs: Long)

        fun sendControl(type: Int, p: ByteArray) {
            synchronized(lock) {
                if (closed) return
                ctrl.addLast(type to p)
                lock.notifyAll()
            }
        }

        /** Returns true if the encoder should produce a key frame (stream had to be cut for freshness). */
        fun offerVideo(data: ByteArray, flags: Int, ptsUs: Long): Boolean {
            var needKey = false
            synchronized(lock) {
                if (closed) return false
                val isConfig = flags and FrameFlags.CONFIG != 0
                val isKey = flags and FrameFlags.KEY != 0
                if (paused && !isConfig) return false
                if (!isConfig) {
                    if (waitForKey && !isKey) { stats.dropped++; return false }
                    val now = System.nanoTime()
                    val oldestAgeMs = video.peekFirst()?.let { (now - it.enqueuedNs) / 1_000_000 } ?: 0
                    if (videoBytes > MAX_QUEUED_BYTES || oldestAgeMs > MAX_QUEUE_AGE_MS) {
                        // Network can't keep up: throw away stale frames and restart from the next key frame.
                        val it = video.iterator()
                        while (it.hasNext()) {
                            val v = it.next()
                            if (v.flags and FrameFlags.CONFIG == 0) { it.remove(); videoBytes -= v.data.size; stats.dropped++ }
                        }
                        if (!isKey) { waitForKey = true; stats.dropped++; needKey = true; lock.notifyAll(); return needKey }
                    }
                    if (isKey) waitForKey = false
                }
                video.addLast(VideoItem(data, flags, ptsUs, System.nanoTime()))
                videoBytes += data.size
                lock.notifyAll()
            }
            return needKey
        }

        fun requireKeyFrame() {
            synchronized(lock) { waitForKey = true }
        }

        fun startWriter() {
            val t = Thread({ writerLoop() }, "nm-writer")
            t.priority = Thread.MAX_PRIORITY - 1
            t.start()
        }

        private fun writerLoop() {
            val header = ByteArray(5 + 17)
            try {
                while (true) {
                    var c: Pair<Int, ByteArray>? = null
                    var v: VideoItem? = null
                    synchronized(lock) {
                        while (!closed && ctrl.isEmpty() && video.isEmpty()) lock.wait()
                        if (closed) return
                        // Control replies (pong, status, text results) always go first.
                        c = ctrl.pollFirst()
                        if (c == null) {
                            v = video.pollFirst()
                            v?.let { videoBytes -= it.data.size }
                        }
                    }
                    c?.let { FrameIO.write(out, it.first, it.second); out.flush() }
                    v?.let { item ->
                        val sendNs = System.nanoTime()
                        stats.queueDelay.add((sendNs - item.enqueuedNs) / 1_000_000.0)
                        val len = 17 + item.data.size
                        header[0] = Msg.VIDEO_FRAME.toByte()
                        putInt(header, 1, len)
                        header[5] = item.flags.toByte()
                        putLong(header, 6, item.ptsUs)
                        putLong(header, 14, sendNs)
                        out.write(header)
                        out.write(item.data)
                        out.flush()
                        stats.bytes += len
                        stats.frames++
                    }
                }
            } catch (_: Exception) {
                close(null)
            }
        }

        fun readLoop() {
            try {
                while (!closed) {
                    val f = try { FrameIO.read(input) } catch (e: SocketTimeoutException) { break }
                    val now = SystemClock.uptimeMillis()
                    when (f.type) {
                        Msg.TOUCH -> listener.onTouch(gen, f.payload, now)
                        Msg.NAV -> if (f.payload.isNotEmpty()) listener.onNav(gen, f.payload[0].toInt() and 0xff)
                        Msg.TEXT -> listener.onText(gen, f.payload, this)
                        Msg.PING -> {
                            val t0 = f.payload.reader().readLong()
                            sendControl(Msg.PONG, payload { writeLong(t0); writeLong(System.nanoTime()) })
                        }
                        Msg.VIDEO_CTRL -> listener.onVideoCtrl(this, f.payload)
                        else -> {}
                    }
                }
            } catch (_: Exception) {
            } finally {
                close(null)
            }
        }

        fun close(reason: Int?) {
            synchronized(lock) {
                if (closed) return
                closed = true
                lock.notifyAll()
            }
            if (reason != null) {
                try { FrameIO.write(out, Msg.BYE, payload { writeByte(reason) }); out.flush() } catch (_: Exception) {}
            }
            try { sock.close() } catch (_: Exception) {}
            gate.endSession(gen)
            if (session === this) session = null
            ended.countDown()
            listener.onSessionEnded(this)
        }

        val isClosed get() = closed

        fun awaitEnd(ms: Long) = ended.await(ms, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val MAX_QUEUED_BYTES = 400 * 1024
        private const val MAX_QUEUE_AGE_MS = 120L

        private fun putInt(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
        }

        private fun putLong(b: ByteArray, o: Int, v: Long) {
            for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte()
        }
    }
}
