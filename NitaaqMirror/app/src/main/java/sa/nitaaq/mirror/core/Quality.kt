package sa.nitaaq.mirror.core

import kotlin.math.roundToInt

enum class Quality(val code: Int) {
    /** ~720p short side, low bitrate: best responsiveness. */
    FAST(0),
    /** Up to ~1080p short side (never above the real display). */
    BALANCED(1),
    /** Starts like FAST, then raises/lowers bitrate (and drops to 540p) from measurements. */
    AUTO(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

data class EncodeParams(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

object QualityPolicy {
    const val MIN_BITRATE = 1_000_000
    const val MAX_BITRATE = 12_000_000

    fun shortSide(q: Quality, autoStep: Int = 0): Int = when (q) {
        Quality.FAST -> 720
        Quality.BALANCED -> 1080
        Quality.AUTO -> if (autoStep > 0) 540 else 720
    }

    fun initialBitrate(q: Quality, fps: Int): Int {
        val base = when (q) {
            Quality.FAST -> 4_000_000
            Quality.BALANCED -> 8_000_000
            Quality.AUTO -> 5_000_000
        }
        return if (fps > 30) (base * 1.5).toInt() else base
    }

    /**
     * Encoder size for a display of displayW x displayH: same aspect ratio (no cropping), short side limited to
     * [maxShort], never upscaled, both sides multiples of [align] (hardware encoders want 16).
     */
    fun encodeSize(displayW: Int, displayH: Int, maxShort: Int, align: Int = 16): Pair<Int, Int> {
        require(displayW > 0 && displayH > 0)
        val short = minOf(displayW, displayH)
        val scale = if (short > maxShort) maxShort.toDouble() / short else 1.0
        fun al(v: Double) = ((v / align).roundToInt() * align).coerceAtLeast(align)
        return Pair(al(displayW * scale), al(displayH * scale))
    }

    fun params(q: Quality, fps: Int, displayW: Int, displayH: Int, autoStep: Int = 0): EncodeParams {
        val (w, h) = encodeSize(displayW, displayH, shortSide(q, autoStep))
        return EncodeParams(w, h, fps, initialBitrate(q, fps))
    }
}

/**
 * Adaptive bitrate. Fed once per second with how long frames waited in the host's send queue and how many were
 * dropped to keep the picture fresh. Freshness beats completeness: on congestion we cut fast, then recover slowly.
 */
class AdaptiveBitrate(initial: Int, private val min: Int = QualityPolicy.MIN_BITRATE, private val max: Int = 8_000_000) {
    var bitrate = initial.coerceIn(min, max)
        private set
    private var calmSeconds = 0
    /** Seconds spent at the floor while still congested — AUTO then lowers resolution. */
    var congestedAtFloor = 0
        private set

    /** Returns the new bitrate if it changed. */
    fun onSecond(queueDelayMs: Double, droppedFrames: Int): Int? {
        val congested = queueDelayMs > 80 || droppedFrames > 0
        val old = bitrate
        if (congested) {
            calmSeconds = 0
            bitrate = (bitrate * 0.7).toInt().coerceAtLeast(min)
            congestedAtFloor = if (bitrate == min) congestedAtFloor + 1 else 0
        } else {
            congestedAtFloor = 0
            if (queueDelayMs < 25) calmSeconds++ else calmSeconds = 0
            if (calmSeconds >= 4) {
                bitrate = (bitrate * 1.15).toInt().coerceAtMost(max)
                calmSeconds = 0
            }
        }
        return if (bitrate != old) bitrate else null
    }
}

/** Rolling window for avg / p95 (diagnostics). */
class RollingStats(private val capacity: Int = 60) {
    private val values = DoubleArray(capacity)
    private var count = 0
    private var next = 0

    @Synchronized
    fun add(v: Double) {
        values[next] = v
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun average(): Double = if (count == 0) Double.NaN else (0 until count).sumOf { values[it] } / count

    @Synchronized
    fun percentile(p: Double): Double {
        if (count == 0) return Double.NaN
        val sorted = values.copyOf(count).sorted()
        val idx = ((p / 100.0) * (count - 1)).roundToInt().coerceIn(0, count - 1)
        return sorted[idx]
    }

    @Synchronized
    fun max(): Double = if (count == 0) Double.NaN else (0 until count).maxOf { values[it] }

    @Synchronized
    fun size() = count

    @Synchronized
    fun clear() { count = 0; next = 0 }
}
