package ir.aria.downloader

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny raw-socket HTTP/1.1 server used by the tests. Runs inside the unit-test JVM and supports
 * exactly the behaviours the downloader must survive: content-length bodies, chunked bodies,
 * ranges, redirects, errors and slow (abortable) streams.
 */
class TestHttpServer : AutoCloseable {

    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, (Exchange) -> Unit>()
    private val pool = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(true)

    init {
        Thread {
            while (running.get()) {
                try {
                    val client = serverSocket.accept()
                    pool.execute { serve(client) }
                } catch (e: Exception) {
                    if (!running.get()) return@Thread
                }
            }
        }.apply { isDaemon = true }.start()
    }

    val port: Int get() = serverSocket.localPort
    val base: String get() = "http://127.0.0.1:$port"

    fun url(path: String): String = base + path

    fun route(path: String, handler: (Exchange) -> Unit) {
        routes[path] = handler
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) {
                    headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
                }
            }
            val exchange = Exchange(method, path, rawPath, headers, client.getOutputStream())
            val handler = routes[path]
            if (handler == null) exchange.respond(404, ByteArray(0)) else handler(exchange)
            exchange.flush()
        } catch (e: Exception) {
            // The client aborted mid-response (this is exactly what the abort tests do).
        } finally {
            runCatching { client.close() }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        pool.shutdownNow()
    }

    class Exchange(
        val method: String,
        val path: String,
        val rawPath: String,
        val headers: Map<String, String>,
        private val out: OutputStream
    ) {
        private var headWritten = false

        fun header(name: String): String? = headers[name.lowercase()]

        fun respond(status: Int, body: ByteArray, extraHeaders: Map<String, String> = emptyMap()) {
            writeHead(status, extraHeaders + mapOf("Content-Length" to body.size.toString()))
            out.write(body)
            out.flush()
        }

        fun respondStatus(status: Int, extraHeaders: Map<String, String> = emptyMap()) {
            writeHead(status, extraHeaders + mapOf("Content-Length" to "0"))
            out.flush()
        }

        /** Starts a fixed-length response and returns the stream for the body. */
        fun stream(status: Int, contentLength: Long, extraHeaders: Map<String, String> = emptyMap()): OutputStream {
            writeHead(status, extraHeaders + mapOf("Content-Length" to contentLength.toString()))
            out.flush()
            return out
        }

        /** Starts a chunked (unknown length) response. */
        fun streamChunked(status: Int, extraHeaders: Map<String, String> = emptyMap()): OutputStream {
            writeHead(status, extraHeaders + mapOf("Transfer-Encoding" to "chunked"))
            out.flush()
            return ChunkedOutputStream(out)
        }

        private fun writeHead(status: Int, headers: Map<String, String>) {
            if (headWritten) return
            headWritten = true
            val builder = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
            for ((key, value) in headers) {
                builder.append(key).append(": ").append(value).append("\r\n")
            }
            builder.append("Connection: close\r\n\r\n")
            out.write(builder.toString().toByteArray(StandardCharsets.ISO_8859_1))
        }

        fun flush() {
            runCatching { out.flush() }
        }

        companion object {
            fun reason(status: Int): String = when (status) {
                200 -> "OK"
                206 -> "Partial Content"
                302 -> "Found"
                403 -> "Forbidden"
                404 -> "Not Found"
                416 -> "Range Not Satisfiable"
                500 -> "Internal Server Error"
                503 -> "Service Unavailable"
                else -> "Status"
            }
        }
    }

    private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            out.write((Integer.toHexString(len) + "\r\n").toByteArray())
            out.write(b, off, len)
            out.write("\r\n".toByteArray())
            out.flush()
        }

        override fun flush() = out.flush()

        override fun close() {
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
        }
    }
}
