package sa.nitaaq.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sa.nitaaq.mirror.core.Fingerprint
import sa.nitaaq.mirror.core.PairInvite
import sa.nitaaq.mirror.core.PairingCrypto
import sa.nitaaq.mirror.core.SelfSignedCert
import sa.nitaaq.mirror.net.PinnedTrustManager
import sa.nitaaq.mirror.net.Tls
import sa.nitaaq.mirror.proto.ByeReason
import sa.nitaaq.mirror.proto.FrameIO
import sa.nitaaq.mirror.proto.Hello
import sa.nitaaq.mirror.proto.HelloMode
import sa.nitaaq.mirror.proto.Msg
import sa.nitaaq.mirror.proto.PairResult
import sa.nitaaq.mirror.proto.Pointer
import sa.nitaaq.mirror.proto.TouchAction
import sa.nitaaq.mirror.proto.TouchMsg
import sa.nitaaq.mirror.proto.payload
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class PairingCryptoTest {
    private val fpH = ByteArray(32) { 1 }
    private val fpC = ByteArray(32) { 2 }
    private val nC = ByteArray(16) { 3 }
    private val nH = ByteArray(16) { 4 }

    @Test fun proofsVerifyOnlyWithTheSameCode() {
        val p = PairingCrypto.proof('C', "12345678", fpH, fpC, nC, nH)
        assertTrue(PairingCrypto.verify(PairingCrypto.proof('C', "1234 5678", fpH, fpC, nC, nH), p))
        assertFalse(PairingCrypto.verify(PairingCrypto.proof('C', "12345679", fpH, fpC, nC, nH), p))
    }

    @Test fun proofsAreBoundToRoleCertificatesAndNonces() {
        val p = PairingCrypto.proof('C', "12345678", fpH, fpC, nC, nH)
        assertFalse(PairingCrypto.verify(PairingCrypto.proof('H', "12345678", fpH, fpC, nC, nH), p))
        assertFalse(PairingCrypto.verify(PairingCrypto.proof('C', "12345678", fpC, fpH, nC, nH), p))
        assertFalse(PairingCrypto.verify(PairingCrypto.proof('C', "12345678", fpH, fpC, nH, nC), p))
    }

    @Test fun verificationDigitsDifferWhenAMachineInTheMiddleSwapsCertificates() {
        val real = PairingCrypto.verificationDigits(fpH, fpC, nC, nH)
        val mitm = PairingCrypto.verificationDigits(ByteArray(32) { 9 }, fpC, nC, nH)
        assertEquals(6, real.length)
        assertNotEquals(real, mitm)
    }

    @Test fun codesAreEightDigitsAndArabicDigitsAreAccepted() {
        repeat(50) { assertTrue(PairingCrypto.newCode().matches(Regex("\\d{8}"))) }
        assertEquals("12345678", PairingCrypto.normalizeCode("١٢٣٤ ٥٦٧٨"))
    }

    @Test fun inviteRoundTripAndValidation() {
        val fp = Fingerprint.hex(ByteArray(32) { it.toByte() })
        val inv = PairInvite("192.168.1.20", 47820, fp, "00123456", "moto edge 60")
        assertEquals(inv, PairInvite.parse(inv.toUri()))
        assertNull(PairInvite.parse("https://example.com"))
        assertNull(PairInvite.parse(inv.toUri().replace("p=47820", "p=99999")))
        assertNull(PairInvite.parse(inv.toUri().replace("f=$fp", "f=abc")))
        assertNull(PairInvite.parse(inv.toUri().replace("c=00123456", "c=123")))
    }
}

/**
 * End-to-end on the JVM over real loopback sockets: our self-signed certificates in a mutually-authenticated
 * TLS 1.3 connection with fingerprint pinning, plus the pairing message exchange the app performs.
 * (On the phones the TLS provider is Conscrypt; this test uses the JDK provider.)
 */
class TlsPairingIntegrationTest {

    private class Id(name: String) {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(
            SelfSignedCert.create(keys, name, Date(System.currentTimeMillis() - 86_400_000), Date(System.currentTimeMillis() + 86_400_000L * 3650)).inputStream()
        ) as X509Certificate
        val fp: ByteArray = Fingerprint.of(cert.encoded)
    }

    @Test fun certificateIsValidX509() {
        val id = Id("NITAAQ Mirror")
        id.cert.checkValidity()
        id.cert.verify(id.keys.public)
        assertEquals(3, id.cert.version)
        assertEquals("SHA256withECDSA", id.cert.sigAlgName)
    }

    /** Minimal host: same rules as HostServer (trusted set, or pairing window + code + approval). */
    private inner class TestHost(val id: Id, var pairingCode: String?, val approve: Boolean) {
        val trusted = CopyOnWriteArraySet<String>()
        val server: SSLServerSocket
        val touches = java.util.concurrent.LinkedBlockingQueue<TouchMsg>()
        init {
            val tm = PinnedTrustManager { fp -> Fingerprint.hex(fp) in trusted || pairingCode != null }
            server = Tls.context(id.keys.private, id.cert, tm).serverSocketFactory.createServerSocket(0, 4, InetAddress.getLoopbackAddress()) as SSLServerSocket
            server.needClientAuth = true
            Thread { while (!server.isClosed) { val s = try { server.accept() as SSLSocket } catch (_: Exception) { break }; Thread { handle(s) }.start() } }.start()
        }

        private fun handle(s: SSLSocket) {
            try {
                s.startHandshake()
                val fpC = Fingerprint.of(s.session.peerCertificates[0].encoded)
                val input = DataInputStream(s.inputStream); val out = BufferedOutputStream(s.outputStream)
                val hello = Hello.decode(FrameIO.read(input).payload)
                if (hello.mode == HelloMode.PAIR) {
                    val code = pairingCode ?: run { FrameIO.write(out, Msg.PAIR_RESULT, byteArrayOf(PairResult.WINDOW_CLOSED.toByte())); out.flush(); s.close(); return }
                    val nH = PairingCrypto.nonce()
                    FrameIO.write(out, Msg.PAIR_NONCE, nH); out.flush()
                    val proof = FrameIO.read(input).payload
                    if (!PairingCrypto.verify(PairingCrypto.proof('C', code, id.fp, fpC, hello.nonce, nH), proof)) {
                        FrameIO.write(out, Msg.PAIR_RESULT, byteArrayOf(PairResult.WRONG_CODE.toByte())); out.flush(); s.close(); return
                    }
                    FrameIO.write(out, Msg.PAIR_PROOF, PairingCrypto.proof('H', code, id.fp, fpC, hello.nonce, nH)); out.flush()
                    if (!approve) { FrameIO.write(out, Msg.PAIR_RESULT, byteArrayOf(PairResult.REJECTED.toByte())); out.flush(); s.close(); return }
                    trusted.add(Fingerprint.hex(fpC)); pairingCode = null
                    FrameIO.write(out, Msg.PAIR_RESULT, byteArrayOf(PairResult.OK.toByte())); out.flush()
                } else if (Fingerprint.hex(fpC) !in trusted) {
                    FrameIO.write(out, Msg.BYE, byteArrayOf(ByeReason.NOT_TRUSTED.toByte())); out.flush(); s.close(); return
                }
                FrameIO.write(out, Msg.WELCOME, payload { writeUTF("host"); writeUTF(Fingerprint.hex(id.fp)); writeInt(1) }); out.flush()
                while (true) {
                    val f = FrameIO.read(input)
                    if (f.type == Msg.TOUCH) touches.add(TouchMsg.decode(f.payload))
                }
            } catch (_: Exception) {
                try { s.close() } catch (_: Exception) {}
            }
        }
    }

    /** Returns the first frame type the client sees after hello/pairing, or -1 if TLS failed. */
    private fun client(me: Id, host: TestHost, pinFp: ByteArray?, code: String?, sendTouch: Boolean = false): Int {
        val seen = AtomicReference<ByteArray>()
        val tm = PinnedTrustManager { fp -> seen.set(fp); pinFp == null || fp.contentEquals(pinFp) }
        val s = Tls.context(me.keys.private, me.cert, tm).socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), host.server.localPort), 2000)
            s.soTimeout = 5000
            s.startHandshake()
            val input = DataInputStream(s.inputStream); val out = BufferedOutputStream(s.outputStream)
            val nC = PairingCrypto.nonce()
            FrameIO.write(out, Msg.HELLO, Hello(1, "note", if (code != null) HelloMode.PAIR else HelloMode.TRUSTED, nC).encode()); out.flush()
            if (code != null) {
                val f = FrameIO.read(input)
                if (f.type != Msg.PAIR_NONCE) return f.type
                val fpH = Fingerprint.of(s.session.peerCertificates[0].encoded)
                FrameIO.write(out, Msg.PAIR_PROOF, PairingCrypto.proof('C', code, fpH, me.fp, nC, f.payload)); out.flush()
                val hp = FrameIO.read(input)
                if (hp.type != Msg.PAIR_PROOF) return hp.type
                assertTrue("host proved it knows the code", PairingCrypto.verify(PairingCrypto.proof('H', code, fpH, me.fp, nC, f.payload), hp.payload))
                val r = FrameIO.read(input)
                if (r.payload[0].toInt() != PairResult.OK) return Msg.PAIR_RESULT
            }
            val w = FrameIO.read(input)
            if (w.type == Msg.WELCOME && sendTouch) {
                FrameIO.write(out, Msg.TOUCH, TouchMsg(TouchAction.DOWN, 0, 1, 0, listOf(Pointer(0, 0.5f, 0.5f))).encode()); out.flush()
            }
            return w.type
        } catch (e: Exception) {
            return -1
        } finally {
            Thread.sleep(50)
            s.close()
        }
    }

    @Test fun fullPairingThenTrustedReconnect() {
        val host = TestHost(Id("moto"), "12345678", approve = true)
        val note = Id("note")
        assertEquals(Msg.WELCOME, client(note, host, host.id.fp, "12345678"))
        assertTrue(Fingerprint.hex(note.fp) in host.trusted)
        // Later: no code needed, pairing window closed.
        assertEquals(Msg.WELCOME, client(note, host, host.id.fp, null, sendTouch = true))
        val t = host.touches.poll(2, TimeUnit.SECONDS)
        assertEquals(TouchAction.DOWN, t?.action)
        host.server.close()
    }

    @Test fun wrongCodeIsRejected() {
        val host = TestHost(Id("moto"), "12345678", approve = true)
        assertEquals(Msg.PAIR_RESULT, client(Id("note"), host, host.id.fp, "87654321"))
        assertTrue(host.trusted.isEmpty())
        host.server.close()
    }

    @Test fun ownerDenyingTheRequestBlocksPairing() {
        val host = TestHost(Id("moto"), "12345678", approve = false)
        assertEquals(Msg.PAIR_RESULT, client(Id("note"), host, host.id.fp, "12345678"))
        assertTrue(host.trusted.isEmpty())
        host.server.close()
    }

    @Test fun unpairedDeviceCannotControlWhenPairingIsClosed() {
        val host = TestHost(Id("moto"), null, approve = true)
        val stranger = Id("stranger")
        val r = client(stranger, host, host.id.fp, null, sendTouch = true)
        assertTrue("TLS refused or BYE, never WELCOME (got $r)", r == -1 || r == Msg.BYE)
        assertNull("no command reached the host", host.touches.poll(300, TimeUnit.MILLISECONDS))
        host.server.close()
    }

    @Test fun controllerRefusesAHostWithAnotherCertificate() {
        val host = TestHost(Id("moto"), "12345678", approve = true)
        val impostorPin = Id("expected").fp
        assertEquals(-1, client(Id("note"), host, impostorPin, "12345678"))
        host.server.close()
    }

    @Test fun revokedControllerIsRefused() {
        val host = TestHost(Id("moto"), "12345678", approve = true)
        val note = Id("note")
        assertEquals(Msg.WELCOME, client(note, host, host.id.fp, "12345678"))
        host.trusted.clear()
        val r = client(note, host, host.id.fp, null, sendTouch = true)
        assertTrue(r == -1 || r == Msg.BYE)
        host.server.close()
    }

    @Test fun fingerprintHelpers() {
        val b = ByteArray(32) { (it * 7).toByte() }
        assertArrayEquals(b, Fingerprint.fromHex(Fingerprint.hex(b)))
        assertEquals(9, Fingerprint.short(b).length)
        assertNull(Fingerprint.fromHex("zz"))
        try { Fingerprint.fromHex("abc")?.let { fail() } } catch (_: Exception) {}
    }
}
