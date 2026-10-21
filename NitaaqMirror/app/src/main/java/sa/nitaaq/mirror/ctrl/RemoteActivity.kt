package sa.nitaaq.mirror.ctrl

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import sa.nitaaq.mirror.App
import sa.nitaaq.mirror.Prefs
import sa.nitaaq.mirror.R
import sa.nitaaq.mirror.SettingsActivity
import sa.nitaaq.mirror.core.FailReason
import sa.nitaaq.mirror.core.LinkState
import sa.nitaaq.mirror.core.Quality
import sa.nitaaq.mirror.proto.ByeReason
import sa.nitaaq.mirror.proto.Nav
import sa.nitaaq.mirror.proto.Pointer
import sa.nitaaq.mirror.proto.TextOp
import sa.nitaaq.mirror.proto.TextResult
import sa.nitaaq.mirror.proto.TouchAction
import sa.nitaaq.mirror.proto.VideoConfig
import sa.nitaaq.mirror.ui.BaseActivity
import sa.nitaaq.mirror.ui.Ui

/**
 * Screen 4: the remote screen. Direct touch by default: a finger on the picture is a finger on the host.
 * The Note's Back key / back gesture goes to the host; leaving this screen uses the separate Exit button.
 */
class RemoteActivity : BaseActivity(), RemoteSession.Listener, SurfaceHolder.Callback {

    private lateinit var content: FrameLayout
    private lateinit var frame: AspectFrame
    private lateinit var surface: SurfaceView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerAction: TextView
    private lateinit var diag: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var handle: TextView
    private lateinit var keyboard: LinearLayout
    private lateinit var input: EditText
    private lateinit var kbResult: TextView
    private lateinit var pairCard: LinearLayout
    private lateinit var pairDigits: TextView

    private var fullscreen = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastRendered = 0L
    private var stalledSeconds = 0
    private var tickCount = 0
    private var transientMessage: String? = null
    private var transientUntil = 0L

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            App.main.postDelayed(this, 1000)
        }
    }

    override val darkChrome = true

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        RemoteSession.addListener(this)
        RemoteSession.lastConfig?.let { onVideoConfig(it) }
        onState(RemoteSession.state)
    }

    override fun onStart() {
        super.onStart()
        RemoteSession.setVideoPaused(false)
        RemoteSession.ensureConnected()
        RemoteSession.requestKeyFrame()
        acquireWifiLock()
        App.main.post(ticker)
    }

    override fun onStop() {
        App.main.removeCallbacks(ticker)
        // Leaving the screen (Home, another app, Exit): keep the session, stop the video to save power/data.
        RemoteSession.setVideoPaused(true)
        releaseWifiLock()
        super.onStop()
    }

    override fun onDestroy() {
        RemoteSession.removeListener(this)
        super.onDestroy()
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(mode, "nitaaq:viewer")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWifiLock() {
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    // ------------------------------------------------------------------ UI

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content = FrameLayout(this)
        Ui.applySystemBarPadding(content)
        root.addView(content, match())

        frame = AspectFrame(this)
        surface = SurfaceView(this)
        surface.holder.addCallback(this)
        surface.setOnTouchListener { v, ev -> onRemoteTouch(v, ev); true }
        frame.addView(surface)
        content.addView(frame, match())

        // Status banner (top)
        bannerText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14.5f; setLineSpacing(0f, 1.1f) }
        bannerAction = TextView(this).apply {
            setTextColor(0xFF7FE3EC.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(Ui.dp(this@RemoteActivity, 12), Ui.dp(this@RemoteActivity, 6), 0, Ui.dp(this@RemoteActivity, 6))
        }
        banner = Ui.column(this, 4).apply {
            background = Ui.rounded(this@RemoteActivity, 0xE6202426.toInt(), 14)
            val p = Ui.dp(this@RemoteActivity, 12)
            setPadding(p, p, p, p)
            addView(bannerText); addView(bannerAction)
            visibility = View.GONE
        }
        content.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            val m = Ui.dp(this@RemoteActivity, 10); setMargins(m, m, m, m)
        })

        // Diagnostics (top-start)
        diag = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.MONOSPACE
            background = Ui.rounded(this@RemoteActivity, 0xAA000000.toInt(), 8)
            val p = Ui.dp(this@RemoteActivity, 8); setPadding(p, p, p, p)
            visibility = if (Prefs.of(this@RemoteActivity).showDiagnostics) View.VISIBLE else View.GONE
            textDirection = View.TEXT_DIRECTION_LTR
        }
        content.addView(diag, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            val m = Ui.dp(this@RemoteActivity, 10); setMargins(m, Ui.dp(this@RemoteActivity, 70), m, m)
        })

        // Toolbar (bottom)
        toolbar = Ui.row(this, 2).apply {
            background = Ui.rounded(this@RemoteActivity, 0xF2FFFFFF.toInt(), 28)
            val p = Ui.dp(this@RemoteActivity, 4); setPadding(p * 2, p, p * 2, p)
            layoutDirection = View.LAYOUT_DIRECTION_LTR // keep the Android nav order: Back, Home, Recents
            addView(tool(R.drawable.ic_back, R.string.t_back) { RemoteSession.sendNav(Nav.BACK) })
            addView(tool(R.drawable.ic_home, R.string.t_home) { RemoteSession.sendNav(Nav.HOME) })
            addView(tool(R.drawable.ic_recents, R.string.t_recents) { RemoteSession.sendNav(Nav.RECENTS) })
            addView(tool(R.drawable.ic_keyboard, R.string.t_keyboard) { toggleKeyboard() })
            addView(tool(R.drawable.ic_fullscreen, R.string.t_fullscreen) { toggleFullscreen() })
            addView(tool(R.drawable.ic_more, R.string.t_more) { v -> showMore(v) })
            addView(tool(R.drawable.ic_hide, R.string.t_hide) { setToolbarVisible(false) })
            addView(tool(R.drawable.ic_exit, R.string.t_exit) { finish() })
            addView(tool(R.drawable.ic_disconnect, R.string.t_disconnect, Ui.DANGER) { RemoteSession.disconnect(); finish() })
        }
        content.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(this@RemoteActivity, 10)
        })

        // Handle to bring the toolbar back
        handle = TextView(this).apply {
            text = "▲"; textSize = 16f; setTextColor(Ui.PRIMARY); gravity = Gravity.CENTER
            background = Ui.rounded(this@RemoteActivity, 0xCCFFFFFF.toInt(), 22)
            contentDescription = getString(R.string.t_show_toolbar)
            visibility = View.GONE
            setOnClickListener { setToolbarVisible(true) }
        }
        content.addView(handle, FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.BOTTOM or Gravity.END).apply {
            val m = Ui.dp(this@RemoteActivity, 12); setMargins(m, m, m, m)
        })

        // Text panel
        input = EditText(this).apply {
            hint = getString(R.string.kb_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            maxLines = 3
            setTextColor(Ui.TEXT)
        }
        kbResult = Ui.body(this, "", sizeSp = 13f)
        val row1 = Ui.row(this, 6).apply {
            addView(Ui.small(this@RemoteActivity, getString(R.string.kb_send)) { sendText(TextOp.INSERT) }, weight())
            addView(Ui.small(this@RemoteActivity, getString(R.string.kb_replace)) { sendText(TextOp.REPLACE_ALL) }, weight())
            addView(Ui.small(this@RemoteActivity, "⌫") { RemoteSession.sendText(TextOp.BACKSPACE) }, weight())
            addView(Ui.small(this@RemoteActivity, getString(R.string.kb_enter)) { RemoteSession.sendText(TextOp.ENTER) }, weight())
        }
        val row2 = Ui.row(this, 6).apply {
            addView(Ui.small(this@RemoteActivity, getString(R.string.kb_host_keyboard)) { RemoteSession.sendText(TextOp.SHOW_KEYBOARD) }, weight())
            addView(Ui.small(this@RemoteActivity, getString(R.string.close)) { toggleKeyboard() }, weight())
        }
        keyboard = Ui.column(this, 8).apply {
            background = Ui.rounded(this@RemoteActivity, Ui.SURFACE, 18)
            val p = Ui.dp(this@RemoteActivity, 12); setPadding(p, p, p, p)
            addView(Ui.body(this@RemoteActivity, getString(R.string.kb_explain), sizeSp = 12.5f))
            addView(input); addView(row1); addView(row2); addView(kbResult)
            visibility = View.GONE
        }
        content.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            val m = Ui.dp(this@RemoteActivity, 8); setMargins(m, m, m, m)
        })

        // Pairing verification card
        pairDigits = TextView(this).apply {
            textSize = 40f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.PRIMARY); gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
        }
        pairCard = Ui.card(this,
            Ui.title(this, getString(R.string.pair_verify_title), 20f),
            pairDigits,
            Ui.body(this, getString(R.string.pair_verify_body)),
            Ui.secondary(this, getString(R.string.cancel)) { RemoteSession.disconnect(); finish() },
        ).apply { visibility = View.GONE }
        content.addView(pairCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            val m = Ui.dp(this@RemoteActivity, 20); setMargins(m, m, m, m)
        })

        setContentView(root)
    }

    private fun tool(icon: Int, label: Int, tint: Int = Ui.TEXT, onClick: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(tint)
        background = Ui.rounded(this@RemoteActivity, Color.TRANSPARENT, 20)
        contentDescription = getString(label)
        tooltipText = getString(label)
        val s = toolSizePx()
        layoutParams = LinearLayout.LayoutParams(s, s)
        setOnClickListener { onClick(it) }
    }

    /** 40dp buttons, shrunk on narrow screens so all 9 toolbar buttons always fit (min 32dp). */
    private fun toolSizePx(): Int {
        val widthDp = resources.configuration.smallestScreenWidthDp.takeIf { it > 0 } ?: 360
        val fit = (widthDp - 16 - 16 - 8 * 2) / 9
        return Ui.dp(this, fit.coerceIn(32, 40))
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun setToolbarVisible(v: Boolean) {
        toolbar.visibility = if (v) View.VISIBLE else View.GONE
        handle.visibility = if (v) View.GONE else View.VISIBLE
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.decorView.windowInsetsController ?: return
            if (fullscreen) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen)
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN else 0
        }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)!!
        if (keyboard.visibility == View.VISIBLE) {
            keyboard.visibility = View.GONE
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        } else {
            keyboard.visibility = View.VISIBLE
            kbResult.text = ""
            input.requestFocus()
            input.post { imm.showSoftInput(input, 0) }
        }
    }

    private fun sendText(op: Int) {
        val t = input.text.toString()
        if (t.isEmpty() && op == TextOp.INSERT) return
        RemoteSession.sendText(op, t)
        kbResult.text = getString(R.string.kb_sending)
    }

    private fun showMore(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.m_notifications)
        m.menu.add(0, 2, 1, R.string.m_quick_settings)
        m.menu.add(0, 3, 2, R.string.m_wake)
        val q = m.menu.addSubMenu(0, 4, 3, R.string.m_quality)
        q.add(1, 41, 0, R.string.q_fast); q.add(1, 42, 1, R.string.q_balanced); q.add(1, 43, 2, R.string.q_auto)
        q.setGroupCheckable(1, true, true)
        when (Prefs.of(this).quality) { Quality.FAST -> q.findItem(41); Quality.BALANCED -> q.findItem(42); Quality.AUTO -> q.findItem(43) }?.isChecked = true
        m.menu.add(0, 5, 4, R.string.m_diagnostics).apply { isCheckable = true; isChecked = diag.visibility == View.VISIBLE }
        m.menu.add(0, 6, 5, R.string.settings)
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> RemoteSession.sendNav(Nav.NOTIFICATIONS)
                2 -> RemoteSession.sendNav(Nav.QUICK_SETTINGS)
                3 -> RemoteSession.sendNav(Nav.WAKE_SCREEN)
                41, 42, 43 -> {
                    Prefs.of(this).quality = when (item.itemId) { 41 -> Quality.FAST; 42 -> Quality.BALANCED; else -> Quality.AUTO }
                    RemoteSession.sendQuality()
                }
                5 -> {
                    val show = diag.visibility != View.VISIBLE
                    Prefs.of(this).showDiagnostics = show
                    diag.visibility = if (show) View.VISIBLE else View.GONE
                }
                6 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        m.show()
    }

    // ------------------------------------------------------------------ input

    private fun onRemoteTouch(v: View, ev: MotionEvent) {
        if (!RemoteSession.isReady) return
        val w = v.width.toFloat().coerceAtLeast(1f)
        val h = v.height.toFloat().coerceAtLeast(1f)
        val n = ev.pointerCount
        val ptrs = ArrayList<Pointer>(n)
        for (i in 0 until n) {
            // Clamp: a finger that slides off the picture keeps "holding" at the edge instead of jumping.
            ptrs.add(Pointer(ev.getPointerId(i), (ev.getX(i) / w).coerceIn(0f, 1f), (ev.getY(i) / h).coerceIn(0f, 1f)))
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                RemoteSession.sendTouch(TouchAction.DOWN, ev.getPointerId(ev.actionIndex), ptrs)
            MotionEvent.ACTION_MOVE -> RemoteSession.sendTouch(TouchAction.MOVE, 0, ptrs)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                RemoteSession.sendTouch(TouchAction.UP, ev.getPointerId(ev.actionIndex), ptrs)
            MotionEvent.ACTION_CANCEL -> RemoteSession.sendTouch(TouchAction.CANCEL, 0, ptrs)
        }
    }

    /** The Note's Back key/gesture controls the host. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (keyboard.visibility == View.VISIBLE) toggleKeyboard() else RemoteSession.sendNav(Nav.BACK)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Handled in dispatchKeyEvent; kept for devices that route back here")
    override fun onBackPressed() {
        if (keyboard.visibility == View.VISIBLE) toggleKeyboard() else RemoteSession.sendNav(Nav.BACK)
    }

    // ------------------------------------------------------------------ surface

    override fun surfaceCreated(holder: SurfaceHolder) {
        RemoteSession.decoder.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            // Keep the Note's edge back-gesture from stealing drags that start near the picture's sides.
            surface.systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        RemoteSession.decoder.setSurface(null)
    }

    // ------------------------------------------------------------------ session events

    override fun onVideoConfig(cfg: VideoConfig) {
        frame.setAspect(cfg.displayWidth.takeIf { it > 0 } ?: cfg.width, cfg.displayHeight.takeIf { it > 0 } ?: cfg.height)
        if (Prefs.of(this).autoRotate) {
            val landscape = cfg.displayWidth > cfg.displayHeight
            requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    override fun onState(state: LinkState) {
        if (state is LinkState.Pairing) {
            pairDigits.text = state.digits.substring(0, 3) + " " + state.digits.substring(3)
            pairCard.visibility = View.VISIBLE
        } else {
            pairCard.visibility = View.GONE
        }
        renderBanner()
    }

    override fun onStatus(status: Map<String, String>) {
        renderBanner()
    }

    override fun onTextResult(code: Int) {
        val host = hostLabel()
        kbResult.text = when (code) {
            TextResult.OK -> getString(R.string.kb_ok)
            TextResult.NO_FOCUS -> getString(R.string.kb_no_focus, host)
            TextResult.NOT_EDITABLE -> getString(R.string.kb_not_editable, host)
            TextResult.PASSWORD -> getString(R.string.kb_password, host)
            TextResult.UNSUPPORTED -> getString(R.string.kb_unsupported, host)
            TextResult.NO_ACCESSIBILITY -> getString(R.string.st_no_a11y, host)
            else -> getString(R.string.kb_failed)
        }
        if (code == TextResult.OK) input.setText("")
    }

    private fun hostLabel() = RemoteSession.hostName.ifBlank { RemoteSession.target?.host ?: "" }

    private fun tick() {
        val rendered = RemoteSession.decoder.renderedFrames
        // Counters only (no content): lets testers confirm the stream from logcat.
        if (++tickCount % 5 == 0) android.util.Log.i("NitaaqMirror", "video rendered=$rendered dropped=${RemoteSession.decoder.droppedFrames}")
        stalledSeconds = if (rendered == lastRendered && RemoteSession.state == LinkState.Connected) stalledSeconds + 1 else 0
        lastRendered = rendered
        if (diag.visibility == View.VISIBLE) {
            diag.text = RemoteSession.diagnostics().joinToString("\n") { (k, v) -> "${diagLabel(k)}: $v" }
        }
        renderBanner()
    }

    private fun diagLabel(k: String) = when (k) {
        "res" -> getString(R.string.diag_res); "fps" -> "FPS"; "bitrate" -> getString(R.string.diag_bitrate)
        "rtt" -> getString(R.string.diag_rtt); "glass" -> getString(R.string.diag_glass); "decode" -> getString(R.string.diag_decode)
        "queue" -> getString(R.string.diag_queue); "drops" -> getString(R.string.diag_drops); "enc" -> getString(R.string.diag_encoder)
        "dec" -> getString(R.string.diag_decoder); "temp" -> getString(R.string.diag_temp); "therm" -> getString(R.string.diag_thermal)
        "mem" -> getString(R.string.diag_mem); "host_os" -> getString(R.string.diag_host_os); "this_os" -> getString(R.string.diag_this_os); else -> k
    }

    private fun renderBanner() {
        val host = hostLabel()
        val st = RemoteSession.lastStatus
        var action: String? = null
        var onAction: (() -> Unit)? = null
        val retry = {
            RemoteSession.target?.let { t ->
                if (t.pairCode == null) RemoteSession.connect(this, t) else finish()
            }
            Unit
        }
        val text: String? = when (val s = RemoteSession.state) {
            LinkState.Idle -> { action = getString(R.string.back); onAction = { finish() }; getString(R.string.st_disconnected) }
            LinkState.Connecting -> getString(R.string.st_connecting, host)
            is LinkState.Pairing -> null
            is LinkState.Reconnecting -> getString(R.string.st_reconnecting, s.attempt)
            LinkState.HostNeedsApproval -> getString(R.string.st_needs_approval, host)
            is LinkState.Ended -> {
                action = getString(R.string.back); onAction = { finish() }
                when (s.reason) {
                    ByeReason.TRUST_REVOKED -> getString(R.string.st_trust_revoked, host)
                    ByeReason.REPLACED -> getString(R.string.st_replaced, host)
                    else -> getString(R.string.st_host_stopped, host)
                }
            }
            is LinkState.Failed -> {
                action = getString(R.string.retry); onAction = retry
                when (s.reason) {
                    FailReason.NETWORK_BLOCKED -> getString(R.string.err_network_blocked, host)
                    FailReason.UNREACHABLE -> getString(R.string.err_unreachable, host)
                    FailReason.WRONG_CODE -> { action = getString(R.string.back); onAction = { finish() }; getString(R.string.err_wrong_code) }
                    FailReason.TOO_MANY_ATTEMPTS -> { action = getString(R.string.back); onAction = { finish() }; getString(R.string.err_too_many) }
                    FailReason.REJECTED -> { action = getString(R.string.back); onAction = { finish() }; getString(R.string.err_rejected, host) }
                    FailReason.PAIRING_CLOSED -> { action = getString(R.string.back); onAction = { finish() }; getString(R.string.err_pairing_closed, host) }
                    FailReason.CERT_MISMATCH -> { action = getString(R.string.back); onAction = { finish() }; getString(R.string.err_cert_mismatch, host) }
                    FailReason.PROTOCOL -> getString(R.string.err_protocol)
                }
            }
            LinkState.Connected -> when {
                st["proj"] == "0" -> getString(R.string.st_needs_approval, host)
                st["touch"] == "none" -> getString(R.string.st_host_view_only, host)
                st["a11y"] == "0" -> getString(R.string.st_no_a11y, host)
                st["disp"] == "off" || st["disp"] == "doze" || st["inter"] == "0" -> {
                    action = getString(R.string.m_wake); onAction = { RemoteSession.sendNav(Nav.WAKE_SCREEN) }
                    getString(R.string.st_screen_off, host)
                }
                st["lock"] == "1" -> getString(R.string.st_locked, host)
                RemoteSession.decoder.renderedFrames == 0L -> getString(R.string.st_waiting_video)
                stalledSeconds >= 3 && st["fps"] == "0" -> getString(R.string.st_no_frames_host, host)
                stalledSeconds >= 3 -> getString(R.string.st_no_frames_net)
                else -> null
            }
        }
        val now = System.currentTimeMillis()
        val shown = text ?: transientMessage?.takeIf { now < transientUntil }
        if (shown == null) { banner.visibility = View.GONE; return }
        banner.visibility = View.VISIBLE
        bannerText.text = shown
        if (action != null && onAction != null) {
            bannerAction.visibility = View.VISIBLE
            bannerAction.text = action
            val a = onAction
            bannerAction.setOnClickListener { a() }
        } else bannerAction.visibility = View.GONE
    }
}
