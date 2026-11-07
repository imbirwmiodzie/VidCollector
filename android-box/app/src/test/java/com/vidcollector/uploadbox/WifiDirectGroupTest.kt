package com.vidcollector.uploadbox

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiDirectGroupTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val fake = FakeP2pBackend()
    private var changes = 0
    private lateinit var group: WifiDirectGroup

    @Before fun setUp() {
        IpPicker.overrideForTests = { emptyList() } // no real p2p interface here → default 192.168.49.1
        group = WifiDirectGroup(app, fake) { changes++ }
    }

    @After fun tearDown() {
        group.stop()
        IpPicker.overrideForTests = null
        BoxState.group = null
        BoxState.groupStatus = null
    }

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
    private fun idle(seconds: Long) = ShadowLooper.idleMainLooper(seconds, TimeUnit.SECONDS)

    @Test fun createsGroupWithStoredCredentialsOn5GHz() {
        grant()
        group.start()
        idle(3)
        val g = BoxState.group
        assertNotNull(BoxState.groupStatus, g)
        val cred = BoxPrefs.credentials(app)
        assertEquals(cred.ssid, g!!.ssid)
        assertEquals(cred.passphrase, g.passphrase)
        assertEquals("192.168.49.1", g.ip)
        assertEquals("5 GHz", g.band)
        assertEquals(listOf("remove", "create:5GHz"), fake.calls.filter { it != "request" })
        assertNull(BoxState.groupStatus)
        assertTrue(changes > 0)
    }

    @Test fun fallsBackToAutoBandWhen5GHzRefused() {
        grant()
        fake.fail5GHz = true
        group.start()
        idle(3)
        assertEquals(listOf("create:5GHz", "create:auto"), fake.calls.filter { it.startsWith("create") })
        assertEquals("2.4 GHz", BoxState.group!!.band)
    }

    @Test fun watchdogRecreatesDroppedGroupAndCountsClients() {
        grant()
        group.start()
        idle(3)
        assertEquals(1, fake.creates())
        fake.group = null // e.g. the user toggled Wi-Fi
        idle(3 * WifiDirectGroup.WATCHDOG_MS / 1000 + 2)
        assertEquals(2, fake.creates())
        assertTrue(BoxLog.snapshot().any { it.contains("Wi-Fi disappeared") })
        assertNotNull(BoxState.group)

        fake.clients = 2
        idle(WifiDirectGroup.WATCHDOG_MS / 1000 + 1)
        assertEquals(2, BoxState.group!!.clients)
    }

    @Test fun singleEmptyAnswerDoesNotKickGuests() {
        grant()
        group.start()
        idle(3)
        fake.clients = 1
        idle(WifiDirectGroup.WATCHDOG_MS / 1000 + 1)
        assertEquals(1, BoxState.group!!.clients)
        val removesBefore = fake.calls.count { it == "remove" }

        fake.emptyAnswers = 1 // the system briefly reports "no group" while a guest joins
        group.refresh()
        idle(WifiDirectGroup.WATCHDOG_MS / 1000 + 1)
        assertEquals("no re-create", 1, fake.creates())
        assertEquals("group never removed", removesBefore, fake.calls.count { it == "remove" })
        assertNotNull(BoxState.group)
        assertEquals(1, BoxState.group!!.clients)
    }

    @Test fun guestJoinAndLeaveAreLogged() {
        grant()
        BoxLog.clear()
        group.start()
        idle(3)
        fake.clients = 2
        idle(WifiDirectGroup.WATCHDOG_MS / 1000 + 1)
        fake.clients = 1
        idle(WifiDirectGroup.WATCHDOG_MS / 1000 + 1)
        val log = BoxLog.snapshot().joinToString("\n")
        assertTrue(log, log.contains("Wi-Fi up: DIRECT-UB-Box-"))
        assertTrue(log, log.contains("5 GHz (5180 MHz), box at 192.168.49.1"))
        assertTrue(log, log.contains("Guest joined (…ee01), now 2"))
        assertTrue(log, log.contains("Guest joined (…ee02), now 2"))
        assertTrue(log, log.contains("Guest left (…ee02), now 1"))
    }

    @Test fun persistentFailureIsReportedAndRetried() {
        grant()
        fake.failAll = true
        group.start()
        idle(2)
        assertNull(BoxState.group)
        assertTrue(BoxState.groupStatus!!, BoxState.groupStatus!!.startsWith("Could not create the Wi-Fi"))
        val before = fake.creates()
        idle(40)
        assertTrue("keeps retrying", fake.creates() > before)
        fake.failAll = false
        idle(40)
        assertNotNull(BoxState.group)
    }

    @Test fun missingPermissionIsExplainedAndNothingIsCreated() {
        group.start()
        idle(10)
        assertEquals(0, fake.creates())
        assertTrue(BoxState.groupStatus!!, BoxState.groupStatus!!.startsWith("Permission needed"))
        grant() // user allows it → the watchdog picks it up without a restart
        idle(WifiDirectGroup.WATCHDOG_MS / 1000 + 2)
        assertNotNull(BoxState.group)
    }

    @Test fun noWifiDirectHardware() {
        grant()
        fake.available = false
        group.start()
        idle(5)
        assertEquals(0, fake.creates())
        assertTrue(BoxState.groupStatus!!.contains("no Wi-Fi Direct"))
    }

    @Test fun stopRemovesGroupAndClearsState() {
        grant()
        group.start()
        idle(3)
        group.stop()
        assertTrue(fake.calls.last() == "close")
        assertNull(BoxState.group)
        val n = fake.calls.size
        idle(20)
        assertEquals("no activity after stop", n, fake.calls.size)
    }
}
