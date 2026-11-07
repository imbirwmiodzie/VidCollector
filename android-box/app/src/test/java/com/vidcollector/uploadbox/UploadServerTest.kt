package com.vidcollector.uploadbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Plain-JVM tests of the HTTP server over real loopback sockets. */
class UploadServerTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private lateinit var server: UploadServer
    private val port get() = server.serverPort

    private val requests = java.util.Collections.synchronizedList(ArrayList<String>())

    @Before fun setUp() {
        dir = tmp.newFolder("uploads")
        server = UploadServer(0, dir, { "<h1>Upload box works</h1>".toByteArray() }, { "192.168.43.1" },
            object : UploadServer.Events {
                override fun onRequest(remoteIp: String, method: String, path: String) {
                    requests.add("$remoteIp $method $path")
                }
            })
        server.startServer()
    }

    @After fun tearDown() = server.stop()

    @Test fun indexPage() {
        val r = HttpTestClient.get(port, "/")
        assertEquals(200, r.code)
        assertTrue(r.body.contains("Upload box works"))
        assertEquals(listOf("127.0.0.1 GET /"), requests.toList())
    }

    @Test fun healthJson() {
        val r = HttpTestClient.get(port, "/health")
        assertEquals(200, r.code)
        assertTrue(r.body, r.body.startsWith("{\"ok\":true,\"ip\":\"192.168.43.1\",\"port\":$port,\"freeBytes\":"))
        assertTrue(r.body, r.body.contains("\"files\":0"))
    }

    @Test fun upload64MbStreamsAndChecksumMatches() {
        val size = 64L * 1024 * 1024 + 123 // not a multiple of the buffer size
        val (reply, sentSha) = HttpTestClient.uploadRandom(port, "clip 1.mp4", size)
        assertEquals(reply.body, 200, reply.code)
        assertTrue(reply.body, reply.body.contains("\"sha256\":\"$sentSha\""))
        val f = File(dir, "clip 1.mp4")
        assertEquals(size, f.length())
        assertEquals(sentSha, HttpTestClient.sha256(f))
        assertTrue(HttpTestClient.get(port, "/health").body.contains("\"files\":1"))
        val files = HttpTestClient.get(port, "/files").body
        assertTrue(files, files.contains("{\"name\":\"clip 1.mp4\",\"size\":$size,"))
        // no temp files left behind
        assertEquals(listOf("clip 1.mp4"), dir.list()!!.toList())
    }

    @Test fun sameNameGetsSuffix() {
        HttpTestClient.uploadRandom(port, "a.mov", 1000, seed = 1)
        HttpTestClient.uploadRandom(port, "a.mov", 2000, seed = 2)
        HttpTestClient.uploadRandom(port, "a.mov", 3000, seed = 3)
        assertEquals(setOf("a.mov", "a-1.mov", "a-2.mov"), dir.list()!!.toSet())
        assertEquals(2000L, File(dir, "a-1.mov").length())
    }

    @Test fun pathTraversalIsNeutralised() {
        val (r, _) = HttpTestClient.uploadRandom(port, "../../evil.sh", 10)
        assertEquals(200, r.code)
        assertTrue(File(dir, "evil.sh").exists())
        assertFalse(File(dir.parentFile, "evil.sh").exists())
    }

    @Test fun emptyFileAllowed() {
        val (r, sha) = HttpTestClient.uploadRandom(port, "empty.bin", 0)
        assertEquals(200, r.code)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha)
        assertEquals(0L, File(dir, "empty.bin").length())
    }

    @Test fun truncatedUploadLeavesNothing() {
        val resp = HttpTestClient.truncatedUpload(port, "cut.mp4", declared = 5_000_000, sent = 100_000)
        assertTrue(resp, resp.startsWith("HTTP/1.1 400"))
        Thread.sleep(200)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun missingContentLengthRejected() {
        val resp = HttpTestClient.rawRequest(port,
            "POST /upload?name=x HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
        assertTrue(resp, resp.startsWith("HTTP/1.1 411"))
    }

    @Test fun tooLargeRejectedWithoutReadingBody() {
        val resp = HttpTestClient.rawRequest(port,
            "POST /upload?name=huge.bin HTTP/1.1\r\nHost: x\r\nContent-Length: ${Long.MAX_VALUE / 2}\r\n\r\n")
        assertTrue(resp, resp.startsWith("HTTP/1.1 507"))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun unknownPath404AndWrongMethod405() {
        assertEquals(404, HttpTestClient.get(port, "/nope").code)
        assertEquals(405, HttpTestClient.get(port, "/upload").code)
    }

    @Test fun sanitize() {
        assertEquals("video.mp4", UploadServer.sanitizeFileName("/storage/emulated/0/DCIM/video.mp4"))
        assertEquals("a_b.mp4", UploadServer.sanitizeFileName("a:b.mp4"))
        assertEquals("upload.bin", UploadServer.sanitizeFileName("..."))
        assertEquals("zdjęcie 1.jpg", UploadServer.sanitizeFileName("zdjęcie 1.jpg"))
        val long = UploadServer.sanitizeFileName("x".repeat(300) + ".mp4")
        assertEquals(150, long.length)
        assertTrue(long.endsWith(".mp4"))
    }

    @Test fun jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\"", UploadServer.jsonStr("a\"b\\c\n"))
    }
}
