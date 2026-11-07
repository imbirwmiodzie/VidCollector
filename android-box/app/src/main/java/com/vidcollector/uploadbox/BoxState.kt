package com.vidcollector.uploadbox

/** Process-wide state shared by the service, the activity and the HTTP server. */
object BoxState {
    @Volatile var running: Boolean = false
    @Volatile var lastError: String? = null
    @Volatile var lastEvent: String? = null
    /** Port the server actually listens on (0 when stopped). */
    @Volatile var boundPort: Int = 0
}
