package sa.nitaaq.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sa.nitaaq.mirror.core.AdaptiveBitrate
import sa.nitaaq.mirror.core.ConnectionStateMachine
import sa.nitaaq.mirror.core.FailReason
import sa.nitaaq.mirror.core.LinkState
import sa.nitaaq.mirror.core.Quality
import sa.nitaaq.mirror.core.QualityPolicy
import sa.nitaaq.mirror.core.RollingStats
import sa.nitaaq.mirror.proto.ByeReason
import sa.nitaaq.mirror.proto.FrameIO
import sa.nitaaq.mirror.proto.Hello
import sa.nitaaq.mirror.proto.HelloMode
import sa.nitaaq.mirror.proto.Msg
import sa.nitaaq.mirror.proto.Pointer
import sa.nitaaq.mirror.proto.StatusCodec
import sa.nitaaq.mirror.proto.TouchAction
import sa.nitaaq.mirror.proto.TouchMsg
import sa.nitaaq.mirror.proto.VideoConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

class ConnectionStateMachineTest {

    @Test fun normalLifecycle() {
        val sm = ConnectionStateMachine()
        val id = sm.connectRequested()
        assertEquals(LinkState.Connecting, sm.state)
        sm.connected(id)
        assertEquals(LinkState.Connected, sm.state)
        sm.userDisconnected()
        assertEquals(LinkState.Idle, sm.state)
        assertFalse(sm.wantsConnection)
    }

    @Test fun dropAfterConnectRetriesWithBackoffAndNewIds() {
        val sm = ConnectionStateMachine()
        val id = sm.connectRequested(); sm.connected(id)
        val d1 = sm.connectionLost(id, 0, everConnected = true)
        assertEquals(250L, d1)
        assertTrue(sm.state is LinkState.Reconnecting)
        val id2 = sm.connectionId
        assertTrue(id2 != id)
        assertEquals(500L, sm.connectionLost(id2, 300, true))
        sm.connected(sm.connectionId)
        assertEquals(LinkState.Connected, sm.state)
    }

    @Test fun eventsFromOldConnectionsAreIgnored() {
        val sm = ConnectionStateMachine()
        val old = sm.connectRequested(); sm.connected(old)
        sm.connectionLost(old, 0, true)
        val cur = sm.connectionId
        assertNull("second report from the same dead socket", sm.connectionLost(old, 10, true))
        sm.hostSaidBye(old, ByeReason.HOST_STOPPED)
        assertTrue(sm.state is LinkState.Reconnecting)
        sm.connected(cur)
        assertEquals(LinkState.Connected, sm.state)
    }

    @Test fun userDisconnectStopsRetries() {
        val sm = ConnectionStateMachine()
        val id = sm.connectRequested(); sm.connected(id)
        sm.userDisconnected()
        assertNull(sm.connectionLost(id, 0, true))
        assertEquals(LinkState.Idle, sm.state)
    }

    @Test fun neverReachableHostFailsAsNetworkBlocked() {
        val sm = ConnectionStateMachine(maxRetryWindowMs = 80_000)
        var id = sm.connectRequested()
        var now = 0L
        var delay = sm.connectionLost(id, now, everConnected = false)
        while (delay != null) {
            now += delay + 3000
            id = sm.connectionId
            delay = sm.connectionLost(id, now, everConnected = false)
        }
        assertEquals(LinkState.Failed(FailReason.NETWORK_BLOCKED), sm.state)
        assertTrue("gives up quickly when it never connected", now <= 20_000)
    }

    @Test fun hostRefusingTrustIsTerminal() {
        val sm = ConnectionStateMachine()
        val id = sm.connectRequested(); sm.connected(id)
        sm.hostSaidBye(id, ByeReason.NOT_TRUSTED)
        assertEquals(LinkState.Failed(FailReason.REJECTED), sm.state)
        assertNull(sm.connectionLost(id, 0, true))
    }

    @Test fun projectionStopAndResume() {
        val sm = ConnectionStateMachine()
        val id = sm.connectRequested(); sm.connected(id)
        sm.projectionStopped(id)
        assertEquals(LinkState.HostNeedsApproval, sm.state)
        sm.projectionRunning(id)
        assertEquals(LinkState.Connected, sm.state)
    }

    @Test fun pairingDigitsShownOnlyForCurrentConnection() {
        val sm = ConnectionStateMachine()
        val id = sm.connectRequested()
        sm.pairing(id + 5, "123456")
        assertEquals(LinkState.Connecting, sm.state)
        sm.pairing(id, "123456")
        assertEquals(LinkState.Pairing("123456"), sm.state)
    }
}

class ProtocolTest {

    @Test fun touchRoundTrip() {
        val m = TouchMsg(TouchAction.MOVE, 1, 42, 123456789L, listOf(Pointer(0, 0.25f, 0.75f), Pointer(1, 1f, 0f)))
        assertEquals(m, TouchMsg.decode(m.encode()))
    }

    @Test fun touchDecodingClampsAndDropsGarbage() {
        val bad = TouchMsg(TouchAction.DOWN, 0, 1, 0, listOf(Pointer(0, 7f, -3f), Pointer(1, Float.NaN, 0.5f)))
        val d = TouchMsg.decode(bad.encode())
        assertEquals(1, d.pointers.size)
        assertEquals(Pointer(0, 1f, 0f), d.pointers[0])
    }

    @Test fun framesRoundTripAndOversizeIsRejected() {
        val bos = ByteArrayOutputStream()
        FrameIO.write(bos, Msg.PING, byteArrayOf(1, 2, 3))
        FrameIO.write(bos, Msg.NAV, byteArrayOf(9))
        val input = DataInputStream(bos.toByteArray().inputStream())
        val a = FrameIO.read(input); val b = FrameIO.read(input)
        assertEquals(Msg.PING, a.type); assertArrayEquals(byteArrayOf(1, 2, 3), a.payload)
        assertEquals(Msg.NAV, b.type)

        val evil = byteArrayOf(Msg.TOUCH.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        try { FrameIO.read(DataInputStream(evil.inputStream())); fail() } catch (_: IOException) {}
    }

    @Test fun helloAndConfigAndStatus() {
        val h = Hello(1, "Galaxy Note10+", HelloMode.PAIR, ByteArray(16) { it.toByte() })
        val h2 = Hello.decode(h.encode())
        assertEquals("Galaxy Note10+", h2.deviceName); assertEquals(HelloMode.PAIR, h2.mode); assertArrayEquals(h.nonce, h2.nonce)

        val c = VideoConfig(720, 1600, 1220, 2712, 0, "c2.qti.avc.encoder", true)
        val c2 = VideoConfig.decode(c.encode())
        assertEquals(1600, c2.height); assertEquals("c2.qti.avc.encoder", c2.encoderName); assertTrue(c2.hardware)

        val s = mapOf("proj" to "1", "disp" to "on", "fps" to "30")
        assertEquals(s, StatusCodec.decode(StatusCodec.encode(s)))
    }
}

class QualityTest {

    @Test fun encodeSizeKeepsAspectAndAlignment() {
        val (w, h) = QualityPolicy.encodeSize(1220, 2712, 720)
        assertEquals(0, w % 16); assertEquals(0, h % 16)
        assertEquals(720.0, w.toDouble(), 16.0)
        assertEquals(1220.0 / 2712, w.toDouble() / h, 0.01)
    }

    @Test fun neverUpscales() {
        val (w, h) = QualityPolicy.encodeSize(720, 1520, 1080)
        assertEquals(720, w); assertEquals(1520, h)
    }

    @Test fun landscapeUsesShortSide() {
        val (w, h) = QualityPolicy.encodeSize(2712, 1220, 720)
        assertEquals(720.0, h.toDouble(), 16.0)
        assertTrue(w > h)
    }

    @Test fun presets() {
        val fast = QualityPolicy.params(Quality.FAST, 30, 1220, 2712)
        val bal = QualityPolicy.params(Quality.BALANCED, 30, 1220, 2712)
        assertTrue(bal.width > fast.width)
        assertTrue(bal.bitrate > fast.bitrate)
        assertEquals(30, fast.fps)
        val auto540 = QualityPolicy.params(Quality.AUTO, 30, 1220, 2712, autoStep = 1)
        assertTrue(auto540.width < fast.width)
    }

    @Test fun adaptiveBitrateCutsFastAndRecoversSlowly() {
        val a = AdaptiveBitrate(5_000_000)
        val cut = a.onSecond(queueDelayMs = 150.0, droppedFrames = 3)!!
        assertEquals(3_500_000, cut)
        assertNull(a.onSecond(5.0, 0)); assertNull(a.onSecond(5.0, 0)); assertNull(a.onSecond(5.0, 0))
        val up = a.onSecond(5.0, 0)!!
        assertTrue(up > cut)
    }

    @Test fun adaptiveBitrateTracksTimeAtFloor() {
        val a = AdaptiveBitrate(1_200_000)
        repeat(5) { a.onSecond(300.0, 10) }
        assertEquals(QualityPolicy.MIN_BITRATE, a.bitrate)
        assertTrue(a.congestedAtFloor >= 3)
    }

    @Test fun rollingStats() {
        val r = RollingStats(10)
        for (i in 1..20) r.add(i.toDouble())
        assertEquals(10, r.size())
        assertEquals(15.5, r.average(), 0.001)
        assertEquals(20.0, r.max(), 0.0)
        assertNotNull(r.percentile(95.0))
    }
}
