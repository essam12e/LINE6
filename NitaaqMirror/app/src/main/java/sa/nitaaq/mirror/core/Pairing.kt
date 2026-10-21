package sa.nitaaq.mirror.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing handshake (runs inside TLS, after both sides presented their self-signed certificates):
 *
 *   C -> H  HELLO(mode=PAIR, nonceC)
 *   H -> C  PAIR_NONCE(nonceH)                      (only while the host's pairing screen is open)
 *   C -> H  PAIR_PROOF(HMAC(k, "C"|fpH|fpC|nonceC|nonceH))
 *   H       checks the proof (max 5 wrong attempts per code, then the code is replaced)
 *   H -> C  PAIR_PROOF(HMAC(k, "H"|...))            proves the host knows the code too
 *   both    show the same 6 verification digits; the host owner must press "Allow"
 *   H -> C  PAIR_RESULT(OK) and both store the other's certificate fingerprint as trusted.
 *
 * k = SHA-256("NITAAQ-PAIR-v1" | code). With the QR code, the controller also pins the host certificate
 * fingerprint from the QR, so a machine in the middle is impossible. With manual entry, comparing the
 * 6 digits on both screens detects one.
 */
object PairingCrypto {
    private val rnd = SecureRandom()

    fun newCode(): String {
        // 8 digits; leading zeros allowed.
        val n = rnd.nextInt(100_000_000)
        return n.toString().padStart(8, '0')
    }

    fun normalizeCode(input: String): String = input.filter { it.isDigit() || it in '٠'..'٩' }
        .map { if (it in '٠'..'٩') ('0' + (it - '٠')) else it }.joinToString("")

    fun nonce(): ByteArray = ByteArray(16).also { rnd.nextBytes(it) }

    private fun key(code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(("NITAAQ-PAIR-v1|" + normalizeCode(code)).toByteArray(Charsets.UTF_8))

    fun proof(role: Char, code: String, fpHost: ByteArray, fpCtrl: ByteArray, nonceC: ByteArray, nonceH: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key(code), "HmacSHA256"))
        mac.update(role.code.toByte())
        mac.update(fpHost); mac.update(fpCtrl); mac.update(nonceC); mac.update(nonceH)
        return mac.doFinal()
    }

    fun verify(expected: ByteArray, got: ByteArray): Boolean = MessageDigest.isEqual(expected, got)

    /** 6 digits both screens show; derived only from public values, so they match iff both saw the same certs. */
    fun verificationDigits(fpHost: ByteArray, fpCtrl: ByteArray, nonceC: ByteArray, nonceH: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("NITAAQ-VERIFY-v1".toByteArray()); md.update(fpHost); md.update(fpCtrl); md.update(nonceC); md.update(nonceH)
        val d = md.digest()
        val v = ((d[0].toInt() and 0x7f) shl 24) or ((d[1].toInt() and 0xff) shl 16) or ((d[2].toInt() and 0xff) shl 8) or (d[3].toInt() and 0xff)
        return (v % 1_000_000).toString().padStart(6, '0')
    }
}

object Fingerprint {
    fun of(der: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(der)
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun fromHex(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    /** Short, human-readable form, e.g. "3F2A-91C0". */
    fun short(b: ByteArray): String = hex(b).take(8).uppercase().chunked(4).joinToString("-")
}

/** Contents of the pairing QR code. */
data class PairInvite(
    val host: String,
    val port: Int,
    val fingerprintHex: String,
    val code: String,
    val name: String,
) {
    fun toUri(): String = "nitaaq-mirror://pair?v=1" +
        "&h=" + enc(host) + "&p=" + port + "&f=" + fingerprintHex + "&c=" + code + "&n=" + enc(name)

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun parse(text: String): PairInvite? {
            val prefix = "nitaaq-mirror://pair?"
            if (!text.startsWith(prefix)) return null
            val params = HashMap<String, String>()
            for (kv in text.removePrefix(prefix).split('&')) {
                val i = kv.indexOf('=')
                if (i <= 0) continue
                params[kv.substring(0, i)] = try { URLDecoder.decode(kv.substring(i + 1), "UTF-8") } catch (_: Exception) { return null }
            }
            if (params["v"] != "1") return null
            val host = params["h"]?.takeIf { it.isNotBlank() && it.length < 64 } ?: return null
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fp = params["f"]?.lowercase()?.takeIf { it.length == 64 && Fingerprint.fromHex(it) != null } ?: return null
            val code = params["c"]?.let { PairingCrypto.normalizeCode(it) }?.takeIf { it.length == 8 } ?: return null
            return PairInvite(host, port, fp, code, (params["n"] ?: "").take(64))
        }
    }
}
