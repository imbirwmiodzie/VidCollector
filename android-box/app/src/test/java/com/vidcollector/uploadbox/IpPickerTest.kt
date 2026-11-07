package com.vidcollector.uploadbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpPickerTest {

    private fun a(iface: String, ip: String) = IfaceAddr(iface, ip)

    @Test fun privateRanges() {
        assertTrue(IpPicker.isPrivateIpv4("10.0.0.5"))
        assertTrue(IpPicker.isPrivateIpv4("172.16.0.1"))
        assertTrue(IpPicker.isPrivateIpv4("172.31.255.254"))
        assertTrue(IpPicker.isPrivateIpv4("192.168.43.1"))
        assertFalse(IpPicker.isPrivateIpv4("172.15.0.1"))
        assertFalse(IpPicker.isPrivateIpv4("172.32.0.1"))
        assertFalse(IpPicker.isPrivateIpv4("100.64.1.2"))   // CGNAT (cellular)
        assertFalse(IpPicker.isPrivateIpv4("169.254.1.1"))  // link-local
        assertFalse(IpPicker.isPrivateIpv4("127.0.0.1"))
        assertFalse(IpPicker.isPrivateIpv4("8.8.8.8"))
        assertFalse(IpPicker.isPrivateIpv4("192.168.1"))
        assertFalse(IpPicker.isPrivateIpv4("192.168.1.256"))
        assertFalse(IpPicker.isPrivateIpv4("fe80::1"))
        assertFalse(IpPicker.isPrivateIpv4(""))
    }

    @Test fun samsungHotspotWinsOverCellular() {
        val picked = IpPicker.pick(listOf(
            a("rmnet_data0", "10.123.45.67"), // cellular, private-looking but must be skipped
            a("lo", "127.0.0.1"),
            a("swlan0", "192.168.97.183"),
        ))
        assertEquals(a("swlan0", "192.168.97.183"), picked)
    }

    @Test fun hotspotPreferredOverWifiClient() {
        // Samsung "Wi-Fi sharing": connected to a router and running a hotspot at once.
        val picked = IpPicker.pick(listOf(a("wlan0", "192.168.1.20"), a("swlan0", "192.168.215.94")))
        assertEquals("swlan0", picked!!.iface)
        val picked2 = IpPicker.pick(listOf(a("wlan0", "192.168.1.20"), a("ap0", "10.27.180.1")))
        assertEquals("ap0", picked2!!.iface)
    }

    @Test fun routerWifiOnly() {
        assertEquals(a("wlan0", "192.168.0.14"),
            IpPicker.pick(listOf(a("rmnet16", "100.70.1.2"), a("wlan0", "192.168.0.14"), a("dummy0", "192.168.0.99"))))
    }

    @Test fun pixelStyleHotspotOnWlan1() {
        assertEquals(a("wlan1", "192.168.50.1"), IpPicker.pick(listOf(a("rmnet_data1", "10.0.0.2"), a("wlan1", "192.168.50.1"))))
    }

    @Test fun otherLanUsedOnlyAsFallback() {
        assertEquals("wlan0", IpPicker.pick(listOf(a("rndis0", "192.168.42.129"), a("wlan0", "10.1.1.4")))!!.iface)
        assertEquals("rndis0", IpPicker.pick(listOf(a("rndis0", "192.168.42.129"), a("tun0", "10.8.0.2")))!!.iface)
    }

    @Test fun vpnAndCellularExcluded() {
        assertNull(IpPicker.pick(listOf(a("tun0", "10.8.0.2"), a("rmnet0", "10.1.2.3"), a("ccmni1", "10.9.9.9"),
            a("v4-rmnet_data2", "192.0.0.4"), a("ppp0", "10.64.64.64"), a("lo", "127.0.0.1"))))
    }

    @Test fun publicAndLinkLocalIgnored() {
        assertNull(IpPicker.pick(listOf(a("wlan0", "169.254.10.10"), a("wlan0", "203.0.113.5"))))
        assertNull(IpPicker.pick(emptyList()))
    }

    @Test fun candidatesOrderedAndDeduplicated() {
        val c = IpPicker.candidates(listOf(
            a("wlan0", "192.168.1.20"), a("eth0", "10.0.0.9"), a("swlan0", "192.168.43.1"), a("wlan0", "192.168.1.20"),
        ))
        assertEquals(listOf(a("swlan0", "192.168.43.1"), a("wlan0", "192.168.1.20"), a("eth0", "10.0.0.9")), c)
    }

    @Test fun rankIsCaseInsensitive() {
        assertEquals(IpPicker.RANK_HOTSPOT, IpPicker.rankOf("SWLAN0"))
        assertEquals(IpPicker.RANK_WLAN, IpPicker.rankOf("wlan2"))
        assertNull(IpPicker.rankOf("lo"))
    }

    @Test fun urlFormat() {
        assertEquals("http://192.168.43.1:8080/", IpPicker.url("192.168.43.1", 8080))
    }

    @Test fun fromSystemDoesNotThrow() {
        IpPicker.fromSystem() // whatever the host has; must not crash
    }

    @Test fun wifiDirectAddressPrefersGroupInterface() {
        val addrs = listOf(a("wlan0", "192.168.1.20"), a("p2p-wlan0-0", "192.168.49.1"), a("rmnet0", "10.0.0.1"))
        assertEquals("192.168.49.1", IpPicker.pickForWifiDirect(addrs, "p2p-wlan0-0"))
        // group interface unknown: the standard Wi-Fi Direct subnet wins
        assertEquals("192.168.49.1", IpPicker.pickForWifiDirect(addrs, null))
        // a vendor using another subnet on the group interface
        assertEquals("192.168.137.1", IpPicker.pickForWifiDirect(
            listOf(a("wlan0", "192.168.1.20"), a("p2p0", "192.168.137.1")), "p2p0"))
        assertEquals("192.168.137.1", IpPicker.pickForWifiDirect(
            listOf(a("wlan0", "192.168.1.20"), a("p2p0", "192.168.137.1")), null))
        assertNull(IpPicker.pickForWifiDirect(listOf(a("wlan0", "192.168.1.20")), "p2p-wlan0-0"))
    }
}
