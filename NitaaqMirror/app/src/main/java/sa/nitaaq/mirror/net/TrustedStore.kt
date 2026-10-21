package sa.nitaaq.mirror.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** A device we paired with. [role] = "host" (stored on the controller) or "controller" (stored on the host). */
data class TrustedPeer(
    val fingerprintHex: String,
    val name: String,
    val role: String,
    val lastAddress: String = "",
    val lastPort: Int = 0,
    val hostId: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = 0,
)

/** Trusted devices, encrypted at rest with the Keystore key (see [KeystoreBox]). */
class TrustedStore private constructor(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("trusted", Context.MODE_PRIVATE)
    private val peers = CopyOnWriteArrayList<TrustedPeer>()

    init { load() }

    private fun load() {
        val sealed = sp.getString("data", null) ?: return
        try {
            val arr = JSONArray(String(KeystoreBox.open(sealed), Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                peers.add(
                    TrustedPeer(
                        o.getString("fp"), o.optString("name"), o.getString("role"),
                        o.optString("addr"), o.optInt("port"), o.optString("hid"), o.optLong("added"), o.optLong("seen"),
                    )
                )
            }
        } catch (_: Exception) {
            peers.clear()
        }
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        for (p in peers) {
            arr.put(
                JSONObject().put("fp", p.fingerprintHex).put("name", p.name).put("role", p.role)
                    .put("addr", p.lastAddress).put("port", p.lastPort).put("hid", p.hostId)
                    .put("added", p.addedAt).put("seen", p.lastSeen)
            )
        }
        sp.edit().putString("data", KeystoreBox.seal(arr.toString().toByteArray(Charsets.UTF_8))).apply()
    }

    fun list(role: String): List<TrustedPeer> = peers.filter { it.role == role }.sortedByDescending { maxOf(it.lastSeen, it.addedAt) }

    fun find(role: String, fpHex: String): TrustedPeer? = peers.firstOrNull { it.role == role && it.fingerprintHex == fpHex }

    fun isTrusted(role: String, fpHex: String) = find(role, fpHex) != null

    @Synchronized
    fun put(peer: TrustedPeer) {
        peers.removeAll { it.role == peer.role && it.fingerprintHex == peer.fingerprintHex }
        peers.add(peer)
        save()
    }

    @Synchronized
    fun touch(role: String, fpHex: String, address: String? = null, port: Int? = null) {
        val p = find(role, fpHex) ?: return
        put(p.copy(lastSeen = System.currentTimeMillis(), lastAddress = address ?: p.lastAddress, lastPort = port ?: p.lastPort))
    }

    @Synchronized
    fun remove(role: String, fpHex: String) {
        peers.removeAll { it.role == role && it.fingerprintHex == fpHex }
        save()
    }

    @Synchronized
    fun clearAll() {
        peers.clear()
        sp.edit().remove("data").apply()
    }

    companion object {
        const val ROLE_HOST = "host"
        const val ROLE_CONTROLLER = "controller"

        @Volatile private var inst: TrustedStore? = null
        fun get(ctx: Context): TrustedStore = inst ?: synchronized(this) {
            inst ?: TrustedStore(ctx.applicationContext).also { inst = it }
        }
    }
}
