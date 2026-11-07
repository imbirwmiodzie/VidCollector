package com.vidcollector.uploadbox

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Embedded HTTP server (NanoHTTPD, one thread per connection).
 *
 * Endpoints:
 *  - GET  /         upload page (from app assets)
 *  - GET  /health   {ok, ip, port, freeBytes, files, uploading}
 *  - GET  /files    {files:[{name,size,modified}]}
 *  - POST /upload?name=<file name>   raw request body = file bytes (also PUT).
 *
 * The body is streamed to disk in 256 KiB chunks, never held in memory, so file
 * size is limited only by free space. Data goes to a hidden ".*.part" file first and
 * is renamed when every byte declared in Content-Length has arrived.
 *
 * Kept free of android.* imports so it runs in plain JVM unit tests.
 */
class UploadServer(
    port: Int,
    private val storageDir: File,
    private val indexHtml: () -> ByteArray,
    private val ipProvider: () -> String?,
    private val events: Events? = null,
) : NanoHTTPD(port) {

    interface Events {
        fun onUploadStarted(name: String, bytes: Long) {}
        fun onUploadFinished(file: File, bytes: Long, millis: Long, sha256: String) {}
        fun onUploadFailed(name: String, reason: String) {}
        /** Every request; lets the app log the first request from each guest address. */
        fun onRequest(remoteIp: String, method: String, path: String) {}
    }

    private val uploading = AtomicInteger(0)
    private val nameLock = Any()
    val serverPort: Int get() = listeningPort

    init {
        storageDir.mkdirs()
    }

    fun startServer() {
        // Generous read timeout: a client phone may stall for a while (screen off, Wi-Fi roam).
        start(SOCKET_TIMEOUT_MS, true)
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        try { events?.onRequest(session.remoteIpAddress ?: "?", session.method.name, path) } catch (_: Exception) {}
        return try {
            when {
                session.method == Method.GET && (path == "/" || path == "/index.html") -> htmlResponse()
                session.method == Method.GET && path == "/health" -> json(Response.Status.OK, healthJson())
                session.method == Method.GET && path == "/files" -> json(Response.Status.OK, filesJson())
                (session.method == Method.POST || session.method == Method.PUT) && path == "/upload" ->
                    handleUpload(session)
                path == "/upload" || path == "/health" || path == "/files" ->
                    error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed", close = true)
                else -> error(Response.Status.NOT_FOUND, "not found", close = false)
            }
        } catch (e: Exception) {
            error(Response.Status.INTERNAL_ERROR, e.toString(), close = true)
        }
    }

    private fun htmlResponse(): Response {
        val bytes = indexHtml()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
            bytes.inputStream(), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    // ---- /upload -------------------------------------------------------------

    private fun handleUpload(session: IHTTPSession): Response {
        val lengthHeader = session.headers["content-length"]
            ?: return error(STATUS_LENGTH_REQUIRED, "Content-Length required (chunked bodies are not supported)", close = true)
        val length = lengthHeader.trim().toLongOrNull()
        if (length == null || length < 0) return error(Response.Status.BAD_REQUEST, "bad Content-Length", close = true)

        val requested = session.parameters["name"]?.firstOrNull()
            ?: session.headers["x-file-name"]
            ?: "upload-${System.currentTimeMillis()}.bin"
        val safeName = sanitizeFileName(requested)

        val free = storageDir.usableSpace
        if (length > free - FREE_SPACE_RESERVE) {
            events?.onUploadFailed(safeName, "not enough space")
            // We do not read the body, so the connection must be closed afterwards.
            return error(STATUS_INSUFFICIENT_STORAGE,
                "not enough space: need $length bytes, free $free", close = true)
        }

        val part = File(storageDir, ".${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_SIZE)
        val started = System.nanoTime()
        var remaining = length
        uploading.incrementAndGet()
        events?.onUploadStarted(safeName, length)
        try {
            val input = session.inputStream
            FileOutputStream(part).use { out ->
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    remaining -= n
                }
                out.fd.sync()
            }
        } catch (e: IOException) {
            part.delete()
            uploading.decrementAndGet()
            events?.onUploadFailed(safeName, e.toString())
            return error(Response.Status.INTERNAL_ERROR, "upload interrupted: $e", close = true)
        }
        uploading.decrementAndGet()

        if (remaining > 0) {
            part.delete()
            events?.onUploadFailed(safeName, "incomplete: ${length - remaining}/$length bytes")
            return error(Response.Status.BAD_REQUEST,
                "incomplete body: got ${length - remaining} of $length bytes", close = true)
        }

        val target: File
        synchronized(nameLock) {
            target = uniqueFile(safeName)
            if (!part.renameTo(target)) {
                part.delete()
                return error(Response.Status.INTERNAL_ERROR, "rename failed", close = true)
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        val sha = digest.digest().toHex()
        events?.onUploadFinished(target, length, millis, sha)
        val mbps = if (millis > 0) length / 1_000_000.0 / (millis / 1000.0) else 0.0
        return json(Response.Status.OK, buildString {
            append("{\"ok\":true,\"name\":").append(jsonStr(target.name))
            append(",\"size\":").append(length)
            append(",\"sha256\":").append(jsonStr(sha))
            append(",\"ms\":").append(millis)
            append(",\"mbps\":").append(String.format(Locale.US, "%.2f", mbps))
            append('}')
        })
    }

    private fun uniqueFile(name: String): File {
        var f = File(storageDir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(storageDir, "$base-$i$ext")
            i++
        }
        return f
    }

    // ---- JSON ----------------------------------------------------------------

    fun completedFiles(): List<File> =
        (storageDir.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }

    fun healthJson(): String = buildString {
        append("{\"ok\":true")
        append(",\"ip\":").append(ipProvider()?.let { jsonStr(it) } ?: "null")
        append(",\"port\":").append(serverPort)
        append(",\"freeBytes\":").append(storageDir.usableSpace)
        append(",\"files\":").append(completedFiles().size)
        append(",\"uploading\":").append(uploading.get())
        append('}')
    }

    fun filesJson(): String = buildString {
        append("{\"files\":[")
        completedFiles().forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("{\"name\":").append(jsonStr(f.name))
            append(",\"size\":").append(f.length())
            append(",\"modified\":").append(f.lastModified())
            append('}')
        }
        append("]}")
    }

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun error(status: Response.IStatus, message: String, close: Boolean): Response =
        json(status, "{\"ok\":false,\"error\":${jsonStr(message)}}").apply {
            if (close) closeConnection(true)
        }

    companion object {
        const val DEFAULT_PORT = 8080
        const val SOCKET_TIMEOUT_MS = 120_000
        const val BUFFER_SIZE = 256 * 1024
        const val FREE_SPACE_RESERVE = 50L * 1024 * 1024

        val STATUS_LENGTH_REQUIRED = status(411, "Length Required")
        val STATUS_INSUFFICIENT_STORAGE = status(507, "Insufficient Storage")

        private fun status(code: Int, text: String) = object : Response.IStatus {
            override fun getDescription() = "$code $text"
            override fun getRequestStatus() = code
        }

        /** Keeps only the last path segment and a conservative set of characters. */
        fun sanitizeFileName(raw: String): String {
            var name = raw.substringAfterLast('/').substringAfterLast('\\')
            name = name.replace(Regex("[^\\p{L}\\p{N}._\\- ()]"), "_").trim().trimStart('.')
            if (name.length > 150) {
                val dot = name.lastIndexOf('.')
                val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
                name = name.substring(0, 150 - ext.length) + ext
            }
            return name.ifEmpty { "upload.bin" }
        }

        fun jsonStr(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
            append('"')
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
