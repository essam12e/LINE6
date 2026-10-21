package sa.nitaaq.mirror.core

/**
 * Chooses which local IPv4 address the host should advertise, given the device's network interfaces.
 *
 * The hard case is a host that is *running a Wi-Fi hotspot while its data is cellular* (our setup: the Motorola
 * shares its screen while being the access point, the Note connects to it). There the "active network" is
 * cellular, so ConnectivityManager reports the mobile IP, not the hotspot. The real address the controller must
 * reach is the access-point interface (e.g. 192.168.x.1 on ap0 / swlan0 / wlan1), which only appears when the
 * network interfaces are enumerated directly.
 *
 * Pure (no Android types) so it is unit-tested on the JVM; [LocalNet] feeds it the real interface list.
 */
object NetPick {
    data class Iface(val ip: String, val name: String)
    data class Pick(val ip: String?, val hotspot: Boolean)

    // Interface names used for the access-point / tethering side across vendors.
    private val AP = Regex("^(ap\\d|swlan|softap|wlan1|rndis|usb|bt-pan|tether)")

    fun isApName(name: String): Boolean = AP.containsMatchIn(name.lowercase())

    private fun siteLocal(ip: String) = ip.startsWith("192.168.") || ip.startsWith("10.") ||
        ip.substringBefore('.').toIntOrNull()?.let { it == 172 } == true &&
        ip.split('.').getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true

    /**
     * @param ifaces  every non-loopback IPv4 the device currently has, with its interface name.
     * @param wifiIp  the address of the active Wi-Fi/Ethernet network, or null (host-as-hotspot, or offline).
     */
    fun pick(ifaces: List<Iface>, wifiIp: String?): Pick {
        // 1. A real access-point interface wins: that is where hotspot clients reach us.
        ifaces.firstOrNull { isApName(it.name) && it.ip != wifiIp }?.let { return Pick(it.ip, true) }
        // 2. Connected as a Wi-Fi client (e.g. the controller on the hotspot): use that address.
        if (wifiIp != null) return Pick(wifiIp, false)
        // 3. No active Wi-Fi and no named AP interface: prefer a 192.168.* gateway-looking address.
        val ranked = ifaces.filter { siteLocal(it.ip) }.sortedWith(
            compareByDescending<Iface> { it.ip.startsWith("192.168.") }
                .thenByDescending { it.ip.endsWith(".1") }
                .thenByDescending { !it.ip.startsWith("10.") },
        )
        val top = ranked.firstOrNull()?.ip ?: ifaces.firstOrNull()?.ip
        // A 192.168.x.1 with no Wi-Fi client network is almost certainly our own hotspot gateway.
        val hotspot = top != null && top.startsWith("192.168.") && top.endsWith(".1")
        return Pick(top, hotspot)
    }
}
