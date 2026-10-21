package sa.nitaaq.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sa.nitaaq.mirror.core.NetPick
import sa.nitaaq.mirror.core.NetPick.Iface

class NetPickTest {

    @Test fun hostRunningHotspotOnCellularAdvertisesTheApAddress() {
        // Motorola as hotspot: active network is cellular (rmnet 10.x), the AP interface carries 192.168.x.1.
        val ifaces = listOf(Iface("10.123.45.67", "rmnet_data0"), Iface("192.168.43.1", "ap0"))
        val p = NetPick.pick(ifaces, wifiIp = null)
        assertEquals("192.168.43.1", p.ip)
        assertTrue("must be flagged as hotspot so the UI says so", p.hotspot)
    }

    @Test fun apSubnetIsNotAssumedFixed() {
        // Newer Android randomises the hotspot subnet; whatever the AP interface reports is used.
        val p = NetPick.pick(listOf(Iface("10.8.0.2", "rmnet0"), Iface("192.168.137.1", "swlan0")), null)
        assertEquals("192.168.137.1", p.ip)
        assertTrue(p.hotspot)
    }

    @Test fun vendorApInterfaceNames() {
        for (name in listOf("ap0", "swlan0", "softap0", "wlan1")) {
            val p = NetPick.pick(listOf(Iface("192.168.1.1", name), Iface("10.0.0.2", "rmnet0")), null)
            assertEquals(name, "192.168.1.1", p.ip)
            assertTrue(name, p.hotspot)
        }
    }

    @Test fun controllerOnHotspotUsesItsWifiClientAddress() {
        // Note connected to the hotspot: ConnectivityManager reports the Wi-Fi client IP.
        val p = NetPick.pick(listOf(Iface("192.168.43.37", "wlan0")), wifiIp = "192.168.43.37")
        assertEquals("192.168.43.37", p.ip)
        assertFalse(p.hotspot)
    }

    @Test fun ordinaryWifiClientHost() {
        val p = NetPick.pick(listOf(Iface("192.168.1.50", "wlan0")), wifiIp = "192.168.1.50")
        assertEquals("192.168.1.50", p.ip)
        assertFalse(p.hotspot)
    }

    @Test fun apInterfaceWinsEvenWhenAlsoOnWifi() {
        // If the host is both a Wi-Fi client and an AP, clients reach us on the AP side.
        val p = NetPick.pick(listOf(Iface("192.168.1.50", "wlan0"), Iface("192.168.43.1", "ap0")), wifiIp = "192.168.1.50")
        assertEquals("192.168.43.1", p.ip)
        assertTrue(p.hotspot)
    }

    @Test fun gatewayLookingAddressWithoutWifiIsTreatedAsHotspot() {
        // No named AP interface and no active Wi-Fi, but a 192.168.x.1 exists -> our own hotspot gateway.
        val p = NetPick.pick(listOf(Iface("192.168.200.1", "wlan0"), Iface("10.1.2.3", "rmnet0")), null)
        assertEquals("192.168.200.1", p.ip)
        assertTrue(p.hotspot)
    }

    @Test fun offlineHasNoAddress() {
        assertNull(NetPick.pick(emptyList(), null).ip)
    }

    @Test fun cellularOnlyIsNotAdvertisedAsHotspot() {
        // Only a cellular address, no AP, no Wi-Fi: usable as a last resort but not called a hotspot.
        val p = NetPick.pick(listOf(Iface("10.123.45.67", "rmnet_data0")), null)
        assertEquals("10.123.45.67", p.ip)
        assertFalse(p.hotspot)
    }

    @Test fun apNameDetection() {
        assertTrue(NetPick.isApName("ap0"))
        assertTrue(NetPick.isApName("SWLAN0"))
        assertTrue(NetPick.isApName("wlan1"))
        assertFalse(NetPick.isApName("wlan0"))
        assertFalse(NetPick.isApName("rmnet_data0"))
    }
}
