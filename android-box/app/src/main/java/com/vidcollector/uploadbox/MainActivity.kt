package com.vidcollector.uploadbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var modeOwn: RadioButton
    private lateinit var modeSame: RadioButton
    private lateinit var startStop: Button
    private lateinit var groupStatus: TextView

    // OWN_WIFI panel
    private lateinit var ownPanel: View
    private lateinit var qrWifi: ImageView
    private lateinit var wifiText: TextView
    private lateinit var step2: View
    private lateinit var qrUrl: ImageView
    private lateinit var urlSmall: TextView
    private lateinit var clientsText: TextView
    private lateinit var joinedHint: TextView
    private lateinit var newPassword: Button

    // SAME_NETWORK panel
    private lateinit var samePanel: View
    private lateinit var urlText: TextView
    private lateinit var qr: ImageView
    private lateinit var otherAddrs: TextView

    private lateinit var event: TextView
    private lateinit var files: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var notifButton: Button
    private lateinit var boxLog: TextView
    private var shownLogVersion = -1

    private val handler = Handler(Looper.getMainLooper())
    private var shownUrl: String? = "\u0000" // force first render
    private var shownWifiQr: String? = null
    private var shownUrlQr: String? = null
    private var lastClients = -1
    private var highlightUntil = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var startAfterPermissions = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    /** Hotspot on/off (hidden system broadcast, still delivered to runtime receivers). */
    private val apReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The interface gets its address a moment after the state change.
            handler.postDelayed({ refresh() }, 1000)
            handler.postDelayed({ refresh() }, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        modeGroup = findViewById(R.id.mode_group)
        modeOwn = findViewById(R.id.mode_own)
        modeSame = findViewById(R.id.mode_same)
        startStop = findViewById(R.id.start_stop)
        groupStatus = findViewById(R.id.group_status)
        ownPanel = findViewById(R.id.own_panel)
        qrWifi = findViewById(R.id.qr_wifi)
        wifiText = findViewById(R.id.wifi_text)
        step2 = findViewById(R.id.step2)
        qrUrl = findViewById(R.id.qr_url)
        urlSmall = findViewById(R.id.url_small)
        clientsText = findViewById(R.id.clients)
        joinedHint = findViewById(R.id.joined_hint)
        newPassword = findViewById(R.id.new_password)
        samePanel = findViewById(R.id.same_panel)
        urlText = findViewById(R.id.url)
        qr = findViewById(R.id.qr)
        otherAddrs = findViewById(R.id.other_addresses)
        event = findViewById(R.id.last_event)
        files = findViewById(R.id.files)
        batteryStatus = findViewById(R.id.battery_status)
        notifButton = findViewById(R.id.notifications)
        boxLog = findViewById(R.id.box_log)

        if (!BoxPrefs.ownWifiSupported) {
            modeOwn.isEnabled = false
            modeOwn.text = getString(R.string.mode_own_unsupported)
        }
        (if (BoxPrefs.mode(this) == BoxMode.OWN_WIFI) modeOwn else modeSame).isChecked = true
        modeGroup.setOnCheckedChangeListener { _, id ->
            BoxPrefs.setMode(this, if (id == R.id.mode_own) BoxMode.OWN_WIFI else BoxMode.SAME_NETWORK)
            shownUrl = "\u0000"; shownWifiQr = null; shownUrlQr = null
            refresh()
        }

        startStop.setOnClickListener {
            if (BoxState.running) UploadBoxService.stop(this) else onStartPressed()
            handler.postDelayed({ refresh() }, 300)
        }
        newPassword.setOnClickListener {
            BoxPrefs.newCredentials(this)
            shownWifiQr = null
            refresh()
        }
        findViewById<Button>(R.id.battery).setOnClickListener { openBatteryExemption() }
        findViewById<Button>(R.id.app_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        notifButton.setOnClickListener { requestPermissionsOrSettings() }

        UploadBoxService.createChannel(this)
        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        registerNetworkWatchers()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkWatchers()
        super.onPause()
    }

    private fun onStartPressed() {
        BoxState.lastError = null
        if (BoxPrefs.mode(this) == BoxMode.OWN_WIFI && WifiDirectGroup.missingPermission(this) != null) {
            // Ask first; start either way when the dialog closes (the status line explains a denial).
            startAfterPermissions = true
            requestRuntimePermissions()
            return
        }
        UploadBoxService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (startAfterPermissions) {
            startAfterPermissions = false
            UploadBoxService.start(this)
        }
        refresh()
    }

    private fun refresh() {
        val running = BoxState.running
        val mode = if (running) BoxState.mode else BoxPrefs.mode(this)
        status.text = when {
            running -> getString(R.string.status_running)
            BoxState.lastError != null -> BoxState.lastError
            else -> getString(R.string.status_stopped)
        }
        startStop.text = getString(if (running) R.string.stop else R.string.start)
        for (i in 0 until modeGroup.childCount) {
            modeGroup.getChildAt(i).isEnabled = !running && (modeGroup.getChildAt(i) != modeOwn || BoxPrefs.ownWifiSupported)
        }

        if (mode == BoxMode.OWN_WIFI) refreshOwnWifi(running) else refreshSameNetwork(running)
        ownPanel.visibility = if (mode == BoxMode.OWN_WIFI) View.VISIBLE else View.GONE
        samePanel.visibility = if (mode == BoxMode.OWN_WIFI) View.GONE else View.VISIBLE

        event.text = BoxState.lastEvent ?: ""
        if (BoxLog.version != shownLogVersion) {
            shownLogVersion = BoxLog.version
            val lines = BoxLog.snapshot()
            boxLog.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
        }

        val list = UploadBoxService.storageDir(this).listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        files.text = if (list.isEmpty()) getString(R.string.no_files) else
            list.joinToString("\n") { "${it.name}  —  ${UploadBoxService.formatBytes(it.length())}" }

        val pm = getSystemService(PowerManager::class.java)
        batteryStatus.text = getString(
            if (pm.isIgnoringBatteryOptimizations(packageName)) R.string.battery_ok else R.string.battery_not_ok)

        notifButton.visibility = if (missingPermissions().isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshOwnWifi(running: Boolean) {
        val g = if (running) BoxState.group else null
        val cred = g?.credentials ?: BoxPrefs.credentials(this)
        val ip = g?.ip ?: IpPicker.WIFI_DIRECT_DEFAULT_IP
        val url = IpPicker.url(ip, UploadBoxService.port)
        val live = g != null

        val wifiPayload = cred.qrPayload()
        if (wifiPayload != shownWifiQr) {
            shownWifiQr = wifiPayload
            qrWifi.setImageBitmap(QrCode.bitmap(wifiPayload, 400))
        }
        if (url != shownUrlQr) {
            shownUrlQr = url
            qrUrl.setImageBitmap(QrCode.bitmap(url, 400))
        }
        wifiText.text = getString(R.string.wifi_credentials, cred.ssid, cred.passphrase)
        urlSmall.text = url
        val alpha = if (live) 1f else 0.25f
        qrWifi.alpha = alpha
        qrUrl.alpha = alpha
        newPassword.visibility = if (running) View.GONE else View.VISIBLE

        groupStatus.visibility = if (running && !live) View.VISIBLE else View.GONE
        groupStatus.text = BoxState.groupStatus ?: getString(R.string.creating_wifi)

        if (g != null) {
            clientsText.text = getString(R.string.clients_connected, g.clients) +
                (if (g.band.isNotEmpty()) " · ${g.band}" else "")
            if (lastClients >= 0 && g.clients > lastClients) {
                highlightUntil = SystemClock.elapsedRealtime() + HIGHLIGHT_MS
            }
            lastClients = g.clients
        } else {
            clientsText.text = if (running) "" else getString(R.string.own_wifi_stopped)
            lastClients = if (running) lastClients else -1
        }
        val highlight = SystemClock.elapsedRealtime() < highlightUntil
        joinedHint.visibility = if (highlight) View.VISIBLE else View.GONE
        step2.setBackgroundColor(if (highlight) HIGHLIGHT_COLOR else Color.TRANSPARENT)
    }

    private fun refreshSameNetwork(running: Boolean) {
        groupStatus.visibility = View.GONE
        val candidates = IpPicker.candidates(IpPicker.fromSystem())
        val best = candidates.firstOrNull()
        val url = best?.let { IpPicker.url(it.address, UploadBoxService.port) }
        if (url != shownUrl) {
            shownUrl = url
            if (url != null) {
                urlText.text = url
                qr.setImageBitmap(QrCode.bitmap(url))
                qr.visibility = View.VISIBLE
            } else {
                urlText.text = getString(R.string.no_address)
                qr.setImageDrawable(null)
                qr.visibility = View.GONE
            }
        }
        urlText.alpha = if (running) 1f else 0.4f
        qr.alpha = if (running) 1f else 0.25f
        otherAddrs.text = if (candidates.isEmpty()) "" else
            "Interface: ${best!!.iface}" + if (candidates.size > 1)
                "\nOther addresses: " + candidates.drop(1).joinToString { "${it.address} (${it.iface})" } else ""
    }

    /** Runtime permissions still missing: notifications (13+) and Wi-Fi Direct (own-Wi-Fi mode). */
    private fun missingPermissions(): List<String> {
        val out = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            out.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (BoxPrefs.ownWifiSupported && BoxPrefs.mode(this) == BoxMode.OWN_WIFI) {
            when (WifiDirectGroup.missingPermission(this)) {
                null -> {}
                Manifest.permission.ACCESS_FINE_LOCATION -> {
                    // Android 12 ignores a FINE request unless COARSE is asked for too.
                    out.add(Manifest.permission.ACCESS_FINE_LOCATION)
                    out.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                else -> out.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        return out
    }

    private fun requestRuntimePermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            if (startAfterPermissions) {
                startAfterPermissions = false
                UploadBoxService.start(this)
            }
            return
        }
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("asked_permissions", true).apply()
        requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    /** Button: ask again, or open app settings if Android won't show the dialog any more. */
    private fun requestPermissionsOrSettings() {
        val missing = missingPermissions()
        val asked = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("asked_permissions", false)
        if (asked && missing.none { shouldShowRequestPermissionRationale(it) }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            requestRuntimePermissions()
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        val intent = if (!pm.isIgnoringBatteryOptimizations(packageName))
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun registerNetworkWatchers() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = post()
            override fun onLost(network: Network) = post()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = post()
            private fun post() { handler.post { refresh() } }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(), cb)
            networkCallback = cb
        } catch (_: Exception) {}
        val filter = IntentFilter(WIFI_AP_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(apReceiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(apReceiver, filter)
    }

    private fun unregisterNetworkWatchers() {
        networkCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
        try { unregisterReceiver(apReceiver) } catch (_: Exception) {}
    }

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REFRESH_MS = 1_000L
        private const val HIGHLIGHT_MS = 30_000L
        private val HIGHLIGHT_COLOR = Color.rgb(0xFF, 0xF1, 0xB8)
        private const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    }
}
