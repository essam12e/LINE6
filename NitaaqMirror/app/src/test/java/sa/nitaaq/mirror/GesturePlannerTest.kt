package sa.nitaaq.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sa.nitaaq.mirror.core.CommandGate
import sa.nitaaq.mirror.core.GesturePlanner
import sa.nitaaq.mirror.core.NormPoint
import sa.nitaaq.mirror.proto.Pointer
import sa.nitaaq.mirror.proto.TouchAction
import sa.nitaaq.mirror.proto.TouchMsg

class GesturePlannerTest {
    private var seq = 0
    private fun msg(action: Int, changed: Int, t: Long, vararg p: Pointer) = TouchMsg(action, changed, ++seq, t, p.toList())

    @Test fun tapIsDownHeldThenReleasedAtSamePoint() {
        val g = GesturePlanner()
        val down = g.plan(msg(TouchAction.DOWN, 0, 1000, Pointer(0, 0.5f, 0.5f)))!!
        assertEquals(1, down.strokes.size)
        assertTrue(down.strokes[0].startsNew)
        assertTrue("finger stays down until UP arrives", down.strokes[0].willContinue)
        val up = g.plan(msg(TouchAction.UP, 0, 1080, Pointer(0, 0.5f, 0.5f)))!!
        assertFalse(up.strokes[0].startsNew)
        assertFalse(up.strokes[0].willContinue)
        assertEquals(listOf(NormPoint(0.5f, 0.5f)), up.strokes[0].path)
        assertFalse(g.isGestureOpen)
    }

    @Test fun dragSegmentsAreContinuousAndNeverRestart() {
        val g = GesturePlanner()
        g.plan(msg(TouchAction.DOWN, 0, 0, Pointer(0, 0.1f, 0.9f)))
        var last = NormPoint(0.1f, 0.9f)
        for (i in 1..20) {
            val p = Pointer(0, 0.1f + i * 0.02f, 0.9f - i * 0.03f)
            val s = g.plan(msg(TouchAction.MOVE, 0, i * 16L, p))!!
            val st = s.strokes.single()
            assertFalse("a move must continue the stroke, not start a new gesture", st.startsNew)
            assertTrue(st.willContinue)
            assertEquals("segment starts where the previous one ended", last, st.path.first())
            last = st.path.last()
            assertEquals(16L, s.durationMs)
        }
        val up = g.plan(msg(TouchAction.UP, 0, 21 * 16L, Pointer(0, last.x, last.y)))!!
        assertFalse(up.strokes.single().willContinue)
    }

    @Test fun longPauseDoesNotBecomeSlowMotion() {
        val g = GesturePlanner()
        g.plan(msg(TouchAction.DOWN, 0, 0, Pointer(0, 0.5f, 0.5f)))
        val s = g.plan(msg(TouchAction.MOVE, 0, 3000, Pointer(0, 0.6f, 0.5f)))!!
        assertEquals(GesturePlanner.MAX_SEGMENT_MS, s.durationMs)
    }

    @Test fun stationaryMoveIsSkipped() {
        val g = GesturePlanner()
        g.plan(msg(TouchAction.DOWN, 0, 0, Pointer(0, 0.5f, 0.5f)))
        assertNull(g.plan(msg(TouchAction.MOVE, 0, 10, Pointer(0, 0.5f, 0.5f))))
        assertTrue(g.isGestureOpen)
    }

    @Test fun pinchAddsSecondFingerWhileFirstContinues() {
        val g = GesturePlanner()
        g.plan(msg(TouchAction.DOWN, 0, 0, Pointer(0, 0.4f, 0.5f)))
        val two = g.plan(msg(TouchAction.DOWN, 1, 30, Pointer(0, 0.4f, 0.5f), Pointer(1, 0.6f, 0.5f)))!!
        assertEquals(2, two.strokes.size)
        val first = two.strokes.first { it.pointerId == 0 }
        val second = two.strokes.first { it.pointerId == 1 }
        assertFalse(first.startsNew); assertTrue(first.willContinue)
        assertTrue(second.startsNew); assertTrue(second.willContinue)

        val spread = g.plan(msg(TouchAction.MOVE, 0, 46, Pointer(0, 0.3f, 0.5f), Pointer(1, 0.7f, 0.5f)))!!
        assertEquals(2, spread.strokes.size)
        assertTrue(spread.strokes.all { !it.startsNew && it.willContinue })

        val lift1 = g.plan(msg(TouchAction.UP, 1, 60, Pointer(0, 0.3f, 0.5f), Pointer(1, 0.7f, 0.5f)))!!
        assertFalse(lift1.strokes.first { it.pointerId == 1 }.willContinue)
        assertTrue(lift1.strokes.first { it.pointerId == 0 }.willContinue)
        assertEquals(1, g.activePointerCount)
    }

    @Test fun cancelLiftsEveryFinger() {
        val g = GesturePlanner()
        g.plan(msg(TouchAction.DOWN, 0, 0, Pointer(0, 0.4f, 0.5f)))
        g.plan(msg(TouchAction.DOWN, 1, 5, Pointer(0, 0.4f, 0.5f), Pointer(1, 0.6f, 0.5f)))
        val c = g.plan(msg(TouchAction.CANCEL, 0, 9))!!
        assertEquals(2, c.strokes.size)
        assertTrue(c.strokes.none { it.willContinue })
        assertFalse(g.isGestureOpen)
    }

    @Test fun movesWithoutDownAreIgnored() {
        val g = GesturePlanner()
        assertNull(g.plan(msg(TouchAction.MOVE, 0, 0, Pointer(0, 0.1f, 0.1f))))
        assertNull(g.plan(msg(TouchAction.UP, 0, 5, Pointer(0, 0.1f, 0.1f))))
    }

    @Test fun afterPlatformCancelTheRestOfTheGestureIsDropped() {
        val g = GesturePlanner()
        g.plan(msg(TouchAction.DOWN, 0, 0, Pointer(0, 0.1f, 0.1f)))
        g.forget()
        assertNull(g.plan(msg(TouchAction.MOVE, 0, 16, Pointer(0, 0.2f, 0.2f))))
        assertNull(g.plan(msg(TouchAction.UP, 0, 32, Pointer(0, 0.2f, 0.2f))))
        // A fresh touch works again.
        assertTrue(g.plan(msg(TouchAction.DOWN, 0, 50, Pointer(0, 0.3f, 0.3f)))!!.strokes[0].startsNew)
    }

    @Test fun doubleTapIsTwoCompleteTaps() {
        val g = GesturePlanner()
        val p = Pointer(0, 0.5f, 0.5f)
        val a = listOf(g.plan(msg(TouchAction.DOWN, 0, 0, p)), g.plan(msg(TouchAction.UP, 0, 60, p)),
            g.plan(msg(TouchAction.DOWN, 0, 150, p)), g.plan(msg(TouchAction.UP, 0, 210, p)))
        assertTrue(a[0]!!.strokes[0].startsNew); assertFalse(a[1]!!.strokes[0].willContinue)
        assertTrue(a[2]!!.strokes[0].startsNew); assertFalse(a[3]!!.strokes[0].willContinue)
    }

    @Test fun endAllLiftsOpenFingers() {
        val g = GesturePlanner()
        g.plan(msg(TouchAction.DOWN, 0, 0, Pointer(0, 0.1f, 0.1f)))
        val e = g.endAll()!!
        assertFalse(e.strokes.single().willContinue)
        assertNull(g.endAll())
    }

    @Test fun maxStrokesIsRespected() {
        val g = GesturePlanner(maxStrokes = 2)
        g.plan(msg(TouchAction.DOWN, 0, 0, Pointer(0, 0.1f, 0.1f)))
        g.plan(msg(TouchAction.DOWN, 1, 1, Pointer(0, 0.1f, 0.1f), Pointer(1, 0.2f, 0.2f)))
        val third = g.plan(msg(TouchAction.DOWN, 2, 2, Pointer(0, 0.1f, 0.1f), Pointer(1, 0.2f, 0.2f), Pointer(2, 0.3f, 0.3f)))
        assertTrue(third == null || third.strokes.none { it.pointerId == 2 })
        assertEquals(2, g.activePointerCount)
    }

    @Test fun staleRules() {
        assertTrue(GesturePlanner.isStale(TouchAction.DOWN, 900))
        assertFalse(GesturePlanner.isStale(TouchAction.DOWN, 100))
        assertTrue(GesturePlanner.isStale(TouchAction.MOVE, 400))
        assertFalse("UP is never dropped (would leave a finger stuck)", GesturePlanner.isStale(TouchAction.UP, 60_000))
        assertFalse(GesturePlanner.isStale(TouchAction.CANCEL, 60_000))
    }
}

class CommandGateTest {
    @Test fun nothingIsAcceptedBeforeAuthentication() {
        val g = CommandGate()
        assertFalse(g.accept(0, 1))
        assertFalse(g.isCurrent(0))
    }

    @Test fun commandsFromAnOlderSessionAreRejected() {
        val g = CommandGate()
        val old = g.startSession()
        assertTrue(g.accept(old, 1))
        val now = g.startSession()
        assertFalse("queued command from the previous connection", g.accept(old, 2))
        assertFalse(g.isCurrent(old))
        assertTrue(g.accept(now, 1))
    }

    @Test fun sequenceMustIncrease() {
        val g = CommandGate()
        val s = g.startSession()
        assertTrue(g.accept(s, 5))
        assertFalse(g.accept(s, 5))
        assertFalse(g.accept(s, 4))
        assertTrue(g.accept(s, 6))
    }

    @Test fun endedSessionIsRejected() {
        val g = CommandGate()
        val s = g.startSession()
        g.endSession(s)
        assertFalse(g.accept(s, 1))
        assertFalse(g.isCurrent(s))
    }

    @Test fun endingAnOldSessionDoesNotAffectTheNewOne() {
        val g = CommandGate()
        val a = g.startSession()
        val b = g.startSession()
        g.endSession(a)
        assertTrue(g.accept(b, 1))
    }
}
