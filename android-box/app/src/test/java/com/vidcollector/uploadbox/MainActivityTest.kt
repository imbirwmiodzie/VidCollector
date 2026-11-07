package com.vidcollector.uploadbox

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {

    private val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()

    @After fun tearDown() {
        IpPicker.overrideForTests = null
        BoxState.running = false
        BoxState.group = null
        BoxState.groupStatus = null
        BoxState.mode = BoxMode.SAME_NETWORK
    }

    private fun screenshot(activity: android.app.Activity, name: String) {
        try {
            val root = activity.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bmp))
            val out = File("build/outputs/$name.png")
            out.parentFile!!.mkdirs()
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Throwable) {}
    }

    @Test fun ownWifiShowsJoinAndPageQrCodesAndHighlightsWhenAPhoneJoins() {
        BoxPrefs.setMode(app, BoxMode.OWN_WIFI)
        BoxState.mode = BoxMode.OWN_WIFI
        BoxState.running = true
        BoxState.group = GroupInfo("DIRECT-UB-Box-K7QP", "pass;word:1", "p2p-wlan0-0", "192.168.49.1", 0, 5180)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        ShadowLooper.idleMainLooper()

        assertEquals(android.view.View.VISIBLE, a.findViewById<android.view.View>(R.id.own_panel).visibility)
        assertEquals(android.view.View.GONE, a.findViewById<android.view.View>(R.id.same_panel).visibility)
        val wifiQr = (a.findViewById<ImageView>(R.id.qr_wifi).drawable as BitmapDrawable).bitmap
        assertEquals("WIFI:T:WPA;S:DIRECT-UB-Box-K7QP;P:pass\\;word\\:1;;", decodeQr(wifiQr))
        val urlQr = (a.findViewById<ImageView>(R.id.qr_url).drawable as BitmapDrawable).bitmap
        assertEquals("http://192.168.49.1:8080/", decodeQr(urlQr))
        assertTrue(a.findViewById<TextView>(R.id.wifi_text).text.contains("Password: pass;word:1"))
        assertEquals("Phones connected: 0 · 5 GHz", a.findViewById<TextView>(R.id.clients).text.toString())
        val hint = a.findViewById<TextView>(R.id.joined_hint)
        assertEquals(android.view.View.GONE, hint.visibility)

        // A guest joins → "now scan 2" highlight.
        BoxState.group = BoxState.group!!.copy(clients = 1)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertEquals(android.view.View.VISIBLE, hint.visibility)
        assertEquals("Phones connected: 1 · 5 GHz", a.findViewById<TextView>(R.id.clients).text.toString())
        screenshot(a, "main-screen-own-wifi")
        // ...and it fades after 30 s.
        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS)
        assertEquals(android.view.View.GONE, hint.visibility)

        // Group still being created: status line shown.
        BoxState.group = null
        BoxState.groupStatus = "Wi-Fi is off. Turn Wi-Fi on (no need to connect to a network)."
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        val st = a.findViewById<TextView>(R.id.group_status)
        assertEquals(android.view.View.VISIBLE, st.visibility)
        assertTrue(st.text.startsWith("Wi-Fi is off"))
        controller.pause().stop().destroy()
    }

    private fun decodeQr(bmp: Bitmap): String {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val src = RGBLuminanceSource(bmp.width, bmp.height, px)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src))).text
    }

    @Test fun showsUrlAndMatchingQrAndFollowsAddressChanges() {
        BoxPrefs.setMode(app, BoxMode.SAME_NETWORK)
        IpPicker.overrideForTests = { listOf(IfaceAddr("rmnet_data0", "10.20.30.40"), IfaceAddr("swlan0", "192.168.97.183")) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val url = activity.findViewById<TextView>(R.id.url).text.toString()
        assertEquals("http://192.168.97.183:8080/", url)
        val qr = activity.findViewById<ImageView>(R.id.qr)
        assertEquals(url, decodeQr((qr.drawable as BitmapDrawable).bitmap))
        assertTrue(activity.findViewById<TextView>(R.id.other_addresses).text.contains("swlan0"))

        screenshot(activity, "main-screen-same-network")

        // Hotspot restarted with a new subnet (Android 11+): URL and QR follow within one refresh.
        IpPicker.overrideForTests = { listOf(IfaceAddr("swlan0", "192.168.214.7")) }
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        val url2 = activity.findViewById<TextView>(R.id.url).text.toString()
        assertEquals("http://192.168.214.7:8080/", url2)
        assertEquals(url2, decodeQr((qr.drawable as BitmapDrawable).bitmap))

        // No usable network: explanatory text, no QR.
        IpPicker.overrideForTests = { listOf(IfaceAddr("rmnet_data0", "10.20.30.40")) }
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        assertTrue(activity.findViewById<TextView>(R.id.url).text.startsWith("No Wi-Fi"))
        assertEquals(android.view.View.GONE, qr.visibility)
        controller.pause().stop().destroy()
    }
}
