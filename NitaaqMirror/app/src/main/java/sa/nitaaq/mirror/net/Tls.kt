package sa.nitaaq.mirror.net

import sa.nitaaq.mirror.core.Fingerprint
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Accepts a peer certificate only if its SHA-256 fingerprint passes [accept]. No CA, no hostnames:
 * trust comes from pairing. The fingerprint actually seen is remembered in [lastSeen].
 */
class PinnedTrustManager(private val accept: (ByteArray) -> Boolean) : X509TrustManager {
    @Volatile var lastSeen: ByteArray? = null
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        if (chain == null || chain.isEmpty()) throw CertificateException("no certificate")
        val fp = Fingerprint.of(chain[0].encoded)
        lastSeen = fp
        if (!accept(fp)) throw CertificateException("untrusted peer")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object Tls {
    private const val PASS = "x" // in-memory KeyStore only; never written anywhere

    fun context(identity: Identity, trust: PinnedTrustManager): SSLContext =
        context(identity.privateKey, identity.certificate, trust)

    fun context(key: PrivateKey, cert: X509Certificate, trust: PinnedTrustManager): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry("me", key, PASS.toCharArray(), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASS.toCharArray())
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(trust), SecureRandom())
        return ctx
    }

    fun harden(s: SSLSocket) {
        val wanted = s.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (wanted.isNotEmpty()) s.enabledProtocols = wanted.toTypedArray()
    }

    fun peerFingerprint(s: SSLSocket): ByteArray? = try {
        (s.session.peerCertificates.firstOrNull() as? X509Certificate)?.let { Fingerprint.of(it.encoded) }
    } catch (_: Exception) { null }
}
