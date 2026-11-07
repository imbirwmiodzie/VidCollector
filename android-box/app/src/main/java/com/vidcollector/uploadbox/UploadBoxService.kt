package com.vidcollector.uploadbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Foreground service (type dataSync) that owns the HTTP server, a partial wake lock and
 * a Wi-Fi lock, so uploads keep going with the screen off.
 */
class UploadBoxService : Service() {

    private var server: UploadServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastText: String? = null
    private var wifiDirect: WifiDirectGroup? = null

    private val ipPoll = object : Runnable {
        override fun run() {
            updateNotificationIfChanged()
            handler.postDelayed(this, IP_POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        BoxState.mode = BoxPrefs.mode(this)
        lastText = notificationText()
        goForeground()
        if (server == null) {
            try {
                startServer()
            } catch (e: Exception) {
                Log.e(TAG, "server start failed", e)
                BoxState.lastError = "Could not start server on port $port: ${e.message}"
                stopEverything()
                return START_NOT_STICKY
            }
            acquireLocks()
            if (BoxState.mode == BoxMode.OWN_WIFI && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiDirect = WifiDirectGroup(this, WifiDirectGroup.backendFactoryForTests?.invoke(this)) {
                    handler.post { updateNotificationIfChanged() }
                }
                    .also { it.start() }
            }
            handler.removeCallbacks(ipPoll)
            handler.postDelayed(ipPoll, IP_POLL_MS)
        }
        return START_STICKY
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun startServer() {
        val dir = storageDir(this)
        val s = UploadServer(
            port = port,
            storageDir = dir,
            indexHtml = { assets.open("index.html").use { it.readBytes() } },
            ipProvider = { currentIp() },
            events = object : UploadServer.Events {
                override fun onUploadStarted(name: String, bytes: Long) {
                    BoxState.lastEvent = "Receiving $name (${formatBytes(bytes)})"
                    Log.i(TAG, "upload start $name $bytes")
                }

                override fun onUploadFinished(file: File, bytes: Long, millis: Long, sha256: String) {
                    val mbps = if (millis > 0) bytes / 1e6 / (millis / 1000.0) else 0.0
                    BoxState.lastEvent = String.format(Locale.US, "Received %s: %s in %.1f s (%.1f MB/s)",
                        file.name, formatBytes(bytes), millis / 1000.0, mbps)
                    Log.i(TAG, "upload done ${file.name} $bytes B ${millis}ms sha256=$sha256")
                }

                override fun onUploadFailed(name: String, reason: String) {
                    BoxState.lastEvent = "Upload of $name failed: $reason"
                    Log.w(TAG, "upload failed $name: $reason")
                }
            },
        )
        s.startServer()
        server = s
        BoxState.running = true
        BoxState.boundPort = s.serverPort
        BoxState.lastError = null
        Log.i(TAG, "server listening on ${s.serverPort}, storing to $dir")
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UploadBox::server").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // HIGH_PERF is a no-op from Android 14; LOW_LATENCY only applies while the screen is on.
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "UploadBox::wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun stopEverything() {
        handler.removeCallbacks(ipPoll)
        wifiDirect?.stop()
        wifiDirect = null
        server?.stop()
        server = null
        releaseLocks()
        BoxState.running = false
        BoxState.boundPort = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15+: dataSync services get ~6 h per 24 h; we must stop when told. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service time limit reached, stopping")
        BoxState.lastError = "Android stopped the box after its dataSync time limit (6 h / 24 h). Press Start again."
        stopEverything()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ipPoll)
        wifiDirect?.stop()
        wifiDirect = null
        server?.stop()
        server = null
        releaseLocks()
        BoxState.running = false
        BoxState.boundPort = 0
        super.onDestroy()
    }

    private fun notificationText(): String {
        val url = currentIp()?.let { IpPicker.url(it, port) }
        return if (BoxState.mode == BoxMode.OWN_WIFI) {
            val g = BoxState.group
            if (g != null && url != null) "Wi-Fi ${g.ssid} · $url" else BoxState.groupStatus ?: "Creating Wi-Fi…"
        } else url ?: getString(R.string.no_address)
    }

    private fun updateNotificationIfChanged() {
        val text = notificationText()
        if (text == lastText) return
        lastText = text
        val nm = getSystemService(NotificationManager::class.java)
        try { nm.notify(NOTIFICATION_ID, buildNotification()) } catch (_: SecurityException) {}
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, UploadBoxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(lastText ?: getString(R.string.no_address))
            .setSmallIcon(R.drawable.ic_stat_box)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "UploadBox"
        const val ACTION_START = "com.vidcollector.uploadbox.START"
        const val ACTION_STOP = "com.vidcollector.uploadbox.STOP"
        const val CHANNEL_ID = "upload_box"
        const val NOTIFICATION_ID = 1
        const val IP_POLL_MS = 5_000L

        /** Port 8080: ports below 1024 need root on Android. Mutable only for tests. */
        @Volatile var port: Int = UploadServer.DEFAULT_PORT

        /** Address guests use: the Wi-Fi Direct group IP in OWN_WIFI mode, else the best LAN IP. */
        fun currentIp(): String? =
            if (BoxState.mode == BoxMode.OWN_WIFI) BoxState.group?.ip
            else IpPicker.pick(IpPicker.fromSystem())?.address

        fun storageDir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "uploads").apply { mkdirs() }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, UploadBoxService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, UploadBoxService::class.java).setAction(ACTION_STOP))
        }

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        }

        fun formatBytes(b: Long): String = when {
            b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b / (1L shl 10).toDouble())
            else -> "$b B"
        }
    }
}
