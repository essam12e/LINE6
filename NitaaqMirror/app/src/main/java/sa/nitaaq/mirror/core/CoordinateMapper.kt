package sa.nitaaq.mirror.core

import kotlin.math.roundToInt

/** Integer rectangle (left, top, width, height). */
data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right get() = left + width
    val bottom get() = top + height
}

data class NormPoint(val x: Float, val y: Float)

/**
 * Coordinate conversions between the controller's view and the host display.
 *
 * Controller side: the remote picture is drawn into [contentBox] = the largest rectangle with the host's aspect
 * ratio that fits the container (letterbox / pillarbox). Touches are converted to 0..1 coordinates of that
 * picture. Touches that *start* on the black bars are rejected; once a gesture has started, points are clamped
 * to the edge so a drag that leaves the picture still ends cleanly.
 *
 * Host side: 0..1 coordinates are scaled to the *current* real display size (which already reflects rotation).
 */
object CoordinateMapper {

    /** Largest box with aspect contentW:contentH, centred inside containerW x containerH. */
    fun fit(containerW: Int, containerH: Int, contentW: Int, contentH: Int): Box {
        if (containerW <= 0 || containerH <= 0 || contentW <= 0 || contentH <= 0) return Box(0, 0, 0, 0)
        val scale = minOf(containerW.toDouble() / contentW, containerH.toDouble() / contentH)
        val w = (contentW * scale).roundToInt().coerceIn(1, containerW)
        val h = (contentH * scale).roundToInt().coerceIn(1, containerH)
        return Box((containerW - w) / 2, (containerH - h) / 2, w, h)
    }

    /** Point in container coordinates -> normalised content point, or null if it is outside the picture. */
    fun toNormalized(x: Float, y: Float, box: Box): NormPoint? {
        if (box.width <= 0 || box.height <= 0) return null
        if (x < box.left || y < box.top || x > box.right || y > box.bottom) return null
        return NormPoint(((x - box.left) / box.width).coerceIn(0f, 1f), ((y - box.top) / box.height).coerceIn(0f, 1f))
    }

    /** Same as [toNormalized] but clamps to the picture edge (used for MOVE/UP after an accepted DOWN). */
    fun toNormalizedClamped(x: Float, y: Float, box: Box): NormPoint {
        if (box.width <= 0 || box.height <= 0) return NormPoint(0f, 0f)
        return NormPoint(((x - box.left) / box.width).coerceIn(0f, 1f), ((y - box.top) / box.height).coerceIn(0f, 1f))
    }

    /** Normalised point -> host pixel, kept strictly inside the display (gesture paths must be non-negative). */
    fun toHostPixels(p: NormPoint, hostW: Int, hostH: Int): Pair<Float, Float> {
        val maxX = (hostW - 1).coerceAtLeast(0).toFloat()
        val maxY = (hostH - 1).coerceAtLeast(0).toFloat()
        return Pair((p.x * hostW).coerceIn(0f, maxX), (p.y * hostH).coerceIn(0f, maxY))
    }
}
