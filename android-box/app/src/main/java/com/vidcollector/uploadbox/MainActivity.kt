package com.vidcollector.uploadbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var urlText: TextView
    private lateinit var qr: ImageView
    private lateinit var otherAddrs: TextView
    private lateinit var event: TextView
    private lateinit var files: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var notifButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var shownUrl: String? = "\u0000" // force first render
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        startStop = findViewById(R.id.start_stop)
        urlText = findViewById(R.id.url)
        qr = findViewById(R.id.qr)
        otherAddrs = findViewById(R.id.other_addresses)
        event = findViewById(R.id.last_event)
        files = findViewById(R.id.files)
        batteryStatus = findViewById(R.id.battery_status)
        notifButton = findViewById(R.id.notifications)

        startStop.setOnClickListener {
            if (BoxState.running) UploadBoxService.stop(this) else {
                BoxState.lastError = null
                UploadBoxService.start(this)
            }
            handler.postDelayed({ refresh() }, 300)
        }
        findViewById<Button>(R.id.battery).setOnClickListener { openBatteryExemption() }
        findViewById<Button>(R.id.app_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        notifButton.setOnClickListener { requestNotificationPermission(force = true) }

        UploadBoxService.createChannel(this)
        requestNotificationPermission(force = false)
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

    private fun refresh() {
        val running = BoxState.running
        status.text = when {
            running -> getString(R.string.status_running)
            BoxState.lastError != null -> BoxState.lastError
            else -> getString(R.string.status_stopped)
        }
        startStop.text = getString(if (running) R.string.stop else R.string.start)

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

        event.text = BoxState.lastEvent ?: ""

        val list = UploadBoxService.storageDir(this).listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        files.text = if (list.isEmpty()) getString(R.string.no_files) else
            list.joinToString("\n") { "${it.name}  —  ${UploadBoxService.formatBytes(it.length())}" }

        val pm = getSystemService(PowerManager::class.java)
        batteryStatus.text = getString(
            if (pm.isIgnoringBatteryOptimizations(packageName)) R.string.battery_ok else R.string.battery_not_ok)

        notifButton.visibility = if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            View.VISIBLE else View.GONE
    }

    private fun requestNotificationPermission(force: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (force && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            // Either never asked, or "don't ask again": the settings page always works.
            val prefs = getSharedPreferences("ui", MODE_PRIVATE)
            if (prefs.getBoolean("asked_notifications", false)) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                return
            }
        }
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
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
        private const val REQ_NOTIFICATIONS = 1
        private const val REFRESH_MS = 2_000L
        private const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    }
}
