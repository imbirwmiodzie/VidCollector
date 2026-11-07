package com.vidcollector.uploadbox

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.io.File

/**
 * Starts the real foreground service under Robolectric (Android 15 / API 35 framework),
 * then talks to its HTTP server over a real socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadBoxServiceTest {

    private var controller: ServiceController<UploadBoxService>? = null

    @After fun tearDown() {
        controller?.destroy()
        UploadBoxService.port = UploadServer.DEFAULT_PORT
    }

    @Test fun serviceServesHealthIndexAndA50MbUpload() {
        UploadBoxService.port = 0 // ephemeral port so the test never collides with a real 8080
        val app = ApplicationProvider.getApplicationContext<Application>()
        val c = Robolectric.buildService(UploadBoxService::class.java,
            Intent(app, UploadBoxService::class.java).setAction(UploadBoxService.ACTION_START))
            .create().startCommand(0, 1)
        controller = c
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
