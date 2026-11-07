package com.vidcollector.uploadbox

import java.security.SecureRandom

/** Network name + password of the box's own Wi-Fi Direct network. Pure logic, JVM-testable. */
data class WifiCredentials(val ssid: String, val passphrase: String) {

    /** Standard Wi-Fi QR payload; phone cameras (Android 10+, iOS 11+) offer "Join network". */
    fun qrPayload(): String = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(passphrase)};;"

    companion object {
        const val SSID_PREFIX = "DIRECT-UB-Box-"
        // No 0/o/1/l/i: the password is also shown as text and may be typed by hand.
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        /** Android only accepts Wi-Fi Direct names "DIRECT-xy..." (x, y alphanumeric), max 32 bytes. */
        private val SSID_RULE = Regex("^DIRECT-[A-Za-z0-9]{2}.*")

        fun isValidSsid(s: String) = SSID_RULE.matches(s) && s.toByteArray(Charsets.UTF_8).size <= 32

        /** WPA2-PSK passphrase: 8..63 printable ASCII characters. */
        fun isValidPassphrase(p: String) = p.length in 8..63 && p.all { it.code in 32..126 }

        fun generate(rnd: SecureRandom = SecureRandom()): WifiCredentials {
            fun pick(n: Int) = String(CharArray(n) { ALPHABET[rnd.nextInt(ALPHABET.length)] })
            return WifiCredentials(SSID_PREFIX + pick(4).uppercase(), pick(10))
        }

        /** Escapes \ ; , : " as required by the WIFI: QR format. */
        fun escape(s: String): String = buildString {
            for (c in s) {
                if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
                append(c)
            }
        }
    }
}
