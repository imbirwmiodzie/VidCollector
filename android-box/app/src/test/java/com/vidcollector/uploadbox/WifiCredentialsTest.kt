package com.vidcollector.uploadbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class WifiCredentialsTest {

    @Test fun generatedCredentialsAreValidForAndroidAndWpa2() {
        val rnd = SecureRandom()
        val seen = HashSet<String>()
        repeat(500) {
            val c = WifiCredentials.generate(rnd)
            assertTrue(c.ssid, WifiCredentials.isValidSsid(c.ssid))
            assertTrue(c.ssid.startsWith("DIRECT-UB-Box-"))
            assertEquals(18, c.ssid.length)
            assertTrue(c.passphrase, WifiCredentials.isValidPassphrase(c.passphrase))
            assertEquals(10, c.passphrase.length)
            assertFalse("ambiguous chars", c.passphrase.any { it in "01oil" })
            seen.add(c.ssid + c.passphrase)
        }
        assertTrue(seen.size > 495)
    }

    @Test fun ssidRules() {
        assertTrue(WifiCredentials.isValidSsid("DIRECT-UB-Box-AB12"))
        assertTrue(WifiCredentials.isValidSsid("DIRECT-a9"))
        assertFalse(WifiCredentials.isValidSsid("UploadBox"))
        assertFalse(WifiCredentials.isValidSsid("DIRECT--x"))
        assertFalse(WifiCredentials.isValidSsid("DIRECT-UB-" + "x".repeat(30))) // > 32 bytes
    }

    @Test fun passphraseRules() {
        assertFalse(WifiCredentials.isValidPassphrase("short"))
        assertTrue(WifiCredentials.isValidPassphrase("12345678"))
        assertFalse(WifiCredentials.isValidPassphrase("x".repeat(64)))
        assertFalse(WifiCredentials.isValidPassphrase("zażółć1234"))
    }

    @Test fun qrPayloadFormat() {
        assertEquals("WIFI:T:WPA;S:DIRECT-UB-Box-AB12;P:k7wq3mzr9p;;",
            WifiCredentials("DIRECT-UB-Box-AB12", "k7wq3mzr9p").qrPayload())
    }

    @Test fun qrPayloadEscapesSpecialCharacters() {
        assertEquals("WIFI:T:WPA;S:DIRECT-xy\\;a\\,b;P:p\\:a\\\\s\\\"s;;",
            WifiCredentials("DIRECT-xy;a,b", "p:a\\s\"s").qrPayload())
    }
}
