package sa.nitaaq.mirror.core

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal DER encoder for a self-signed X.509 v3 certificate (ECDSA P-256 / SHA-256).
 * The certificate is never validated against a CA: peers trust each other only by its SHA-256 fingerprint,
 * which is exchanged during pairing. Avoids pulling a crypto library into the app.
 */
object SelfSignedCert {

    fun create(keys: KeyPair, commonName: String, notBefore: Date, notAfter: Date): ByteArray {
        val sigAlg = seq(oid("1.2.840.10045.4.3.2")) // ecdsa-with-SHA256
        val name = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val serial = BigInteger(64, SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xA0, int(BigInteger.valueOf(2))), // [0] EXPLICIT version v3
            int(serial),
            sigAlg,
            name,
            seq(time(notBefore), time(notAfter)),
            name,
            keys.public.encoded, // SubjectPublicKeyInfo, already DER
        )
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.private)
        signer.update(tbs)
        val sig = signer.sign()
        return seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + sig))
    }

    private fun time(d: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = utc }.format(d).toInt()
        return if (year < 2050) {
            tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(d).toByteArray())
        } else {
            tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(d).toByteArray())
        }
    }

    private fun int(v: BigInteger) = tlv(0x02, v.toByteArray())

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = p
            stack.add((v and 0x7f).toInt())
            v = v shr 7
            while (v > 0) { stack.add(((v and 0x7f) or 0x80).toInt()); v = v shr 7 }
            for (b in stack.reversed()) out.write(b)
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun seq(vararg items: ByteArray) = tlv(0x30, concat(items))
    private fun set(vararg items: ByteArray) = tlv(0x31, concat(items))

    private fun concat(items: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in items) out.write(i)
        return out.toByteArray()
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = value.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xff) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xff); out.write(len and 0xff) }
        }
        out.write(value)
        return out.toByteArray()
    }
}
