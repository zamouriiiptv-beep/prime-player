package com.castivio.playback.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.castivio.playback.api.HostLookup
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * **Temporary, for development.** The two things a record of an open's tries needs that the player does not hand over:
 * a name for what went wrong that is safe to show, and the host's address families.
 *
 * Both are used only by a measuring build, and neither runs on the path an opening takes: the name is made after a
 * failure has already been reported, and the lookup is made on a thread of its own after the server has said
 * something, or a try has failed.
 */
internal object OpenAttemptProbes {

    /** Deep enough for any real chain, shallow enough that a cycle cannot hang it. */
    private const val MAX_CAUSES = 12

    /**
     * One low-priority thread that nothing else uses, made when the first lookup is asked for and never stopped. The
     * lookup can wait on the resolver for seconds, which is fine here and would not be anywhere an opening is.
     */
    private val thread: Executor by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "castivio-open-probe").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    /** Looks the host of [url] up on the probe's thread and hands what it finds to [found]. Returns at once. */
    fun lookUpHostLater(url: String, found: (HostLookup) -> Unit) {
        runCatching { thread.execute { found(lookUpHost(url)) } }
    }

    /**
     * How many IPv4 and IPv6 addresses the host of [url] has, from the resolver, after the open said something.
     *
     * The resolver answers from its cache for a host the open has just used, so this is what the open saw, not a new
     * question to the network -- and not a figure for how long the open's own lookup took, which it is not. No address
     * and no host name is kept: only the two counts, or the name of the exception that stopped the lookup.
     */
    fun lookUpHost(url: String): HostLookup {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return HostLookup(failure = "no host")
        return try {
            val found = InetAddress.getAllByName(host)
            HostLookup(
                ipv4 = found.count { it is Inet4Address },
                ipv6 = found.count { it is Inet6Address },
            )
        } catch (e: Exception) {
            HostLookup(failure = nameOf(e))
        }
    }

    /**
     * What stopped a try, for the panel: the status the server answered with when it answered one, otherwise the name
     * of the exception at the bottom of the chain -- `SocketTimeoutException`, `ConnectException`,
     * `UnknownHostException` -- which is the one that says what the network did.
     *
     * **Never a message.** The text of a network exception carries the address it could not reach, and this is shown
     * to others.
     */
    @OptIn(UnstableApi::class)
    fun describeFailure(error: Throwable): String {
        val chain = ArrayList<Throwable>(MAX_CAUSES)
        var next: Throwable? = error
        while (next != null && chain.size < MAX_CAUSES) {
            chain += next
            val cause = next.cause
            next = if (cause === next) null else cause
        }
        val status = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        return if (status != null) "HTTP $status" else nameOf(chain.last())
    }

    private fun nameOf(error: Throwable): String =
        error.javaClass.simpleName.ifEmpty { error.javaClass.name.substringAfterLast('.') }
}
