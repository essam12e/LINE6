package sa.nitaaq.mirror.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import sa.nitaaq.mirror.core.Fingerprint
import sa.nitaaq.mirror.core.SelfSignedCert
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps/unwraps small secrets with a non-exportable AES-256-GCM key that lives in the Android Keystore.
 */
object KeystoreBox {
    private const val ALIAS = "nitaaq_mirror_box_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain)
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun open(sealed: String): ByteArray {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return c.doFinal(all, 12, all.size - 12)
    }
}

/**
 * This device's TLS identity: an ECDSA P-256 key + self-signed certificate. Generated once; the private key is
 * stored only in sealed form (see [KeystoreBox]). Peers recognise each other by the certificate's SHA-256.
 */
class Identity private constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: ByteArray = Fingerprint.of(certificate.encoded)
    val fingerprintHex: String = Fingerprint.hex(fingerprint)

    companion object {
        @Volatile private var cached: Identity? = null

        fun get(ctx: Context): Identity = cached ?: synchronized(this) {
            cached ?: load(ctx).also { cached = it }
        }

        private fun load(ctx: Context): Identity {
            val sp = ctx.applicationContext.getSharedPreferences("identity", Context.MODE_PRIVATE)
            val sealedKey = sp.getString("key", null)
            val certB64 = sp.getString("cert", null)
            if (sealedKey != null && certB64 != null) {
                try {
                    val pk = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(KeystoreBox.open(sealedKey)))
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(Base64.decode(certB64, Base64.NO_WRAP).inputStream()) as X509Certificate
                    return Identity(pk, cert)
                } catch (_: Exception) {
                    // Keystore key lost (e.g. data restored to another phone): start a fresh identity.
                    TrustedStore.get(ctx).clearAll()
                }
            }
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            val now = System.currentTimeMillis()
            val der = SelfSignedCert.create(kp, "NITAAQ Mirror", Date(now - 86_400_000L), Date(now + 25L * 365 * 86_400_000L))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
            sp.edit()
                .putString("key", KeystoreBox.seal(kp.private.encoded))
                .putString("cert", Base64.encodeToString(der, Base64.NO_WRAP))
                .apply()
            return Identity(kp.private, cert)
        }
    }
}
