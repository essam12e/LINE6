package sa.nitaaq.mirror.core

import sa.nitaaq.mirror.proto.TouchAction
import sa.nitaaq.mirror.proto.TouchMsg

/** One stroke inside a gesture segment, in normalised coordinates. */
data class StrokePlan(
    val pointerId: Int,
    val path: List<NormPoint>,
    /** true = a new finger (new stroke); false = continues this finger's previous stroke. */
    val startsNew: Boolean,
    /** true = finger stays down after this segment. */
    val willContinue: Boolean,
)

data class SegmentPlan(val strokes: List<StrokePlan>, val durationMs: Long)

/**
 * Turns the live touch stream from the controller into *continued* accessibility gesture segments.
 *
 * Why: dispatching every finger movement as an independent gesture would cancel the previous one (the host would
 * see a series of short taps). Instead the first DOWN starts a stroke with willContinue=true, every MOVE continues
 * that same stroke (StrokeDescription.continueStroke), and UP ends it. The finger therefore stays "down" on the
 * host exactly as long as on the controller, so taps, long-presses, drags, flings and double-taps behave
 * naturally, and the host's own long-press timeout applies.
 *
 * Every segment lists *all* fingers that are down, because the platform treats a continued gesture that omits an
 * open stroke as lifting it. Extra fingers (pinch) are added as new strokes inside a continuation.
 *
 * Segment duration follows the real time between events, clamped to [MIN_SEGMENT_MS, MAX_SEGMENT_MS] so that a
 * finger resting still for seconds does not turn the next movement into a slow-motion replay.
 *
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
class GesturePlanner(private val maxStrokes: Int = 10) {

    private val active = LinkedHashMap<Int, NormPoint>()
    private var lastTimeMs = 0L

    val isGestureOpen: Boolean get() = active.isNotEmpty()
    val activePointerCount: Int get() = active.size

    fun plan(msg: TouchMsg): SegmentPlan? {
        val current = HashMap<Int, NormPoint>()
        for (p in msg.pointers) current[p.id] = NormPoint(p.x, p.y)
        return when (msg.action) {
            TouchAction.DOWN -> down(msg, current)
            TouchAction.MOVE -> move(msg, current)
            TouchAction.UP -> up(msg, current)
            TouchAction.CANCEL -> endAll()
            else -> null
        }
    }

    private fun down(msg: TouchMsg, current: Map<Int, NormPoint>): SegmentPlan? {
        val newPoint = current[msg.changedId] ?: return null
        if (active.containsKey(msg.changedId)) return move(msg, current)
        val wasEmpty = active.isEmpty()
        val strokes = ArrayList<StrokePlan>()
        for ((id, last) in active) {
            val cur = current[id] ?: last
            strokes.add(StrokePlan(id, path(last, cur), startsNew = false, willContinue = true))
            active[id] = cur
        }
        if (active.size >= maxStrokes) {
            return if (strokes.isEmpty()) null else SegmentPlan(strokes, takeDuration(msg.timeMs))
        }
        strokes.add(StrokePlan(msg.changedId, listOf(newPoint), startsNew = true, willContinue = true))
        active[msg.changedId] = newPoint
        val dur = if (wasEmpty) { lastTimeMs = msg.timeMs; MIN_SEGMENT_MS } else takeDuration(msg.timeMs)
        return SegmentPlan(strokes, dur)
    }

    private fun move(msg: TouchMsg, current: Map<Int, NormPoint>): SegmentPlan? {
        if (active.isEmpty()) return null
        var moved = false
        val strokes = ArrayList<StrokePlan>(active.size)
        for ((id, last) in active) {
            val cur = current[id] ?: last
            if (cur != last) moved = true
            strokes.add(StrokePlan(id, path(last, cur), startsNew = false, willContinue = true))
        }
        if (!moved) return null // nothing to inject; time keeps accumulating (clamped later)
        for (s in strokes) active[s.pointerId] = s.path.last()
        return SegmentPlan(strokes, takeDuration(msg.timeMs))
    }

    private fun up(msg: TouchMsg, current: Map<Int, NormPoint>): SegmentPlan? {
        if (!active.containsKey(msg.changedId)) return null
        val strokes = ArrayList<StrokePlan>(active.size)
        for ((id, last) in active) {
            val cur = current[id] ?: last
            strokes.add(StrokePlan(id, path(last, cur), startsNew = false, willContinue = id != msg.changedId))
            active[id] = cur
        }
        active.remove(msg.changedId)
        return SegmentPlan(strokes, takeDuration(msg.timeMs))
    }

    /** Lifts every open finger where it is (used for CANCEL, disconnect, rotation). */
    fun endAll(): SegmentPlan? {
        if (active.isEmpty()) return null
        val strokes = active.map { (id, last) -> StrokePlan(id, listOf(last), startsNew = false, willContinue = false) }
        active.clear()
        return SegmentPlan(strokes, MIN_SEGMENT_MS)
    }

    /** The platform cancelled our gesture (e.g. the host's own screen was touched): forget open strokes. */
    fun forget() {
        active.clear()
    }

    private fun takeDuration(t: Long): Long {
        val d = (t - lastTimeMs).coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
        lastTimeMs = t
        return d
    }

    private fun path(a: NormPoint, b: NormPoint) = if (a == b) listOf(a) else listOf(a, b)

    companion object {
        const val MIN_SEGMENT_MS = 1L
        const val MAX_SEGMENT_MS = 40L

        /** Drop commands that sat in a queue for too long (e.g. after a stall). UP/CANCEL are never dropped. */
        fun isStale(action: Int, ageMs: Long): Boolean = when (action) {
            TouchAction.DOWN -> ageMs > 700
            TouchAction.MOVE -> ageMs > 250
            else -> false
        }
    }
}

/**
 * Accepts control commands only from the current authenticated session, in order.
 * A reconnect starts a new generation, so anything still queued from an older connection is discarded.
 */
class CommandGate {
    @Volatile var generation = 0
        private set
    private var lastSeq = Int.MIN_VALUE
    @Volatile private var authenticated = false

    @Synchronized
    fun startSession(): Int {
        generation++
        lastSeq = Int.MIN_VALUE
        authenticated = true
        return generation
    }

    @Synchronized
    fun endSession(gen: Int) {
        if (gen == generation) authenticated = false
    }

    /** For commands without a sequence number (navigation, text): only the generation is checked. */
    fun isCurrent(gen: Int): Boolean = authenticated && gen == generation

    @Synchronized
    fun accept(gen: Int, seq: Int): Boolean {
        if (!authenticated || gen != generation) return false
        if (seq <= lastSeq) return false
        lastSeq = seq
        return true
    }
}
