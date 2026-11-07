package com.vidcollector.uploadbox

import java.net.Inet4Address
import java.net.NetworkInterface

/** One IPv4 address on one network interface, e.g. ("swlan0", "192.168.43.1"). */
data class IfaceAddr(val iface: String, val address: String)

/**
 * Chooses the address other phones on the same Wi-Fi should use to reach this phone.
 *
 * The logic is pure (works on plain strings) so it can be unit-tested on the JVM;
 * [fromSystem] is the thin adapter over [NetworkInterface].
 */
object IpPicker {

    /** Lower rank = better. */
    const val RANK_HOTSPOT = 0   // ap*, swlan* (Samsung hotspot = swlan0)
    const val RANK_WLAN = 1      // wlan* (Wi-Fi client; some devices also run the hotspot on wlan1/wlan2)
    const val RANK_OTHER_LAN = 2 // eth*, usb/rndis (USB tethering), p2p*, bt-pan, ...

    private val excludedPrefixes = listOf(
        "lo", "rmnet", "ccmni", "v4-", "clat", "tun", "ppp", "ipsec", "dummy", "ip6tnl",
        "sit", "gre", "erspan", "ifb", "seth", "radio", "umts", "r_rmnet", "epdg"
    )

    /** Null means "never use this interface" (loopback, cellular, VPN, virtual). */
    fun rankOf(iface: String): Int? {
        val n = iface.lowercase()
        if (excludedPrefixes.any { n.startsWith(it) }) return null
        return when {
            n.startsWith("ap") || n.startsWith("swlan") -> RANK_HOTSPOT
            n.startsWith("wlan") -> RANK_WLAN
            else -> RANK_OTHER_LAN
        }
    }

    /** Parses a dotted-quad IPv4 string, or returns null. */
    fun parseIpv4(address: String): IntArray? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    /** RFC 1918 private ranges: 10/8, 172.16/12, 192.168/16. */
    fun isPrivateIpv4(address: String): Boolean {
        val o = parseIpv4(address) ?: return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168)
    }

    /** All usable addresses, best first. */
    fun candidates(addrs: List<IfaceAddr>): List<IfaceAddr> =
        addrs.asSequence()
            .filter { isPrivateIpv4(it.address) }
            .mapNotNull { a -> rankOf(a.iface)?.let { r -> r to a } }
            .distinct()
            .sortedWith(compareBy<Pair<Int, IfaceAddr>>({ it.first }, { it.second.iface }, { it.second.address }))
            .map { it.second }
            .toList()

    fun pick(addrs: List<IfaceAddr>): IfaceAddr? = candidates(addrs).firstOrNull()

    /** Wi-Fi Direct group owner address used by Android (wpa_supplicant/IpServer default). */
    const val WIFI_DIRECT_DEFAULT_IP = "192.168.49.1"

    /**
     * Address of the box on its own Wi-Fi Direct network: the private IPv4 on the group's
     * interface (e.g. "p2p-wlan0-0"), else any address in 192.168.49.0/24, else null.
     */
    fun pickForWifiDirect(addrs: List<IfaceAddr>, groupIface: String?): String? {
        val priv = addrs.filter { isPrivateIpv4(it.address) }
        groupIface?.let { gi -> priv.firstOrNull { it.iface == gi }?.let { return it.address } }
        priv.firstOrNull { it.address.startsWith("192.168.49.") }?.let { return it.address }
        return priv.firstOrNull { it.iface.startsWith("p2p") }?.address
    }

    /** Test hook: replaces the live interface list (Robolectric has no real Wi-Fi). */
    @Volatile var overrideForTests: (() -> List<IfaceAddr>)? = null

    /** Reads the live interface list. Never throws. */
    fun fromSystem(): List<IfaceAddr> = overrideForTests?.invoke() ?: try {
        val result = ArrayList<IfaceAddr>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (ni in ifaces) {
            try {
                if (!ni.isUp || ni.isLoopback) continue
            } catch (_: Exception) {
                continue
            }
            for (ia in ni.inetAddresses) {
                if (ia is Inet4Address) {
                    ia.hostAddress?.let { result.add(IfaceAddr(ni.name, it)) }
                }
            }
        }
        result
    } catch (_: Exception) {
        emptyList()
    }

    fun url(ip: String, port: Int) = "http://$ip:$port/"
}
