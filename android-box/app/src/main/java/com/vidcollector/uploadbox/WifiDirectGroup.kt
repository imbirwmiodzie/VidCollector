package com.vidcollector.uploadbox

import android.Manifest
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Owns the box's own Wi-Fi network: a Wi-Fi Direct group whose name and password the app
 * chooses (Android 10+). Other phones join it like any Wi-Fi network (legacy clients), and the
 * box is reachable at the group owner address (normally 192.168.49.1).
 *
 * - Asks for 5 GHz first, falls back to "any band" if the phone refuses.
 * - Self-healing: a watchdog re-reads the group every few seconds and re-creates it if the
 *   system dropped it (Wi-Fi toggled, regular hotspot started, driver reset, ...).
 * - Publishes state to [BoxState.group] / [BoxState.groupStatus] and calls [onChange].
 */
@TargetApi(Build.VERSION_CODES.Q)
class WifiDirectGroup(
    private val ctx: Context,
    backend: P2pBackend? = null,
    private val onChange: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val backend: P2pBackend = backend ?: SystemP2pBackend(ctx) {
        Log.w(TAG, "Wi-Fi Direct channel lost")
        if (wanted) handler.postDelayed({ refresh() }, 2000)
    }
    private var wanted = false
    private var creating = false
    private var useAutoBand = false
    private var failures = 0
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val st = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (st == WifiP2pManager.WIFI_P2P_STATE_DISABLED) publish(null, MSG_WIFI_OFF)
                    else if (st == WifiP2pManager.WIFI_P2P_STATE_ENABLED) handler.postDelayed({ refresh() }, 500)
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refresh()
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    fun start() {
        if (wanted) return
        wanted = true
        failures = 0
        useAutoBand = false
        if (!backend.isAvailable()) {
            publish(null, "This phone has no Wi-Fi Direct. Use \"Existing Wi-Fi or hotspot\" mode.")
            return
        }
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(receiver, f)
            receiverRegistered = true
        }
        publish(null, MSG_CREATING)
        recreate()
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun stop() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        try { backend.close() } catch (_: Exception) {}
        BoxState.group = null
        BoxState.groupStatus = null
    }

    /** Removes any old group (ours from an earlier run, or another app's) and creates ours. */
    private fun recreate() {
        if (!wanted || creating) return
        problem()?.let { publish(null, it); return }
        creating = true
        guarded {
            backend.removeGroup { handler.postDelayed({ create() }, 400) }
        }
    }

    private fun create() {
        if (!wanted) { creating = false; return }
        val cred = BoxPrefs.credentials(ctx)
        val band5 = !useAutoBand
        Log.i(TAG, "createGroup ${cred.ssid} band=${if (band5) "5GHz" else "auto"}")
        guarded {
            backend.createGroup(cred, band5) { ok, reason ->
                creating = false
                if (ok) {
                    failures = 0
                    handler.postDelayed({ refresh() }, 500)
                    handler.postDelayed({ refresh() }, 2000)
                    return@createGroup
                }
                failures++
                Log.w(TAG, "createGroup failed reason=$reason (${reasonText(reason)})")
                if (band5) {
                    useAutoBand = true // no 5 GHz group on this phone/country: let the system choose
                    handler.postDelayed({ recreate() }, 300)
                } else {
                    publish(null, "Could not create the Wi-Fi (${reasonText(reason)}). " +
                        "Turn Wi-Fi on and the regular hotspot off. Retrying…")
                    handler.postDelayed({ recreate() }, minOf(30_000L, 3_000L * failures))
                }
            }
        }
    }

    fun refresh() {
        if (!wanted) return
        problem()?.let { publish(null, it); return }
        guarded {
            backend.requestGroup { g ->
                if (!wanted) return@requestGroup
                if (g == null || !g.isOwner) {
                    if (!creating) {
                        if (failures == 0) publish(null, MSG_CREATING)
                        recreate()
                    }
                    return@requestGroup
                }
                val ip = IpPicker.pickForWifiDirect(IpPicker.fromSystem(), g.iface) ?: IpPicker.WIFI_DIRECT_DEFAULT_IP
                val info = GroupInfo(g.ssid, g.passphrase, g.iface, ip, g.clients, g.frequencyMhz)
                if (info != BoxState.group) Log.i(TAG, "group $info")
                publish(info, null)
            }
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            creating = false
            Log.w(TAG, "Wi-Fi Direct call failed", e)
            publish(null, "Wi-Fi Direct error: ${e.message}. Retrying…")
        }
    }

    /** A reason we cannot create the group right now, or null. */
    private fun problem(): String? {
        if (missingPermission(ctx) != null) {
            return "Permission needed: tap \"Allow notifications / nearby devices\" below, then Stop and Start."
        }
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        if (wm != null && !wm.isWifiEnabled) return MSG_WIFI_OFF
        if (Build.VERSION.SDK_INT < 33) {
            val lm = ctx.getSystemService(LocationManager::class.java)
            if (lm != null && !lm.isLocationEnabled) return "Turn Location on (Android 10–12 needs it for Wi-Fi Direct)."
        }
        return null
    }

    private fun publish(info: GroupInfo?, status: String?) {
        val changed = info != BoxState.group || status != BoxState.groupStatus
        BoxState.group = info
        BoxState.groupStatus = status
        if (changed) onChange()
    }

    companion object {
        private const val TAG = "UploadBox"
        const val WATCHDOG_MS = 4_000L
        const val MSG_CREATING = "Creating Wi-Fi…"
        const val MSG_WIFI_OFF = "Wi-Fi is off. Turn Wi-Fi on (no need to connect to a network)."

        /** Test hook: replaces the system backend the service uses. */
        @Volatile var backendFactoryForTests: ((Context) -> P2pBackend)? = null

        /** The runtime permission Wi-Fi Direct needs on this Android version, if not yet granted. */
        fun missingPermission(ctx: Context): String? {
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
            return if (ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) null else perm
        }

        fun reasonText(reason: Int) = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported"
            WifiP2pManager.BUSY -> "busy"
            WifiP2pManager.ERROR -> "error"
            else -> "code $reason"
        }
    }
}
