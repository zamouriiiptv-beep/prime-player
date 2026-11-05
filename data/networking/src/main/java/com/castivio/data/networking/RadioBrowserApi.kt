package com.castivio.data.networking

import com.castivio.data.parsing.RadioBrowserParser
import com.castivio.data.parsing.RadioBrowserQuery
import com.castivio.data.parsing.RadioBrowserServerPool
import com.castivio.domain.DirectoryFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.InetAddress
import java.net.UnknownHostException

/** The address of [path] on one of the directory's servers: always over HTTPS. A parameter of the classes below so a test can point them at its own server. */
internal fun httpsUrl(host: String, path: String): String = "https://$host$path"

/**
 * What a request to the world radio directory came to: a value, or why not.
 */
internal sealed interface DirectoryAnswer<out T> {
    data class Ok<out T>(val value: T) : DirectoryAnswer<T>

    data class Failed(val reason: DirectoryFailure) : DirectoryAnswer<Nothing>
}

/**
 * Finds the directory's servers: by their names in DNS, then by asking one of them, then by what is already known.
 *
 * The directory publishes one name that resolves to all its servers; the address of each is turned back into the server's own name, which
 * is the name its certificate is for. Where reverse lookups are not answered, a server is asked for its list of servers. Names that are
 * not the directory's are refused by the pool, not here.
 */
internal class RadioBrowserDiscovery(
    private val http: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val nameOf: (InetAddress) -> String = { it.hostName },
    private val urlOf: (host: String, path: String) -> String = ::httpsUrl,
) {
    /** Every name found, or none: never an exception. Blocking lookups run off the caller's thread. */
    suspend fun discover(seeds: List<String>): List<String> = withContext(io) {
        val byDns = byDns()
        byDns.ifEmpty { byEndpoint(seeds) }
    }

    private fun byDns(): List<String> = try {
        resolve(ROUND_ROBIN).map(nameOf).filter(RadioBrowserServerPool::accepts).map { it.lowercase() }.distinct()
    } catch (ignored: UnknownHostException) {
        emptyList()
    } catch (ignored: SecurityException) {
        emptyList()
    }

    private fun byEndpoint(seeds: List<String>): List<String> {
        for (host in seeds.take(ENDPOINT_TRIES)) {
            try {
                val request = Request.Builder().url(urlOf(host, RadioBrowserQuery.servers())).header("Accept", "application/json").build()
                val names = http.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) return@use emptyList<String>()
                    val found = ArrayList<String>()
                    RadioBrowserParser.parseServers(InputStreamReader(body.byteStream(), Charsets.UTF_8)) { found += it }
                    found
                }
                if (names.isNotEmpty()) return names
            } catch (ignored: IOException) {
                // the next one
            } catch (ignored: RuntimeException) {
                // an answer that is not the directory's: the next one
            }
        }
        return emptyList()
    }

    private companion object {
        const val ROUND_ROBIN = "all.api.radio-browser.info"
        const val ENDPOINT_TRIES = 3
    }
}

/**
 * One request to the directory, on whichever of its servers answers.
 *
 * The names are discovered once -- and again after a day, or after every server has failed -- the pool keeps the one that answered and
 * leaves alone the ones that did not, and a request tries a few before it says it failed. What it says is the reason: no server was
 * reached at all ([DirectoryFailure.OFFLINE]), servers answered with errors ([DirectoryFailure.SERVICE_DOWN]), or one answered with
 * something that is not the directory's ([DirectoryFailure.BAD_ANSWER]).
 */
internal class RadioBrowserApi(
    private val http: OkHttpClient,
    private val pool: RadioBrowserServerPool,
    private val discovery: RadioBrowserDiscovery,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val urlOf: (host: String, path: String) -> String = ::httpsUrl,
) {
    private var discoveredAtMs = NEVER
    private val lock = Any()

    /** Asks for [path] and reads the body with [read] while the connection is open. */
    suspend fun <T> get(path: String, read: (Reader) -> T): DirectoryAnswer<T> {
        discoverIfDue()
        return withContext(io) { ask(path, read) }
    }

    private suspend fun discoverIfDue() {
        val due = synchronized(lock) {
            val now = clock()
            val needed = discoveredAtMs == NEVER || now - discoveredAtMs > REDISCOVER_MS || pool.size() == 0
            if (needed) discoveredAtMs = now
            needed
        }
        if (due) pool.replace(discovery.discover(RadioBrowserServerPool.FALLBACK))
    }

    private fun <T> ask(path: String, read: (Reader) -> T): DirectoryAnswer<T> {
        var answered = false
        var badAnswer = false
        for (host in pool.candidates(MAX_TRIES)) {
            try {
                val request = Request.Builder().url(urlOf(host, path)).header("Accept", "application/json").build()
                val value = http.newCall(request).execute().use { response ->
                    answered = true
                    val body = response.body
                    if (!response.isSuccessful || body == null) return@use null
                    try {
                        Some(read(InputStreamReader(body.byteStream(), Charsets.UTF_8)))
                    } catch (ignored: RuntimeException) {
                        badAnswer = true
                        null
                    }
                }
                if (value != null) {
                    pool.succeeded(host)
                    return DirectoryAnswer.Ok(value.value)
                }
                pool.failed(host)
            } catch (ignored: IOException) {
                pool.failed(host)
            }
        }
        // Every server tried failed: the names are asked for again next time.
        synchronized(lock) { discoveredAtMs = NEVER }
        return DirectoryAnswer.Failed(
            when {
                badAnswer -> DirectoryFailure.BAD_ANSWER
                answered -> DirectoryFailure.SERVICE_DOWN
                else -> DirectoryFailure.OFFLINE
            },
        )
    }

    /** A box, so that a value that is itself null can be told from no value. */
    private class Some<T>(val value: T)

    private companion object {
        const val MAX_TRIES = 3
        const val REDISCOVER_MS = 24 * 60 * 60_000L
        const val NEVER = Long.MIN_VALUE
    }
}
