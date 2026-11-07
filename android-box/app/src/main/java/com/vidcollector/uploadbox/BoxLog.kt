package com.vidcollector.uploadbox

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small in-memory event log shown on the box screen ("Wi-Fi log"), so problems on a real
 * phone can be diagnosed from a screenshot without adb. Also mirrored to logcat (tag UploadBox).
 */
object BoxLog {
    private const val MAX = 40
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile var version = 0
        private set
    /** Test hook / sink for logcat; android.util.Log is not available in plain JVM tests. */
    @Volatile var sink: ((String) -> Unit)? = null

    @Synchronized fun add(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        lines.addFirst(line)
        while (lines.size > MAX) lines.removeLast()
        version++
        sink?.invoke(msg)
    }

    @Synchronized fun snapshot(): List<String> = lines.toList()

    @Synchronized fun clear() {
        lines.clear()
        version++
    }
}
