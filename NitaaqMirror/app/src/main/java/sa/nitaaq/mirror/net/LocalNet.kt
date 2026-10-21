package sa.nitaaq.mirror.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import sa.nitaaq.mirror.core.NetPick
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LocalNet {
    const val DEFAULT_PORT = 47820
    const val SERVICE_TYPE = "_nitaaqmirror._tcp."

    /** [hotspot] = this device is the access point (sharing while tethering), so clients reach [ipv4]. */
    data class Info(val ipv4: String?, val onWifi: Boolean, val hotspot: Boolean, val reachable: Boolean)

    private fun interfaceAddresses(): List<NetPick.Iface> {
        val out = ArrayList<NetPick.Iface>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        a.hostAddress?.let { out.add(NetPick.Iface(it, name)) }
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun info(ctx: Context): Info {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)!!
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        // The Wi-Fi/Ethernet client address (null when the host is the hotspot, i.e. active network is cellular).
        val wifiIp = if (onWifi) cm.getLinkProperties(net!!)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress else null
        val pick = NetPick.pick(interfaceAddresses(), wifiIp)
        // Reachable by another device on the same L2: we are a hotspot, a Wi-Fi client, or have a LAN address.
        val reachable = pick.ip != null && (pick.hotspot || onWifi)
        return Info(pick.ip, onWifi, pick.hotspot, reachable)
    }

    /** Multicast lock improves NSD/mDNS over a local link; harmless if unavailable. Best-effort. */
    fun acquireMulticast(ctx: Context): WifiManager.MulticastLock? = try {
        ctx.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("nitaaq")?.apply { setReferenceCounted(false); acquire() }
    } catch (_: Exception) { null }

    /** Host side: advertise on the LAN so a trusted controller can find us after an IP change. */
    class Advertiser(private val ctx: Context) {
        private var listener: NsdManager.RegistrationListener? = null
        private var multicast: WifiManager.MulticastLock? = null

        fun start(port: Int, name: String, hostId: String) {
            stop()
            multicast = acquireMulticast(ctx)
            val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "NITAAQ-" + hostId.take(12)
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("id", hostId.take(16))
                setAttribute("n", name.take(40))
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(s: NsdServiceInfo?, e: Int) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo?, e: Int) {}
                override fun onServiceRegistered(s: NsdServiceInfo?) {}
                override fun onServiceUnregistered(s: NsdServiceInfo?) {}
            }
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
                listener = l
            } catch (_: Exception) {}
        }

        fun stop() {
            try { multicast?.release() } catch (_: Exception) {}
            multicast = null
            val l = listener ?: return
            listener = null
            try { ctx.getSystemService(NsdManager::class.java)?.unregisterService(l) } catch (_: Exception) {}
        }
    }

    data class Found(val hostIdPrefix: String, val address: String, val port: Int, val name: String)

    /**
     * Controller side: browse the LAN for [timeoutMs] and return what answered.
     * Blocking; call from a background thread.
     */
    @Suppress("DEPRECATION")
    fun discover(ctx: Context, timeoutMs: Long, wantedIdPrefix: String? = null): List<Found> {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return emptyList()
        val multicast = acquireMulticast(ctx)
        val results = ConcurrentHashMap<String, Found>()
        val done = CountDownLatch(1)
        val resolveQueue = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceFound(s: NsdServiceInfo) { resolveQueue.offer(s) }
            override fun onServiceLost(s: NsdServiceInfo?) {}
        }
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) } catch (_: Exception) { return emptyList() }
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline && done.count > 0) {
                val s = resolveQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                // resolveService allows one resolve at a time: do them sequentially.
                val latch = CountDownLatch(1)
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo?, e: Int) { latch.countDown() }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val addr = si.host?.takeIf { it is Inet4Address }?.hostAddress
                        val id = si.attributes["id"]?.let { String(it, Charsets.UTF_8) } ?: ""
                        val name = si.attributes["n"]?.let { String(it, Charsets.UTF_8) } ?: si.serviceName
                        if (addr != null) results[id + addr] = Found(id, addr, si.port, name)
                        latch.countDown()
                    }
                })
                latch.await(1500, TimeUnit.MILLISECONDS)
                if (wantedIdPrefix != null && results.values.any { wantedIdPrefix.startsWith(it.hostIdPrefix) && it.hostIdPrefix.isNotEmpty() }) break
            }
        } finally {
            try { nsd.stopServiceDiscovery(discovery) } catch (_: Exception) {}
            try { multicast?.release() } catch (_: Exception) {}
        }
        return results.values.toList()
    }
}
