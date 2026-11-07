package com.vidcollector.uploadbox

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.TimeUnit
import java.io.File

/**
 * Starts the real foreground service under Robolectric (Android 15 / API 35 framework),
 * then talks to its HTTP server over a real socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadBoxServiceTest {

    private var controller: ServiceController<UploadBoxService>? = null

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After fun tearDown() {
        controller?.destroy()
        UploadBoxService.port = UploadServer.DEFAULT_PORT
        WifiDirectGroup.backendFactoryForTests = null
        IpPicker.overrideForTests = null
    }

    private fun startService(): ServiceController<UploadBoxService> =
        Robolectric.buildService(UploadBoxService::class.java,
            Intent(app, UploadBoxService::class.java).setAction(UploadBoxService.ACTION_START))
            .create().startCommand(0, 1).also { controller = it }

    @Test fun ownWifiModeCreatesGroupAndServesOnGroupAddress() {
        UploadBoxService.port = 0
        BoxPrefs.setMode(app, BoxMode.OWN_WIFI)
        shadowOf(app).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        IpPicker.overrideForTests = { emptyList() }
        val fake = FakeP2pBackend()
        WifiDirectGroup.backendFactoryForTests = { fake }

        val c = startService()
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        assertEquals(BoxMode.OWN_WIFI, BoxState.mode)
        val g = BoxState.group
        assertNotNull(BoxState.groupStatus, g)
        assertEquals(BoxPrefs.credentials(app).ssid, g!!.ssid)

        val health = HttpTestClient.get(BoxState.boundPort, "/health")
        assertTrue(health.body, health.body.contains("\"ip\":\"192.168.49.1\""))

        // Notification switches from "Creating Wi-Fi…" to the network name + URL.
        ShadowLooper.idleMainLooper(6, TimeUnit.SECONDS)
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val text = shadowOf(shadowOf(nm).getNotification(UploadBoxService.NOTIFICATION_ID)).contentText.toString()
        assertTrue(text, text.contains(g.ssid) && text.contains("http://192.168.49.1:"))

        c.withIntent(Intent(app, UploadBoxService::class.java).setAction(UploadBoxService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(fake.calls.contains("close"))
        assertNull(BoxState.group)
        assertFalse(BoxState.running)
    }

    @Test fun serviceServesHealthIndexAndA50MbUpload() {
        UploadBoxService.port = 0 // ephemeral port so the test never collides with a real 8080
        BoxPrefs.setMode(app, BoxMode.SAME_NETWORK)
        val c = startService()
        val service = c.get()

        // Foreground with the right notification, locks held.
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals("Upload box running", shadowOf(notification).contentTitle)
        assertTrue(BoxState.running)
        val wl: PowerManager.WakeLock? = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(wl)
        assertTrue(wl!!.isHeld)

        val port = BoxState.boundPort
        assertTrue(port > 0)

        val health = HttpTestClient.get(port, "/health")
        assertEquals(200, health.code)
        assertTrue(health.body, health.body.contains("\"ok\":true"))
        assertTrue(health.body, health.body.contains("\"files\":0"))

        val index = HttpTestClient.get(port, "/")
        assertEquals(200, index.code)
        assertTrue("index.html comes from app assets", index.body.contains("Upload box works"))
        assertTrue(index.body.contains("<input id=\"picker\" type=\"file\" multiple"))

        val size = 50L * 1024 * 1024 + 7
        val (reply, sentSha) = HttpTestClient.uploadRandom(port, "test-video.mp4", size, seed = 2024)
        assertEquals(reply.body, 200, reply.code)
        val stored = File(UploadBoxService.storageDir(app), "test-video.mp4")
        assertEquals(size, stored.length())
        assertEquals(sentSha, HttpTestClient.sha256(stored))
        assertTrue(stored.absolutePath.contains("external", ignoreCase = true) ||
            stored.parentFile!!.parentFile == app.getExternalFilesDir(null))

        val files = HttpTestClient.get(port, "/files").body
        assertTrue(files, files.contains("\"name\":\"test-video.mp4\",\"size\":$size"))

        // Stop action shuts everything down and frees the locks.
        c.withIntent(Intent(app, UploadBoxService::class.java).setAction(UploadBoxService.ACTION_STOP))
            .startCommand(0, 2)
        assertFalse(BoxState.running)
        assertFalse(wl.isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
