package sa.nitaaq.mirror.host

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.app.KeyguardManager
import android.view.Display
import sa.nitaaq.mirror.App
import sa.nitaaq.mirror.Prefs
import sa.nitaaq.mirror.R
import sa.nitaaq.mirror.core.AdaptiveBitrate
import sa.nitaaq.mirror.core.Quality
import sa.nitaaq.mirror.core.QualityPolicy
import sa.nitaaq.mirror.net.Identity
import sa.nitaaq.mirror.net.LocalNet
import sa.nitaaq.mirror.net.TrustedStore
import sa.nitaaq.mirror.proto.FrameFlags
import sa.nitaaq.mirror.proto.Msg
import sa.nitaaq.mirror.proto.Nav
import sa.nitaaq.mirror.proto.StatusCodec
import sa.nitaaq.mirror.proto.TextResult
import sa.nitaaq.mirror.proto.VideoConfig
import sa.nitaaq.mirror.proto.VideoCtrl
import sa.nitaaq.mirror.proto.payload
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Foreground service (type mediaProjection) that owns screen capture, the encoder and the TLS server.
 * Its notification is always visible while it runs and has a "Stop" action.
 */
class HostService : Service(), ScreenEncoder.Sink, HostServer.Listener {

    private lateinit var prefs: Prefs
    private var projection: MediaProjection? = null
    @Volatile private var encoder: ScreenEncoder? = null
    private var server: HostServer? = null
    private var advertiser: LocalNet.Advertiser? = null
    private val statusThread = HandlerThread("nm-status").apply { start() }
    private val statusHandler = Handler(statusThread.looper)
    @Volatile private var lastConfig: VideoConfig? = null
    @Volatile private var lastCsd: ByteArray? = null
    private var abr: AdaptiveBitrate? = null
    private var calmAtMaxSeconds = 0
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var approvalLatch: CountDownLatch? = null
    @Volatile private var approvalAnswer = false
    private var lastFramesOut = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(App.localized(base))
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.of(this)
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else intent.getParcelableExtra(EXTRA_DATA)
                startCapture(code, data)
            }
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_OPEN_PAIRING -> server?.openPairing()
            ACTION_CLOSE_PAIRING -> server?.closePairing()
            ACTION_APPROVE -> answerApproval(true, intent.getIntExtra(EXTRA_REQ, -1))
            ACTION_DENY -> answerApproval(false, intent.getIntExtra(EXTRA_REQ, -1))
            null -> { stopSelf(); return START_NOT_STICKY } // restarted by the system without a projection grant
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ capture

    private fun startCapture(code: Int, data: Intent?) {
        // Must be in the foreground with type mediaProjection *before* getMediaProjection (Android 14+).
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (code != Activity.RESULT_OK || data == null) {
            if (projection == null) failAndStop(getString(R.string.err_projection_denied))
            return
        }
        releaseCapture()
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        val mp = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (mp == null) { failAndStop(getString(R.string.err_projection_denied)); return }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { statusHandler.post { onProjectionStopped(mp) } }
        }, statusHandler)

        val q = prefs.quality
        val enc = ScreenEncoder(this, mp, this)
        encoder = enc
        abr = AdaptiveBitrate(QualityPolicy.initialBitrate(q, prefs.fps))
        enc.start(q, prefs.fps)

        if (server == null) {
            val s = HostServer(this, this)
            try {
                s.start()
            } catch (e: Exception) {
                failAndStop(getString(R.string.err_server_port)); return
            }
            server = s
            advertiser = LocalNet.Advertiser(this).also {
                it.start(s.port, App.deviceName(this), Identity.get(this).fingerprintHex)
            }
            if (TrustedStore.get(this).list(TrustedStore.ROLE_CONTROLLER).isEmpty()) {
                s.openPairing()
            }
        }
        HostState.port = server?.port ?: 0
        HostState.phase = HostState.Phase.RUNNING
        HostState.lastError = null
        HostState.changed()
        server?.session?.let { onSessionStarted(it) }
        updateNotification()
        statusHandler.removeCallbacksAndMessages(STATUS_TOKEN)
        scheduleStatus()
    }

    private fun onProjectionStopped(mp: MediaProjection) {
        if (projection !== mp) return
        releaseCapture()
        if (HostState.phase == HostState.Phase.STOPPED) return
        HostState.phase = HostState.Phase.PROJECTION_STOPPED
        HostState.changed()
        TouchControl.active?.setKeepAwake(false)
        updateNotification()
        sendStatusNow()
    }

    private fun releaseCapture() {
        encoder?.stop()
        encoder = null
        val mp = projection
        projection = null
        try { mp?.stop() } catch (_: Exception) {}
        lastConfig = null
        lastCsd = null
    }

    private fun failAndStop(msg: String) {
        HostState.lastError = msg
        stopEverything()
    }

    private fun stopEverything() {
        statusHandler.removeCallbacksAndMessages(null)
        answerApproval(false)
        releaseCapture()
        advertiser?.stop(); advertiser = null
        server?.stop(); server = null
        TouchControl.active?.let { it.endAllGestures(); it.setKeepAwake(false) }
        releaseLocks()
        HostState.phase = HostState.Phase.STOPPED
        HostState.controllerName = null
        HostState.pairingCode = null
        HostState.pendingApproval = null
        HostState.changed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (HostState.phase != HostState.Phase.STOPPED) stopEverything()
        statusThread.quitSafely()
        if (running === this) running = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ encoder sink

    override fun onVideoConfig(cfg: VideoConfig) {
        lastConfig = cfg
        lastCsd = null
        TouchControl.active?.endAllGestures() // geometry changed: lift any finger
        server?.session?.let {
            it.sendControl(Msg.VIDEO_CONFIG, cfg.encode())
            it.requireKeyFrame()
        }
        abr = AdaptiveBitrate(encoder?.targetBitrate ?: cfg.width * 1000)
    }

    override fun onEncodedFrame(data: ByteArray, flags: Int, ptsUs: Long) {
        if (flags and FrameFlags.CONFIG != 0) lastCsd = data
        val s = server?.session ?: return
        if (s.offerVideo(data, flags, ptsUs)) encoder?.requestKeyFrame()
    }

    /** Called only after the encoder could not be rebuilt on the existing capture; capture must restart. */
    override fun onEncoderError(e: Throwable) {
        HostState.lastError = getString(R.string.err_encoder)
        HostState.changed()
        statusHandler.post { projection?.let { onProjectionStopped(it) } }
    }

    // ------------------------------------------------------------------ server listener

    /** Session start/end callbacks come from different connection threads; serialise them. */
    private val sessionLock = Any()

    override fun onSessionStarted(session: HostServer.Session): Unit = synchronized(sessionLock) {
        HostState.controllerName = session.peerName
        HostState.changed()
        acquireLocks()
        lastConfig?.let { session.sendControl(Msg.VIDEO_CONFIG, it.encode()) }
        lastCsd?.let { session.offerVideo(it, FrameFlags.CONFIG, 0) }
        session.requireKeyFrame()
        encoder?.let { e -> abr?.let { e.setBitrate(it.bitrate) }; e.requestKeyFrame() }
        TouchControl.active?.setKeepAwake(prefs.keepAwake && projection != null)
        sendStatusNow()
        updateNotification()
    }

    override fun onSessionEnded(session: HostServer.Session): Unit = synchronized(sessionLock) {
        val current = server?.session
        if (current != null && current !== session && !current.isClosed) return // a newer session is live
        HostState.controllerName = null
        HostState.changed()
        TouchControl.active?.let { it.endAllGestures(); it.setKeepAwake(false) }
        encoder?.setBitrate(IDLE_BITRATE)
        releaseLocks()
        updateNotification()
    }

    override fun onTouch(gen: Int, payload: ByteArray, receivedAt: Long) {
        val svc = TouchControl.active ?: return
        val gate = server?.gate ?: return
        svc.onTouch(gate, gen, payload, receivedAt)
    }

    override fun onNav(gen: Int, action: Int) {
        if (server?.gate?.isCurrent(gen) != true) return
        if (action == Nav.WAKE_SCREEN) { wakeScreen(); return }
        TouchControl.active?.nav(action)
    }

    override fun onText(gen: Int, payload: ByteArray, session: HostServer.Session) {
        if (server?.gate?.isCurrent(gen) != true) return
        val svc = TouchControl.active
        if (svc == null) {
            session.sendControl(Msg.TEXT_RESULT, byteArrayOf(TextResult.NO_ACCESSIBILITY.toByte())); return
        }
        svc.text(payload) { result -> session.sendControl(Msg.TEXT_RESULT, byteArrayOf(result.toByte())) }
    }

    override fun onVideoCtrl(session: HostServer.Session, payload: ByteArray) {
        if (payload.isEmpty()) return
        val e = encoder
        when (payload[0].toInt()) {
            VideoCtrl.PAUSE -> { session.paused = true; e?.setBitrate(IDLE_BITRATE) }
            VideoCtrl.RESUME -> {
                session.paused = false
                session.requireKeyFrame()
                e?.let { enc -> abr?.let { enc.setBitrate(it.bitrate) }; enc.requestKeyFrame() }
            }
            VideoCtrl.KEYFRAME -> { session.requireKeyFrame(); e?.requestKeyFrame() }
            VideoCtrl.QUALITY -> if (payload.size >= 3) {
                val q = Quality.of(payload[1].toInt())
                val fps = if (payload[2].toInt() >= 60) 60 else 30
                prefs.quality = q; prefs.fps = fps
                calmAtMaxSeconds = 0
                e?.setQuality(q, fps)
            }
        }
    }

    override fun askApproval(name: String, digits: String, fpShort: String): Boolean {
        val latch = CountDownLatch(1)
        val req = ++approvalSeq
        approvalLatch = latch
        approvalAnswer = false
        HostState.pendingApproval = HostState.Approval(name, digits, fpShort, req)
        HostState.changed()
        showApprovalNotification(name, digits, req)
        val answered = latch.await(60, TimeUnit.SECONDS)
        HostState.pendingApproval = null
        HostState.changed()
        getSystemService(NotificationManager::class.java)!!.cancel(APPROVAL_NOTIF_ID)
        return answered && approvalAnswer
    }

    @Volatile private var approvalSeq = 0

    /** [req] must match the pending request, so a late tap can't approve a different device. */
    private fun answerApproval(ok: Boolean, req: Int = approvalSeq) {
        if (req != approvalSeq) return
        approvalAnswer = ok
        approvalLatch?.countDown()
        approvalLatch = null
    }

    override fun onPairingChanged() {
        val s = server
        HostState.pairingCode = if (s != null && s.isPairingOpen()) s.pairingCode else null
        HostState.pairingExpiresAt = s?.pairingExpiresAt ?: 0
        HostState.changed()
    }

    // ------------------------------------------------------------------ status, adaptation

    private fun scheduleStatus() {
        statusHandler.postAtTime({ statusTick(); scheduleStatus() }, STATUS_TOKEN, android.os.SystemClock.uptimeMillis() + 1000)
    }

    private fun statusTick() {
        server?.let { if (it.pairingCode != null && !it.isPairingOpen()) it.closePairing() }
        val s = server?.session
        val e = encoder
        if (s != null && e != null && !s.paused) {
            val delay = s.stats.queueDelay.average().let { if (it.isNaN()) 0.0 else it }
            val dropped = s.stats.dropped
            abr?.onSecond(delay, dropped)?.let { e.setBitrate(it) }
            if (e.quality == Quality.AUTO) {
                val a = abr
                if (a != null && a.congestedAtFloor >= 3 && e.autoStep == 0) { e.setAutoStep(1); calmAtMaxSeconds = 0 }
                if (a != null && e.autoStep == 1) {
                    calmAtMaxSeconds = if (a.bitrate >= 3_000_000 && dropped == 0) calmAtMaxSeconds + 1 else 0
                    if (calmAtMaxSeconds >= 15) { e.setAutoStep(0); calmAtMaxSeconds = 0 }
                }
            }
        }
        sendStatusNow()
        s?.stats?.let { it.dropped = 0; it.frames = 0; it.bytes = 0; it.queueDelay.clear() }
    }

    private fun sendStatusNow() {
        val map = collectStatus()
        HostState.diagnostics = map
        HostState.changed()
        server?.session?.sendControl(Msg.STATUS, StatusCodec.encode(map))
    }

    @Suppress("DEPRECATION")
    private fun collectStatus(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val e = encoder
        val s = server?.session
        m["proj"] = if (projection != null && e != null) "1" else "0"
        val disp = getSystemService(DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY)
        m["disp"] = when (disp?.state) {
            Display.STATE_ON -> "on"; Display.STATE_OFF -> "off"
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "doze"
            else -> "other"
        }
        m["inter"] = if (getSystemService(PowerManager::class.java)!!.isInteractive) "1" else "0"
        m["lock"] = if (getSystemService(KeyguardManager::class.java)!!.isKeyguardLocked) "1" else "0"
        m["a11y"] = if (TouchControl.active != null) "1" else "0"
        if (!sa.nitaaq.mirror.BuildConfig.TOUCH_CONTROL) m["touch"] = "none"
        if (e != null) {
            val out = e.framesOut
            m["fps"] = (out - lastFramesOut).coerceAtLeast(0).toString()
            lastFramesOut = out
            e.current?.let { m["res"] = "${it.width}x${it.height}" }
            m["disp_size"] = "${e.displayW}x${e.displayH}"
            m["br"] = e.targetBitrate.toString()
            m["enc"] = e.encoderName
            m["hw"] = if (e.hardware) "1" else "0"
            m["q"] = e.quality.code.toString()
            m["mfps"] = e.fps.toString()
        }
        if (s != null) {
            val qd = s.stats.queueDelay
            if (qd.size() > 0) { m["qd"] = "%.1f".format(java.util.Locale.US, qd.average()); m["qd95"] = "%.1f".format(java.util.Locale.US, qd.percentile(95.0)) }
            m["drop"] = s.stats.dropped.toString()
            m["sent_kbps"] = (s.stats.bytes * 8 / 1000).toString()
        }
        val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let {
            m["temp"] = "%.1f".format(java.util.Locale.US, it / 10.0)
        }
        if (Build.VERSION.SDK_INT >= 29) m["therm"] = getSystemService(PowerManager::class.java)!!.currentThermalStatus.toString()
        val rt = Runtime.getRuntime()
        m["mem"] = (((rt.totalMemory() - rt.freeMemory()) + Debug.getNativeHeapAllocatedSize()) / (1024 * 1024)).toString()
        m["awake"] = if (TouchControl.active?.isKeepingAwake == true) "1" else "0"
        m["os"] = App.osLine()
        return m
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        // Official (deprecated but functional) way to turn the display on; never touches the lock screen.
        val pm = getSystemService(PowerManager::class.java)!!
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "nitaaq:wake",
        )
        wl.acquire(3000)
    }

    private fun acquireLocks() {
        if (cpuLock == null) {
            // Keeps input/network alive while a controller is connected (released on disconnect).
            cpuLock = getSystemService(PowerManager::class.java)!!
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nitaaq:session").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "nitaaq:session")?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        try { cpuLock?.release() } catch (_: Exception) {}
        cpuLock = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    // ------------------------------------------------------------------ notifications

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            HostState.phase == HostState.Phase.PROJECTION_STOPPED -> getString(R.string.notif_projection_stopped)
            HostState.controllerName != null -> getString(R.string.notif_sharing_with, HostState.controllerName)
            else -> getString(R.string.notif_waiting)
        }
        return Notification.Builder(this, App.CH_SHARING)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_sharing), stop).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification() {
        if (HostState.phase == HostState.Phase.STOPPED) return
        getSystemService(NotificationManager::class.java)!!.notify(NOTIF_ID, buildNotification())
    }

    private fun showApprovalNotification(name: String, digits: String, req: Int) {
        fun svc(action: String, rc: Int) = PendingIntent.getService(
            this, rc, Intent(this, HostService::class.java).setAction(action).putExtra(EXTRA_REQ, req),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // "Allow" must not work from the lock screen.
        val allow = Notification.Action.Builder(null, getString(R.string.allow), svc(ACTION_APPROVE, 4))
        if (Build.VERSION.SDK_INT >= 31) allow.setAuthenticationRequired(true)
        val open = PendingIntent.getActivity(this, 3, Intent(this, HostActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, App.CH_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.approve_title))
            .setContentText(getString(R.string.approve_body, name, digits))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.approve_body, name, digits)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(allow.build())
            .addAction(Notification.Action.Builder(null, getString(R.string.deny), svc(ACTION_DENY, 5)).build())
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        getSystemService(NotificationManager::class.java)!!.notify(APPROVAL_NOTIF_ID, n)
    }

    fun revoke(fpHex: String) {
        server?.kick(fpHex)
    }

    companion object {
        const val ACTION_START = "sa.nitaaq.mirror.START"
        const val ACTION_STOP = "sa.nitaaq.mirror.STOP"
        const val ACTION_OPEN_PAIRING = "sa.nitaaq.mirror.OPEN_PAIRING"
        const val ACTION_CLOSE_PAIRING = "sa.nitaaq.mirror.CLOSE_PAIRING"
        const val ACTION_APPROVE = "sa.nitaaq.mirror.APPROVE"
        const val ACTION_DENY = "sa.nitaaq.mirror.DENY"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_REQ = "req"
        private const val NOTIF_ID = 10
        private const val APPROVAL_NOTIF_ID = 11
        private const val IDLE_BITRATE = 250_000
        private val STATUS_TOKEN = Any()

        @Volatile var running: HostService? = null
            private set

        fun send(ctx: Context, action: String, req: Int = -1) {
            if (running == null) return
            ctx.startService(Intent(ctx, HostService::class.java).setAction(action).putExtra(EXTRA_REQ, req))
        }
    }
}
