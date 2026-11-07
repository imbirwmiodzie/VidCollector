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

    @After fun tearDown() {
        IpPicker.overrideForTests = null
    }

    private fun decodeQr(bmp: Bitmap): String {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val src = RGBLuminanceSource(bmp.width, bmp.height, px)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src))).text
    }

    @Test fun showsUrlAndMatchingQrAndFollowsAddressChanges() {
        IpPicker.overrideForTests = { listOf(IfaceAddr("rmnet_data0", "10.20.30.40"), IfaceAddr("swlan0", "192.168.97.183")) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        val url = activity.findViewById<TextView>(R.id.url).text.toString()
        assertEquals("http://192.168.97.183:8080/", url)
        val qr = activity.findViewById<ImageView>(R.id.qr)
        assertEquals(url, decodeQr((qr.drawable as BitmapDrawable).bitmap))
        assertTrue(activity.findViewById<TextView>(R.id.other_addresses).text.contains("swlan0"))

        // Optional screenshot for humans (build/outputs/main-screen.png).
        try {
            val root = activity.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bmp))
            val out = File("build/outputs/main-screen.png")
            out.parentFile!!.mkdirs()
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Throwable) {}

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
