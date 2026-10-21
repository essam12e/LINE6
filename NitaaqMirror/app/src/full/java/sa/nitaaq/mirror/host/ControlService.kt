package sa.nitaaq.mirror.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import sa.nitaaq.mirror.App
import sa.nitaaq.mirror.core.CommandGate
import sa.nitaaq.mirror.core.CoordinateMapper
import sa.nitaaq.mirror.core.GesturePlanner
import sa.nitaaq.mirror.core.SegmentPlan
import sa.nitaaq.mirror.proto.Nav
import sa.nitaaq.mirror.proto.TextOp
import sa.nitaaq.mirror.proto.TextResult
import sa.nitaaq.mirror.proto.TouchMsg
import sa.nitaaq.mirror.proto.reader
import java.text.BreakIterator

/**
 * Accessibility service enabled by the user on the host ("full" edition only). It is the only way (without root)
 * to inject touches into other apps. It receives no accessibility events, does nothing until a paired controller
 * is connected, and never records or stores screen content or text. Window content is read only on demand to
 * find the focused text field when the controller sends text.
 */
class ControlService : AccessibilityService(), TouchControl {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var planner = GesturePlanner()
    private var plannerGen = -1
    private val openStrokes = HashMap<Int, GestureDescription.StrokeDescription>()
    private var screenW = 0
    private var screenH = 0

    /**
     * Pipelined = each segment is dispatched as soon as it arrives (the platform queues continuations after the
     * previous segment). If a pipelined continuation is ever cancelled, we fall back to serial dispatch
     * (wait for completion, merge queued movement) and remember that for this device.
     */
    private var serialMode = false
    private var pipelinedCancels = 0
    private var inFlight = 0
    private val pending = ArrayDeque<SegmentPlan>()

    private var overlay: View? = null
    override val isKeepingAwake get() = overlay != null

    override fun onServiceConnected() {
        super.onServiceConnected()
        thread = HandlerThread("nm-inject", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        handler = Handler(thread.looper)
        planner = GesturePlanner(GestureDescription.getMaxStrokeCount().coerceIn(1, 10))
        serialMode = getSharedPreferences("gesture", Context.MODE_PRIVATE).getBoolean("serial", false)
        instance = this
        TouchControl.active = this
        HostState.changed()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        if (TouchControl.active === this) TouchControl.active = null
        removeOverlay()
        if (::thread.isInitialized) thread.quitSafely()
        HostState.changed()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ------------------------------------------------------------------ touches

    override fun onTouch(gate: CommandGate, gen: Int, payload: ByteArray, receivedAt: Long) {
        handler.post {
            val msg = try { TouchMsg.decode(payload) } catch (_: Exception) { return@post }
            if (!gate.accept(gen, msg.seq)) return@post
            if (gen != plannerGen) {
                // First command of a new session: lift anything left over from the previous one.
                planner.endAll()?.let { dispatch(it, pipelined = false) }
                pending.clear()
                plannerGen = gen
            }
            if (GesturePlanner.isStale(msg.action, SystemClock.uptimeMillis() - receivedAt)) return@post
            if (!planner.isGestureOpen) refreshScreenSize()
            val plan = planner.plan(msg) ?: return@post
            submit(plan)
        }
    }

    override fun endAllGestures() {
        if (!::handler.isInitialized) return
        handler.post {
            pending.clear()
            planner.endAll()?.let { dispatch(it, pipelined = false) }
        }
    }

    private fun submit(plan: SegmentPlan) {
        if (pending.isNotEmpty() || !canDispatchNow(plan)) {
            val last = pending.lastOrNull()
            val merged = if (serialMode && last != null) merge(last, plan) else null
            if (merged != null) pending[pending.size - 1] = merged else pending.addLast(plan)
            return
        }
        dispatch(plan, pipelined = inFlight > 0)
    }

    /**
     * A new gesture must wait until the previous one's last segment was injected: dispatching it earlier would
     * make the platform cancel the pending release, turning a quick tap (or the first half of a double-tap)
     * into nothing. Continuations go straight through (pipelined) unless we fell back to serial mode.
     */
    private fun canDispatchNow(plan: SegmentPlan): Boolean {
        if (inFlight == 0) return true
        if (plan.strokes.all { it.startsNew }) return false
        return !serialMode
    }

    /** Merges two pure-movement segments over the same fingers into one (serial mode catch-up). */
    private fun merge(a: SegmentPlan, b: SegmentPlan): SegmentPlan? {
        if (a.strokes.size != b.strokes.size) return null
        val out = ArrayList<sa.nitaaq.mirror.core.StrokePlan>(a.strokes.size)
        for (i in a.strokes.indices) {
            val x = a.strokes[i]; val y = b.strokes[i]
            if (x.pointerId != y.pointerId || !x.willContinue || !y.willContinue || y.startsNew) return null
            out.add(x.copy(path = x.path + y.path.drop(1)))
        }
        return SegmentPlan(out, (a.durationMs + b.durationMs).coerceAtMost(GesturePlanner.MAX_SEGMENT_MS))
    }

    private fun dispatch(plan: SegmentPlan, pipelined: Boolean) {
        if (screenW <= 0) refreshScreenSize()
        val builder = GestureDescription.Builder()
        // Ending segment: release promptly (tap = 1 ms, fling tail <= 8 ms) so the next tap is not held back.
        val ending = plan.strokes.none { it.willContinue }
        val moving = plan.strokes.any { it.path.size > 1 }
        val dur = if (ending) (if (moving) plan.durationMs.coerceIn(1, 8) else 1L) else plan.durationMs.coerceAtLeast(1)
        var continuation = false
        try {
            for (s in plan.strokes) {
                val path = Path()
                s.path.forEachIndexed { i, p ->
                    // Whole pixels: the platform only accepts a continuation whose first point is *exactly*
                    // (float ==) the previous end point, and integer coordinates survive path measuring exactly.
                    val (fx, fy) = CoordinateMapper.toHostPixels(p, screenW, screenH)
                    val x = Math.round(fx).toFloat()
                    val y = Math.round(fy).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val prev = openStrokes[s.pointerId]
                val stroke = if (!s.startsNew && prev != null && prev.willContinue()) {
                    continuation = true
                    prev.continueStroke(path, 0, dur, s.willContinue)
                } else {
                    GestureDescription.StrokeDescription(path, 0, dur, s.willContinue)
                }
                builder.addStroke(stroke)
                if (s.willContinue) openStrokes[s.pointerId] = stroke else openStrokes.remove(s.pointerId)
            }
        } catch (_: Exception) {
            resetGestureState(); return
        }
        val wasPipelined = pipelined && continuation
        inFlight++
        val ok = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) {
                inFlight = (inFlight - 1).coerceAtLeast(0)
                flushPending()
            }

            override fun onCancelled(d: GestureDescription?) {
                inFlight = (inFlight - 1).coerceAtLeast(0)
                // One cancel can be the owner touching the host screen; repeated ones mean the platform
                // rejects pipelined continuations on this device.
                if (wasPipelined && !serialMode && ++pipelinedCancels >= 2) {
                    serialMode = true
                    getSharedPreferences("gesture", Context.MODE_PRIVATE).edit().putBoolean("serial", true).apply()
                }
                // The platform dropped our pointer(s) (or the host screen was touched): start clean.
                resetGestureState()
            }
        }, handler)
        if (!ok) { inFlight = (inFlight - 1).coerceAtLeast(0); resetGestureState() }
    }

    private fun flushPending() {
        while (true) {
            val next = pending.firstOrNull() ?: return
            if (!canDispatchNow(next)) return
            pending.removeFirst()
            dispatch(next, pipelined = inFlight > 0)
        }
    }

    private fun resetGestureState() {
        planner.forget()
        openStrokes.clear()
        pending.clear()
    }

    @Suppress("DEPRECATION")
    private fun refreshScreenSize() {
        val d = getSystemService(DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val m = DisplayMetrics()
        d.getRealMetrics(m)
        screenW = m.widthPixels; screenH = m.heightPixels
    }

    // ------------------------------------------------------------------ navigation

    override fun nav(action: Int) {
        handler.post {
            val g = when (action) {
                Nav.BACK -> GLOBAL_ACTION_BACK
                Nav.HOME -> GLOBAL_ACTION_HOME
                Nav.RECENTS -> GLOBAL_ACTION_RECENTS
                Nav.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                Nav.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
                else -> return@post
            }
            performGlobalAction(g)
        }
    }

    // ------------------------------------------------------------------ text

    /** Applies a text operation to the focused input field. The text is never stored or logged. */
    override fun text(payload: ByteArray, reply: (Int) -> Unit) {
        handler.post {
            val result = try { applyText(payload) } catch (_: Exception) { TextResult.FAILED }
            reply(result)
        }
    }

    private fun applyText(payload: ByteArray): Int {
        val r = payload.reader()
        val op = r.readUnsignedByte()
        val txt = if (op == TextOp.INSERT || op == TextOp.REPLACE_ALL) r.readUTF() else ""
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return TextResult.NO_FOCUS
        if (op == TextOp.SHOW_KEYBOARD) {
            softKeyboardController.showMode = SHOW_MODE_AUTO
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return if (ok) TextResult.OK else TextResult.UNSUPPORTED
        }
        if (!node.isEditable) return TextResult.NOT_EDITABLE
        if (node.isPassword) return TextResult.PASSWORD // deliberately not supported
        if (op == TextOp.ENTER) {
            if (Build.VERSION.SDK_INT >= 30) {
                val enter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                if (node.actionList.any { it.id == enter.id }) {
                    return if (node.performAction(enter.id)) TextResult.OK else TextResult.UNSUPPORTED
                }
            }
            return TextResult.UNSUPPORTED
        }
        val hasText = node.text != null && !(Build.VERSION.SDK_INT >= 26 && node.isShowingHintText)
        val cur = if (hasText) node.text.toString() else ""
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > cur.length || e > cur.length) { s = cur.length; e = cur.length }
        if (s > e) { val t = s; s = e; e = t }
        val newText: String
        val cursor: Int
        when (op) {
            TextOp.INSERT -> { newText = cur.substring(0, s) + txt + cur.substring(e); cursor = s + txt.length }
            TextOp.REPLACE_ALL -> { newText = txt; cursor = txt.length }
            TextOp.BACKSPACE -> {
                if (s != e) { newText = cur.substring(0, s) + cur.substring(e); cursor = s }
                else if (s == 0) return TextResult.OK
                else {
                    // Delete one user-perceived character (Arabic marks, emoji, surrogate pairs).
                    val bi = BreakIterator.getCharacterInstance()
                    bi.setText(cur)
                    val prev = bi.preceding(s).let { if (it == BreakIterator.DONE) s - 1 else it }
                    newText = cur.substring(0, prev) + cur.substring(s); cursor = prev
                }
            }
            else -> return TextResult.UNSUPPORTED
        }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return TextResult.UNSUPPORTED
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return TextResult.OK
    }

    // ------------------------------------------------------------------ keep awake

    /**
     * Optional "keep the host awake during a session": a 1x1, fully transparent, non-touchable accessibility
     * overlay with FLAG_KEEP_SCREEN_ON. It neither covers the screen nor receives touches.
     */
    override fun setKeepAwake(on: Boolean) {
        App.main.post {
            if (on && overlay == null) {
                val wm = getSystemService(WindowManager::class.java)!!
                val v = View(this)
                val lp = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSPARENT,
                ).apply { gravity = Gravity.TOP or Gravity.START }
                try { wm.addView(v, lp); overlay = v } catch (_: Exception) {}
            } else if (!on) {
                removeOverlay()
            }
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try { getSystemService(WindowManager::class.java)!!.removeView(v) } catch (_: Exception) {}
    }

    companion object {
        @Volatile var instance: ControlService? = null
            private set
    }
}
