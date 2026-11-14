package com.castivio.tv.probe.support

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A film of the fake panel: its id, its own category, and the other categories whose replies list it too. */
data class FakeFilm(val id: Long, val category: String, val alsoIn: List<String> = emptyList())

/**
 * A small HTTP/1.1 server on the loopback address that answers like an Xtream panel, for the runner's tests.
 *
 * It is written on plain sockets so that a test needs nothing but the JDK, and so that it can say what only a server
 * can: how many requests were being served **at the same moment** ([peakActive]) and how many TCP connections it
 * accepted ([accepted]). Those are the truth a client's own evidence is compared with.
 *
 * Replies are chunked, like the provider's; the list of categories has a length. A reply can be given a delay before
 * its first byte, sent in slow chunks so that it stays open, held until a number of requests are in the handler at
 * once ([rendezvous]), answered with a chosen status, or cut short.
 */
class FakePanel(
    private val categories: List<String>,
    private val films: List<FakeFilm>,
) : AutoCloseable {

    /** Category requests wait here until this many are in the handler at once; the first ones are what is held. */
    @Volatile
    var rendezvous: CountDownLatch? = null

    @Volatile
    var chunks: Int = 1

    @Volatile
    var chunkDelayMs: Long = 0L

    @Volatile
    var firstByteDelayMs: Long = 0L

    /** The status for a request, by key: `categories`, `reply` or `category:<id>`. */
    @Volatile
    var statusFor: (String) -> Int = { 200 }

    /** What the one reply says instead of the films, when it is set. */
    @Volatile
    var replyOverride: String? = null

    /** The one reply declares more bytes than it sends and then closes the connection. */
    @Volatile
    var truncateReply: Boolean = false

    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(true)
    private val active = AtomicInteger()

    val port: Int get() = server.localPort

    /** TCP connections accepted. */
    val accepted = AtomicInteger()

    /** The most requests for films that were in the handler at the same moment. */
    val peakActive = AtomicInteger()

    /** Bytes of body sent, framing and headers not counted. */
    val bodyBytes = AtomicLong()

    /** Every request's key, in the order they arrived. */
    val requests = CopyOnWriteArrayList<String>()

    /** The User-Agent of every request, in the order they arrived; empty for a request that had none. */
    val userAgents = CopyOnWriteArrayList<String>()

    init {
        pool.execute { acceptLoop() }
    }

    override fun close() {
        running.set(false)
        try {
            server.close()
        } catch (ignored: IOException) {
            // closing is the end of it
        }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            accepted.incrementAndGet()
            pool.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                val output = socket.getOutputStream()
                while (running.get()) {
                    val requestLine = input.readLine() ?: return
                    var keepAlive = true
                    var userAgent = ""
                    while (true) {
                        val header = input.readLine() ?: return
                        if (header.isEmpty()) break
                        if (header.startsWith("Connection:", ignoreCase = true) && header.contains("close", ignoreCase = true)) {
                            keepAlive = false
                        }
                        if (header.startsWith("User-Agent:", ignoreCase = true)) {
                            userAgent = header.substringAfter(':').trim()
                        }
                    }
                    userAgents += userAgent
                    val closeAfter = handle(requestLine, output)
                    if (closeAfter || !keepAlive) return
                }
            }
        } catch (ignored: IOException) {
            // a client that went away
        } catch (ignored: InterruptedException) {
            // the server is closing
        }
    }

    /** Answers one request. Returns true when the connection must be closed after it. */
    private fun handle(requestLine: String, output: OutputStream): Boolean {
        val target = requestLine.split(" ").getOrNull(1) ?: return true
        val query = target.substringAfter('?', "")
        val params = HashMap<String, String>()
        for (part in query.split('&')) {
            if (!part.contains('=')) continue
            params[part.substringBefore('=')] = URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }
        val action = params["action"]
        val category = params["category_id"]
        val key = when {
            action == "get_vod_categories" -> "categories"
            category == null -> "reply"
            else -> "category:$category"
        }
        requests += key
        val isFilms = key != "categories"
        if (isFilms) updateMax(peakActive, active.incrementAndGet())
        try {
            val latch = rendezvous
            if (key.startsWith("category:") && latch != null) {
                latch.countDown()
                latch.await(RENDEZVOUS_SECONDS, TimeUnit.SECONDS)
            }
            if (firstByteDelayMs > 0L) Thread.sleep(firstByteDelayMs)
            val status = statusFor(key)
            if (status != 200) {
                writeStatus(output, status)
                return true
            }
            val body = bodyFor(key)
            return when {
                key == "categories" -> {
                    writeFixed(output, body)
                    false
                }
                key == "reply" && truncateReply -> {
                    writeTruncated(output, body)
                    true
                }
                else -> {
                    writeChunked(output, body)
                    false
                }
            }
        } finally {
            if (isFilms) active.decrementAndGet()
        }
    }

    private fun updateMax(peak: AtomicInteger, value: Int) {
        while (true) {
            val current = peak.get()
            if (value <= current || peak.compareAndSet(current, value)) return
        }
    }

    private fun filmJson(film: FakeFilm): String =
        "{\"num\":${film.id},\"name\":\"Film ${film.id}\",\"stream_type\":\"movie\",\"stream_id\":${film.id}," +
            "\"stream_icon\":\"\",\"rating\":\"5\",\"added\":\"1\",\"category_id\":\"${film.category}\"," +
            "\"container_extension\":\"mp4\"}"

    private fun bodyFor(key: String): ByteArray {
        val text = when {
            key == "categories" -> categories.joinToString(",", "[", "]") {
                "{\"category_id\":\"$it\",\"category_name\":\"Category $it\",\"parent_id\":0}"
            }
            key == "reply" -> replyOverride ?: films.joinToString(",", "[", "]") { filmJson(it) }
            else -> {
                val id = key.removePrefix("category:")
                films.filter { it.category == id || id in it.alsoIn }.joinToString(",", "[", "]") { filmJson(it) }
            }
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun writeStatus(output: OutputStream, status: Int) {
        output.write("HTTP/1.1 $status Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    // The count of body bytes is made BEFORE the bytes are written: a test that reads it the moment the client has
    // the whole body must not find it one write behind.
    private fun writeFixed(output: OutputStream, body: ByteArray) {
        bodyBytes.addAndGet(body.size.toLong())
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1),
        )
        output.write(body)
        output.flush()
    }

    private fun writeTruncated(output: OutputStream, body: ByteArray) {
        bodyBytes.addAndGet(body.size.toLong())
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size + TRUNCATED_BY}\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1),
        )
        output.write(body)
        output.flush()
    }

    private fun writeChunked(output: OutputStream, body: ByteArray) {
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1),
        )
        output.flush()
        val pieces = chunks.coerceAtLeast(1)
        val size = (body.size + pieces - 1) / pieces
        var offset = 0
        while (offset < body.size) {
            val end = minOf(body.size, offset + size)
            val length = end - offset
            bodyBytes.addAndGet(length.toLong())
            output.write("${Integer.toHexString(length)}\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write(body, offset, length)
            output.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            offset = end
            if (offset < body.size && chunkDelayMs > 0L) Thread.sleep(chunkDelayMs)
        }
        output.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private companion object {
        const val BACKLOG = 64
        const val SOCKET_TIMEOUT_MS = 20_000
        const val RENDEZVOUS_SECONDS = 10L
        const val TRUNCATED_BY = 1_000
    }
}
