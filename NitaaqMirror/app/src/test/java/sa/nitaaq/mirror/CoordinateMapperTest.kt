package sa.nitaaq.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sa.nitaaq.mirror.core.Box
import sa.nitaaq.mirror.core.CoordinateMapper
import sa.nitaaq.mirror.core.NormPoint
import kotlin.math.abs

class CoordinateMapperTest {

    @Test fun portraitHostInPortraitControllerUsesFullHeightWithSideBars() {
        // Controller 1080x2280 view, host 1220x2712 (aspect ~0.45 vs ~0.47): bars left/right.
        val b = CoordinateMapper.fit(1080, 2280, 1220, 2712)
        assertEquals(2280, b.height)
        assertTrue(b.width < 1080)
        assertEquals((1080 - b.width) / 2, b.left)
        assertEquals(1220.0 / 2712, b.width.toDouble() / b.height, 0.002)
    }

    @Test fun landscapeHostInPortraitControllerLetterboxes() {
        val b = CoordinateMapper.fit(1080, 2280, 2712, 1220)
        assertEquals(1080, b.width)
        assertTrue(b.height < 2280)
        assertEquals((2280 - b.height) / 2, b.top)
    }

    @Test fun touchesOnBlackBarsAreRejected() {
        val b = CoordinateMapper.fit(1080, 2280, 2712, 1220) // bars above/below
        assertNull(CoordinateMapper.toNormalized(540f, 10f, b))
        assertNull(CoordinateMapper.toNormalized(540f, 2270f, b))
        assertNotNull(CoordinateMapper.toNormalized(540f, 1140f, b))
    }

    @Test fun centreMapsToCentreAcrossResolutions() {
        val b = CoordinateMapper.fit(1080, 2280, 1220, 2712)
        val n = CoordinateMapper.toNormalized(b.left + b.width / 2f, b.top + b.height / 2f, b)!!
        assertEquals(0.5f, n.x, 0.001f); assertEquals(0.5f, n.y, 0.001f)
        val (x, y) = CoordinateMapper.toHostPixels(n, 1220, 2712)
        assertEquals(610f, x, 1f); assertEquals(1356f, y, 1f)
    }

    @Test fun cornersStayInsideHostDisplay() {
        val (x0, y0) = CoordinateMapper.toHostPixels(NormPoint(0f, 0f), 1220, 2712)
        val (x1, y1) = CoordinateMapper.toHostPixels(NormPoint(1f, 1f), 1220, 2712)
        assertEquals(0f, x0); assertEquals(0f, y0)
        assertEquals(1219f, x1); assertEquals(2711f, y1)
    }

    @Test fun rotationUsesCurrentHostSize() {
        val p = NormPoint(0.25f, 0.75f)
        val (px, py) = CoordinateMapper.toHostPixels(p, 1220, 2712)
        val (lx, ly) = CoordinateMapper.toHostPixels(p, 2712, 1220)
        assertEquals(305f, px, 0.5f); assertEquals(2034f, py, 0.5f)
        assertEquals(678f, lx, 0.5f); assertEquals(915f, ly, 0.5f)
    }

    @Test fun dragLeavingThePictureIsClampedNotDropped() {
        val b = Box(100, 0, 880, 2280)
        val n = CoordinateMapper.toNormalizedClamped(5000f, -40f, b)
        assertEquals(1f, n.x); assertEquals(0f, n.y)
    }

    @Test fun degenerateSizesDoNotCrash() {
        val b = CoordinateMapper.fit(0, 0, 1220, 2712)
        assertEquals(0, b.width)
        assertNull(CoordinateMapper.toNormalized(1f, 1f, b))
    }

    @Test fun mappingRoundTripIsAccurateToAPixel() {
        val host = 1220 to 2712
        val box = CoordinateMapper.fit(1080, 2220, host.first, host.second)
        for (i in 0..20) {
            val vx = box.left + box.width * i / 20f
            val vy = box.top + box.height * (20 - i) / 20f
            val n = CoordinateMapper.toNormalized(vx, vy, box)!!
            val (hx, hy) = CoordinateMapper.toHostPixels(n, host.first, host.second)
            val ex = (host.first * i / 20f).coerceAtMost(host.first - 1f)
            val ey = (host.second * (20 - i) / 20f).coerceAtMost(host.second - 1f)
            assertTrue("x $hx vs $ex", abs(hx - ex) <= 1.5f)
            assertTrue("y $hy vs $ey", abs(hy - ey) <= 1.5f)
        }
    }
}
