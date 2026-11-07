package com.vidcollector.uploadbox

import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Random

/** Minimal blocking HTTP client for tests (no extra dependencies). */
object HttpTestClient {

    data class Reply(val code: Int, val body: String)

    fun get(port: Int, path: String): Reply {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 30000
        return read(c)
    }

    /**
     * Streams [size] pseudo-random bytes (seeded) as the request body without ever
     * holding the file in memory; returns the reply and the SHA-256 of what was sent.
     */
    fun uploadRandom(port: Int, name: String, size: Long, seed: Long = 42): Pair<Reply, String> {
        val c = URL("http://127.0.0.1:$port/upload?name=" + URLEncoder.encode(name, "UTF-8"))
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = 120000
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(size)
        val md = MessageDigest.getInstance("SHA-256")
        val rnd = Random(seed)
        val buf = ByteArray(1 shl 20)
        c.outputStream.use { out ->
            var left = size
            while (left > 0) {
                rnd.nextBytes(buf)
                val n = minOf(left, buf.size.toLong()).toInt()
                out.write(buf, 0, n)
                md.update(buf, 0, n)
                left -= n
            }
        }
        return read(c) to md.digest().hex()
    }

    /** Sends headers announcing [declared] bytes, sends only [sent], then closes the socket. */
    fun truncatedUpload(port: Int, name: String, declared: Long, sent: Int): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10000
            val out = s.getOutputStream()
            out.write(("POST /upload?name=$name HTTP/1.1\r\nHost: x\r\nContent-Length: $declared\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray())
            out.write(ByteArray(sent) { 7 })
            out.flush()
            s.shutdownOutput()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    fun rawRequest(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10000
            s.getOutputStream().write(request.toByteArray())
            s.getOutputStream().flush()
            s.shutdownOutput()
            s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().hex()
    }

    private fun read(c: HttpURLConnection): Reply {
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return Reply(code, body)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
