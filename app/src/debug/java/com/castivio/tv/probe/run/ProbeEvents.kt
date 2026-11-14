package com.castivio.tv.probe.run

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What the client's events say about one call. */
class CallTrace {
    @Volatile
    var requestEndNs: Long = -1L

    @Volatile
    var headersStartNs: Long = -1L

    /** The connection that carried the call, numbered from 1 within the run; -1 until one is acquired. */
    @Volatile
    var connection: Int = -1

    @Volatile
    var protocol: String? = null

    /** From the request being sent to the first byte of the answer: the provider thinking. */
    fun firstByteMs(): Long? {
        val sent = requestEndNs
        val back = headersStartNs
        return if (sent >= 0L && back >= sent) (back - sent) / NANOS_PER_MS else null
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/**
 * The probe's listener for one run: what each call did, which connection carried it, and how many sockets were
 * opened in all.
 *
 * It is told what happened and can change nothing -- OkHttp's listener has nowhere to do so -- and it keeps no
 * address: the remote end of a connection is counted, never stored for printing.
 */
class ProbeEvents(private val clock: () -> Long) {

    /** Sockets opened: every `connectStart`. */
    val connects = AtomicInteger()

    private val connectionIndex = ConcurrentHashMap<Connection, Int>()
    private val nextConnection = AtomicInteger()
    private val endpoints: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val traces = ConcurrentHashMap<Call, CallTrace>()

    /** How many different remote ends were connected to. */
    val remoteEndpoints: Int get() = endpoints.size

    val factory: EventListener.Factory = object : EventListener.Factory {
        override fun create(call: Call): EventListener {
            val trace = CallTrace()
            traces[call] = trace
            return Listener(trace)
        }
    }

    fun trace(call: Call): CallTrace = traces[call] ?: CallTrace()

    private fun indexOf(connection: Connection): Int {
        val known = connectionIndex[connection]
        if (known != null) return known
        val candidate = nextConnection.incrementAndGet()
        return connectionIndex.putIfAbsent(connection, candidate) ?: candidate
    }

    private inner class Listener(private val trace: CallTrace) : EventListener() {

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connects.incrementAndGet()
            endpoints.add(inetSocketAddress.toString())
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            trace.connection = indexOf(connection)
            trace.protocol = connection.protocol().toString()
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            trace.requestEndNs = clock()
        }

        override fun responseHeadersStart(call: Call) {
            trace.headersStartNs = clock()
        }
    }
}
