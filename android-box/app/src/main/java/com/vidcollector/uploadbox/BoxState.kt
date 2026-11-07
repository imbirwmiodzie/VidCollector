package com.vidcollector.uploadbox

/** Live state of the box's own Wi-Fi Direct network, as reported by the system. */
data class GroupInfo(
    val ssid: String,
    val passphrase: String,
    val iface: String?,
    val ip: String?,
    val clients: Int,
    val frequencyMhz: Int = 0,
) {
    val credentials get() = WifiCredentials(ssid, passphrase)
    val band: String get() = when {
        frequencyMhz >= 5900 -> "6 GHz"
        frequencyMhz >= 4900 -> "5 GHz"
        frequencyMhz > 0 -> "2.4 GHz"
        else -> ""
    }
}

/** Process-wide state shared by the service, the activity and the HTTP server. */
object BoxState {
    @Volatile var running: Boolean = false
    @Volatile var lastError: String? = null
    @Volatile var lastEvent: String? = null
    /** Port the server actually listens on (0 when stopped). */
    @Volatile var boundPort: Int = 0
    /** Mode the running service was started in. */
    @Volatile var mode: BoxMode = BoxMode.SAME_NETWORK
    /** Wi-Fi Direct network (null until it is up, or in SAME_NETWORK mode). */
    @Volatile var group: GroupInfo? = null
    /** Human-readable Wi-Fi Direct status / problem ("Creating Wi-Fi…", "Turn Wi-Fi on", …). */
    @Volatile var groupStatus: String? = null
}

enum class BoxMode {
    /** The box creates its own Wi-Fi (Wi-Fi Direct group). Guests scan a Wi-Fi QR code. */
    OWN_WIFI,
    /** Everyone is already on the same router Wi-Fi or on the phone's regular hotspot. */
    SAME_NETWORK,
}
