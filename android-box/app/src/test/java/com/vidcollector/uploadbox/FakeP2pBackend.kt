package com.vidcollector.uploadbox

/** In-memory stand-in for the system Wi-Fi Direct service. Synchronous callbacks. */
class FakeP2pBackend : P2pBackend {
    var available = true
    var fail5GHz = false
    var failAll = false
    var clients = 0
    var group: GroupSnapshot? = null
    val calls = mutableListOf<String>()

    override fun isAvailable() = available

    override fun removeGroup(done: () -> Unit) {
        calls += "remove"
        group = null
        done()
    }

    override fun createGroup(cred: WifiCredentials, band5GHz: Boolean, result: (Boolean, Int) -> Unit) {
        calls += if (band5GHz) "create:5GHz" else "create:auto"
        if (failAll || (band5GHz && fail5GHz)) {
            result(false, 0)
            return
        }
        group = GroupSnapshot(cred.ssid, cred.passphrase, "p2p-wlan0-0", true, 0, if (band5GHz) 5180 else 2437)
        result(true, 0)
    }

    override fun requestGroup(result: (GroupSnapshot?) -> Unit) {
        calls += "request"
        result(group?.copy(clients = clients))
    }

    override fun close() {
        calls += "close"
        group = null
    }

    fun creates() = calls.count { it.startsWith("create") }
}
