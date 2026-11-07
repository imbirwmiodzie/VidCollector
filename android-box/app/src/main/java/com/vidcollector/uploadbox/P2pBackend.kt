package com.vidcollector.uploadbox

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper

/** What the system reports about the current Wi-Fi Direct group. */
data class GroupSnapshot(
    val ssid: String,
    val passphrase: String,
    val iface: String?,
    val isOwner: Boolean,
    val frequencyMhz: Int,
    /** MAC addresses of joined guests (as reported by the system). */
    val clientIds: List<String> = emptyList(),
) {
    val clients: Int get() = clientIds.size
}

/**
 * The few Wi-Fi Direct calls the box needs. Callbacks arrive on the main thread.
 * An interface so the group logic can be tested with a fake (Robolectric does not
 * implement createGroup(channel, config, listener)).
 */
interface P2pBackend {
    fun isAvailable(): Boolean
    /** Removes the current group if any; [done] is always called. */
    fun removeGroup(done: () -> Unit)
    fun createGroup(cred: WifiCredentials, band5GHz: Boolean, result: (ok: Boolean, reason: Int) -> Unit)
    fun requestGroup(result: (GroupSnapshot?) -> Unit)
    fun close()
}

/** Real implementation over [WifiP2pManager] (Android 10+ for named groups). */
@TargetApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingPermission") // callers check permissions first (WifiDirectGroup.problem)
class SystemP2pBackend(private val ctx: Context, private val onChannelLost: () -> Unit) : P2pBackend {
    private val manager: WifiP2pManager? = ctx.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    private fun ch(): WifiP2pManager.Channel? {
        channel?.let { return it }
        val m = manager ?: return null
        return m.initialize(ctx, Looper.getMainLooper()) {
            channel = null
            onChannelLost()
        }.also { channel = it }
    }

    override fun isAvailable() = manager != null

    override fun removeGroup(done: () -> Unit) {
        val m = manager
        val c = ch()
        if (m == null || c == null) return done()
        m.removeGroup(c, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = done()
            override fun onFailure(reason: Int) = done() // usually "no group to remove"
        })
    }

    override fun createGroup(cred: WifiCredentials, band5GHz: Boolean, result: (Boolean, Int) -> Unit) {
        val m = manager
        val c = ch()
        if (m == null || c == null) return result(false, WifiP2pManager.P2P_UNSUPPORTED)
        val config = WifiP2pConfig.Builder()
            .setNetworkName(cred.ssid)
            .setPassphrase(cred.passphrase)
            .setGroupOperatingBand(if (band5GHz) WifiP2pConfig.GROUP_OWNER_BAND_5GHZ else WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            .enablePersistentMode(false)
            .build()
        m.createGroup(c, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = result(true, 0)
            override fun onFailure(reason: Int) = result(false, reason)
        })
    }

    override fun requestGroup(result: (GroupSnapshot?) -> Unit) {
        val m = manager
        val c = ch()
        if (m == null || c == null) return result(null)
        m.requestGroupInfo(c) { g ->
            result(g?.let {
                GroupSnapshot(
                    ssid = it.networkName ?: "",
                    passphrase = it.passphrase ?: "",
                    iface = it.`interface`,
                    isOwner = it.isGroupOwner,
                    frequencyMhz = it.frequency,
                    clientIds = it.clientList?.map { d -> d.deviceAddress ?: "?" }?.sorted() ?: emptyList(),
                )
            })
        }
    }

    override fun close() {
        val m = manager
        val c = channel
        if (m != null && c != null) {
            try { m.removeGroup(c, null) } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
        }
        channel = null
    }
}
