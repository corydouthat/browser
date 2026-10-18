package app.hatch.selfcheck

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plain-JVM tests for [LocalHttpServer] (assets and logging injected; no Android runtime needed). */
class LocalHttpServerTest {
    private val dir: File = Files.createTempDirectory("hatch-reports").toFile()
    private val logs = CopyOnWriteArrayList<String>()
    private val reports = CopyOnWriteArrayList<File>()
    private val server = LocalHttpServer(
        reportsDir = dir,
        personaKey = { "win-firefox__us-east" },
        onReport = { f, _ -> reports += f },
        assets = { rel -> if (rel == "index.html") "<html><head></head><body>x</body></html>".toByteArray() else null },
        log = { logs += it },
    )

    @AfterEach
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun request(socket: Socket, raw: String): Reply? {
        socket.getOutputStream().apply { write(raw.toByteArray(Charsets.ISO_8859_1)); flush() }
        val input = socket.getInputStream()
        val statusLine = readLine(input) ?: return null
        val status = statusLine.split(' ')[1].toInt()
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            headers[l.substring(0, i).lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(len)
        var off = 0
        while (off < len) { val n = input.read(body, off, len - off); if (n < 0) break; off += n }
        return Reply(status, headers, String(body, Charsets.UTF_8))
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun connect(): Socket = Socket("127.0.0.1", server.port!!).apply { soTimeout = 5000 }

    @Test
    fun `token never reaches the log and echo carries the hardening headers`() {
        val url = server.start()
        val token = URI(url).path.removePrefix("/t/").trimEnd('/')
        assertEquals(32, token.length)
        assertTrue(logs.isNotEmpty())
        for (l in logs) assertFalse(l.contains(token), "token leaked into the log: $l")
        connect().use { s ->
            val r = request(s, "GET ${server.basePath}echo HTTP/1.1\r\nHost: x\r\nUser-Agent: UA1\r\nConnection: close\r\n\r\n")!!
            assertEquals(200, r.status)
            assertEquals("nosniff", r.headers["x-content-type-options"])
            assertEquals("no-referrer", r.headers["referrer-policy"])
            assertEquals("Sec-CH-UA-Mobile, Sec-CH-UA-Platform, Viewport-Width, DPR", r.headers["accept-ch"])
            assertTrue(r.body.contains("\"user-agent\":\"UA1\""), r.body)
        }
        connect().use { s ->
            val r = request(s, "GET /t/0000/echo HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")!!
            assertEquals(404, r.status, "wrong token")
        }
        connect().use { s ->
            val r = request(s, "GET ${server.basePath}index.html HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")!!
            assertEquals(200, r.status)
            assertTrue(r.body.contains("window.__hatchCrossOrigin=\"${server.crossOriginBaseUrl}\""), r.body)
        }
    }

    @Test
    fun `header floods get 431 and the connection is closed`() {
        server.start()
        connect().use { s ->
            val sb = StringBuilder("GET ${server.basePath}echo HTTP/1.1\r\nHost: x\r\n")
            repeat(1000) { sb.append("X-A").append(it).append(": a\r\n") }
            sb.append("\r\n")
            val r = request(s, sb.toString())!!
            assertEquals(431, r.status)
            assertEquals("close", r.headers["connection"])
            assertEquals(-1, s.getInputStream().read(), "server closed the connection")
        }
        connect().use { s ->
            val one = "X-B: " + "b".repeat(60 * 1024) + "\r\n"
            val r = request(s, "GET ${server.basePath}echo HTTP/1.1\r\nHost: x\r\n$one$one\r\n")!!
            assertEquals(431, r.status, "aggregate header bytes capped even with few lines")
        }
    }

    @Test
    fun `two reports in the same second land in two files`() {
        server.start()
        val body = "{\"summary\":{\"overall\":\"PASS\"}}"
        val names = (1..2).map {
            connect().use { s ->
                val r = request(s, "POST ${server.basePath}report HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")!!
                assertEquals(200, r.status, r.body)
                Regex("\"file\":\"([^\"]+)\"").find(r.body)!!.groupValues[1]
            }
        }
        assertNotEquals(names[0], names[1])
        assertEquals(2, reports.size)
        assertEquals(2, dir.listFiles()!!.size)
        for (f in dir.listFiles()!!) assertEquals(body, f.readText())
        assertTrue(names.all { it.startsWith("selfcheck-win-firefox__us-east-") && it.endsWith(".json") })
    }

    @Test
    fun `oversized report is refused with 413`() {
        server.start()
        connect().use { s ->
            val r = request(s, "POST ${server.basePath}report HTTP/1.1\r\nHost: x\r\nContent-Length: ${LocalHttpServer.MAX_BODY_BYTES + 1}\r\n\r\n")!!
            assertEquals(413, r.status, "refused before any body byte arrives")
            assertEquals("close", r.headers["connection"])
            assertEquals(-1, s.getInputStream().read(), "server closed the connection")
        }
        assertEquals(0, reports.size)
    }

    @Test
    fun `stop closes idle keep-alive connections and leaves no worker thread`() {
        server.start()
        val s = connect()
        val r = request(s, "GET ${server.basePath}echo HTTP/1.1\r\nHost: x\r\n\r\n")!!
        assertEquals(200, r.status)
        assertEquals("keep-alive", r.headers["connection"])
        server.stop()
        assertNull(server.baseUrl)
        val t0 = System.currentTimeMillis()
        s.soTimeout = 2000
        val eof = try { s.getInputStream().read() } catch (e: java.net.SocketException) { -1 }
        assertEquals(-1, eof, "client read must return EOF after stop()")
        assertTrue(System.currentTimeMillis() - t0 < 1500, "EOF within 500 ms (took ${System.currentTimeMillis() - t0} ms)")
        s.close()
        Thread.sleep(300)
        val alive = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("hatch-http") }
        assertTrue(alive.isEmpty(), "threads still alive: ${alive.map { it.name }}")
        assertTrue(logs.any { it.contains("stopped") })
    }

    @Test
    fun `unique report file names from the helper`() {
        val a = LocalHttpServer.newReportFile(dir, "k")
        val b = LocalHttpServer.newReportFile(dir, "k")
        assertNotEquals(a.name, b.name)
        assertTrue(a.exists() && b.exists())
        assertTrue(Regex("selfcheck-k-\\d{8}-\\d{6}-\\d{3}-\\d+\\.json").matches(a.name), a.name)
    }
}
