package com.vidcollector.uploadbox

import android.content.Context
import android.os.Build

/** Persistent settings: mode and the Wi-Fi Direct name/password (stable, so QR codes can be printed). */
object BoxPrefs {
    private const val FILE = "box"
    private const val KEY_MODE = "mode"
    private const val KEY_SSID = "wd_ssid"
    private const val KEY_PASS = "wd_pass"

    val ownWifiSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun mode(ctx: Context): BoxMode {
        val saved = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        val m = saved?.let { runCatching { BoxMode.valueOf(it) }.getOrNull() }
            ?: if (ownWifiSupported) BoxMode.OWN_WIFI else BoxMode.SAME_NETWORK
        return if (m == BoxMode.OWN_WIFI && !ownWifiSupported) BoxMode.SAME_NETWORK else m
    }

    fun setMode(ctx: Context, mode: BoxMode) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun credentials(ctx: Context): WifiCredentials {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val ssid = p.getString(KEY_SSID, null)
        val pass = p.getString(KEY_PASS, null)
        if (ssid != null && pass != null && WifiCredentials.isValidSsid(ssid) && WifiCredentials.isValidPassphrase(pass)) {
            return WifiCredentials(ssid, pass)
        }
        return newCredentials(ctx)
    }

    fun newCredentials(ctx: Context): WifiCredentials {
        val c = WifiCredentials.generate()
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_SSID, c.ssid).putString(KEY_PASS, c.passphrase).apply()
        return c
    }
}
