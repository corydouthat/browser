package app.hatch.selfcheck

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loopback HTTP/1.1 server for the Self-check screen. Alive only while the screen is open.
 *
 * Two listeners on 127.0.0.1 with ephemeral ports: the page origin and a second origin for the cross-origin
 * iframe probe. Every URL is under a random 128-bit token path `/t/<token>/` (anything else is 404):
 *  - `index.html` (or empty path): `assets/selfcheck/index.html` with a `<script>` injected after `<head>` that
 *    sets `window.__hatchCrossOrigin` to the second origin's token base.
 *  - `echo` (GET): `{"headers":{...}}` of lowercase request headers the harness cares about (null when absent)
 *    with `Accept-CH: Sec-CH-UA-Mobile, Sec-CH-UA-Platform, Viewport-Width, DPR` on the response.
 *  - `report` (POST, <= 2 MB): saved as `files/reports/selfcheck-<persona>-<timestamp>-<n>.json`; [onReport] fires.
 *  - anything else: `assets/selfcheck/<path>` with a content type from the extension.
 *
 * Hardening (SEC-5 / PERF-04): the token is never logged (only the port); headers are capped at
 * [MAX_HEADER_LINES] / [MAX_HEADER_BYTES] (431, connection closed); at most [MAX_CONNECTIONS] worker threads
 * (a further connection gets a 503 and is closed); every accepted socket is tracked and closed by [stop], so
 * no thread outlives the screen; report file names carry milliseconds and a counter so no two reports collide.
 *
 * [assets] reads `assets/selfcheck/<rel>` (null = missing); the secondary constructor wires it to a [Context]
 * so the class stays constructible in a plain JVM test.
 */
class LocalHttpServer(
    private val reportsDir: File,
    private val personaKey: () -> String,
    private val onReport: (File, String) -> Unit,
    private val assets: (String) -> ByteArray?,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    constructor(context: Context, reportsDir: File, personaKey: () -> String, onReport: (File, String) -> Unit) : this(
        reportsDir = reportsDir,
        personaKey = personaKey,
        onReport = onReport,
        assets = { rel -> try { context.assets.open("$ASSET_ROOT/$rel").use { it.readBytes() } } catch (e: IOException) { null } },
    )

    private class Listener(val socket: ServerSocket, val thread: Thread)

    private val running = AtomicBoolean(false)
    private val token: String = randomToken()
    private var primary: Listener? = null
    private var secondary: Listener? = null
    private var executor: ExecutorService? = null
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet())

    val basePath: String get() = "/t/$token/"

    /** `http://127.0.0.1:<port>/t/<token>/` of the page origin; null when stopped. */
    val baseUrl: String? get() = primary?.let { "http://127.0.0.1:${it.socket.localPort}$basePath" }

    /** Base of the second origin (for `window.__hatchCrossOrigin`). */
    val crossOriginBaseUrl: String? get() = secondary?.let { "http://127.0.0.1:${it.socket.localPort}$basePath" }

    val isRunning: Boolean get() = running.get()

    /** Loopback port of the page origin (what the log mentions instead of the token); null when stopped. */
    val port: Int? get() = primary?.socket?.localPort

    @Synchronized
    fun start(): String {
        if (running.get()) return baseUrl!!
        val pool = ThreadPoolExecutor(2, MAX_CONNECTIONS, 15L, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { r ->
            Thread(r, "hatch-http").apply { isDaemon = true }
        }
        executor = pool
        running.set(true)
        primary = listen(pool)
        secondary = listen(pool)
        log("self-check server up on 127.0.0.1:${primary!!.socket.localPort} (cross-origin :${secondary!!.socket.localPort}); token not logged")
        return baseUrl!!
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        listOf(primary, secondary).forEach { l -> runCatching { l?.socket?.close() } }
        primary = null
        secondary = null
        // Interrupting does not unblock a socket read: close every client socket explicitly.
        val open = synchronized(clients) { clients.toList().also { clients.clear() } }
        open.forEach { runCatching { it.close() } }
        executor?.shutdownNow()
        executor = null
        log("self-check server stopped (${open.size} connection(s) closed)")
    }

    private fun listen(pool: ExecutorService): Listener {
        val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val thread = Thread({
            while (running.get() && !socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                clients += client
                try {
                    pool.execute { serve(client) }
                } catch (e: RejectedExecutionException) {
                    runCatching { refuse(client) }
                }
            }
        }, "hatch-http-accept-${socket.localPort}").apply { isDaemon = true; start() }
        return Listener(socket, thread)
    }

    /** All worker threads busy: answer 503 on the accept thread and drop the connection. */
    private fun refuse(client: Socket) {
        client.use { s ->
            clients.remove(s)
            s.soTimeout = 1000
            val out = BufferedOutputStream(s.getOutputStream())
            val body = "busy".toByteArray()
            out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }

    // ---- connection handling ---------------------------------------------------------------------

    private class Request(val method: String, val path: String, val query: String, val version: String, val headers: Map<String, String>, val body: ByteArray)

    private class HeaderLimitException : IOException("too many header bytes")

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = KEEPALIVE_TIMEOUT_MS
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                try {
                    while (running.get()) {
                        val request = try { readRequest(input) } catch (e: HeaderLimitException) {
                            writeResponse(output, null, Response(431, "text/plain; charset=utf-8", "request header fields too large".toByteArray()), false)
                            break
                        } ?: break
                        val keepAlive = wantsKeepAlive(request) && !request.headers.containsKey("x-hatch-too-large")
                        val response = try { handle(request) } catch (e: Exception) {
                            log("handler failed for ${request.path}: ${e.message}")
                            Response(500, "text/plain; charset=utf-8", "internal error".toByteArray())
                        }
                        writeResponse(output, request, response, keepAlive)
                        if (!keepAlive) break
                    }
                } catch (e: SocketTimeoutException) {
                    // idle keep-alive connection; close quietly
                } catch (e: IOException) {
                    // client went away or the server stopped
                }
            }
        } finally {
            clients.remove(client)
        }
    }

    private fun wantsKeepAlive(r: Request): Boolean {
        val connection = r.headers["connection"]?.lowercase(Locale.ROOT)
        return when {
            connection == "close" -> false
            r.version == "HTTP/1.0" -> connection == "keep-alive"
            else -> true
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val version = parts.getOrNull(2) ?: "HTTP/1.0"
        val headers = LinkedHashMap<String, String>()
        var lines = 0
        var bytes = requestLine.length
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            lines++
            bytes += line.length + 2
            if (lines > MAX_HEADER_LINES || bytes > MAX_HEADER_BYTES) throw HeaderLimitException()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            headers[name] = if (headers.containsKey(name)) headers[name] + ", " + value else value
        }
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) target.substring(q + 1) else ""
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        val body = when {
            length <= 0L -> ByteArray(0)
            length > MAX_BODY_BYTES -> {
                // Answer 413 at once and close the connection; nothing of the body is read (a 2 MB drain would
                // block on a client that never sends it, and the close reaches a browser mid-upload anyway).
                return Request(method, path, query, version, headers + ("x-hatch-too-large" to "1"), ByteArray(0))
            }
            else -> readFully(input, length.toInt())
        }
        return Request(method, path, query, version, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_LINE_BYTES) throw HeaderLimitException()
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(out, off, length - off)
            if (n < 0) throw IOException("unexpected end of body")
            off += n
        }
        return out
    }

    private class Response(val status: Int, val contentType: String, val body: ByteArray, val extraHeaders: Map<String, String> = emptyMap())

    private fun writeResponse(out: OutputStream, request: Request?, r: Response, keepAlive: Boolean) {
        val reason = when (r.status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"; else -> "Error"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        for ((k, v) in r.extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (request == null || request.method != "HEAD") out.write(r.body)
        out.flush()
    }

    // ---- routing -----------------------------------------------------------------------------------

    private fun handle(r: Request): Response {
        if (!r.path.startsWith(basePath)) return notFound()
        val rel = r.path.substring(basePath.length)
        if (r.headers.containsKey("x-hatch-too-large")) {
            return Response(413, "text/plain; charset=utf-8", "report too large (max $MAX_BODY_BYTES bytes)".toByteArray())
        }
        return when (rel) {
            "echo" -> echo(r)
            "report" -> if (r.method == "POST") report(r) else Response(405, "text/plain; charset=utf-8", "POST only".toByteArray())
            "", "index.html" -> indexPage()
            else -> asset(rel)
        }
    }

    private fun notFound() = Response(404, "text/plain; charset=utf-8", "not found".toByteArray())

    private fun echo(r: Request): Response {
        val sb = StringBuilder("{\"headers\":{")
        var first = true
        for (name in ECHO_HEADERS) {
            if (!first) sb.append(',')
            first = false
            sb.append(jsonString(name)).append(':')
            val v = r.headers[name]
            if (v == null) sb.append("null") else sb.append(jsonString(v))
        }
        // any other client-hint header the engine might send, so a regression shows up
        for ((k, v) in r.headers) {
            if (k.startsWith("sec-ch-") && k !in ECHO_HEADERS) sb.append(',').append(jsonString(k)).append(':').append(jsonString(v))
        }
        sb.append("},\"method\":").append(jsonString(r.method)).append(",\"query\":").append(jsonString(r.query)).append('}')
        return Response(
            200, "application/json; charset=utf-8", sb.toString().toByteArray(),
            mapOf("Accept-CH" to "Sec-CH-UA-Mobile, Sec-CH-UA-Platform, Viewport-Width, DPR"),
        )
    }

    private fun report(r: Request): Response {
        if (r.body.isEmpty()) return Response(400, "text/plain; charset=utf-8", "empty body".toByteArray())
        val file = newReportFile(reportsDir, safeName(personaKey()))
        file.writeBytes(r.body)
        val text = String(r.body, Charsets.UTF_8)
        log("self-check report saved: ${file.name} (${r.body.size} bytes)")
        onReport(file, text)
        return Response(200, "application/json; charset=utf-8", "{\"ok\":true,\"file\":${jsonString(file.name)}}".toByteArray())
    }

    private fun indexPage(): Response {
        val html = assets("index.html") ?: return notFound()
        val text = String(html, Charsets.UTF_8)
        val cross = crossOriginBaseUrl ?: ""
        val inject = "<script>window.__hatchCrossOrigin=${jsonString(cross)};</script>"
        val idx = text.indexOf("<head>", ignoreCase = true)
        val patched = if (idx >= 0) {
            val end = idx + "<head>".length
            text.substring(0, end) + inject + text.substring(end)
        } else {
            inject + text
        }
        return Response(200, "text/html; charset=utf-8", patched.toByteArray(Charsets.UTF_8))
    }

    private fun asset(rel: String): Response {
        if (rel.contains("..") || rel.startsWith("/") || rel.contains('\\')) return notFound()
        val bytes = assets(rel) ?: return notFound()
        return Response(200, contentType(rel), bytes)
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        "txt", "md" -> "text/plain; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "LocalHttpServer"
        const val ASSET_ROOT = "selfcheck"
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        const val MAX_HEADER_LINES = 64
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_CONNECTIONS = 32
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val KEEPALIVE_TIMEOUT_MS = 5_000
        private val ECHO_HEADERS = listOf(
            "user-agent", "accept-language", "accept", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "sec-ch-ua-platform-version", "sec-ch-ua-arch", "sec-ch-ua-model", "sec-ch-ua-full-version-list",
            "save-data", "viewport-width", "dpr", "device-memory", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest",
        )
        private val reportCounter = AtomicInteger()

        fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)

        /**
         * A fresh, never-existing `selfcheck-<key>-yyyyMMdd-HHmmss-SSS-<n>.json` under [dir] (created empty):
         * two reports in the same second (Run twice, or POST + bridge fallback) never overwrite each other.
         */
        fun newReportFile(dir: File, key: String): File {
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            while (true) {
                val n = reportCounter.incrementAndGet()
                val f = File(dir, "selfcheck-$key-$ts-$n.json")
                if (f.createNewFile()) return f
            }
        }

        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '<' -> sb.append("\\u003c")
                    else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}
