package com.castivio.tv.probe.run

import com.castivio.data.parsing.XtreamParser
import com.castivio.domain.MediaKind
import com.castivio.tv.probe.core.Fetch
import com.castivio.tv.probe.core.FilmEntries
import com.castivio.tv.probe.core.FilmFieldScanner
import com.castivio.tv.probe.core.OneReplyScan
import com.castivio.tv.probe.core.ProbeConfig
import com.castivio.tv.probe.core.ProbeEnvironment
import com.castivio.tv.probe.core.RequestKind
import com.castivio.tv.probe.core.RunExecutor
import com.castivio.tv.probe.core.RunOutcome
import com.castivio.tv.probe.core.RunRequest
import com.castivio.tv.probe.core.StepPurpose
import com.castivio.tv.probe.core.StringPool
import com.castivio.tv.probe.core.WalkScan
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs one step of the plan against the provider, over the network only.
 *
 * ## What a run does
 *
 *  - **K1:** the categories list and the one reply, asked together, as the importer asks them.
 *  - **K3, K4, K8, W1:** the categories list, then `get_vod_streams` for each listed category, in the provider's
 *    order, drawn by the workers from one queue.
 *
 * A timed run reads each body into a buffer that is thrown away and counts the bytes: nothing is parsed and nothing
 * is kept. A verification run reads `stream_id` and `category_id` from every film and nothing else, and its time is
 * not used. Either way nothing is written anywhere: no database, no preference, no file.
 *
 * ## What it does not touch
 *
 * It is given the account and builds its own client; it calls none of the application's importers, writers or
 * counters. See [ProbeClient].
 */
class HttpRunExecutor(
    private val credentials: ProbeCredentials,
    private val environment: ProbeEnvironment,
    private val config: ProbeConfig,
    private val newClient: (EventListener.Factory) -> OkHttpClient = { listener -> ProbeClient.create(listener) },
    private val clock: () -> Long = { System.nanoTime() },
) : RunExecutor {

    override fun execute(request: RunRequest): RunOutcome {
        val events = ProbeEvents(clock)
        val client = newClient(events.factory)
        val run = RunState(request, events, client, clock, config.bucketMs)
        val monitor = RunMonitor(run, environment, config)
        val rxBefore = environment.uidRxBytes()
        val networkBefore = environment.network()
        monitor.start(networkBefore)
        var scanned: Scanned? = null
        try {
            scanned = when (request.step.arm.fetch) {
                Fetch.ONE_REPLY -> oneReply(run)
                Fetch.BY_CATEGORY -> byCategory(run)
            }
        } catch (e: Exception) {
            run.unexpected = e.javaClass.simpleName
        } finally {
            monitor.stop()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        val rxAfter = environment.uidRxBytes()
        return run.outcome(scanned, rxBefore, rxAfter, networkBefore, environment.network(), monitor, config)
    }

    private fun readsFilms(run: RunState): Boolean = run.request.step.purpose != StepPurpose.TIMED

    private fun oneReply(run: RunState): Scanned {
        val verify = readsFilms(run)
        val pool = Executors.newFixedThreadPool(2, NamedThreads("probe-reply"))
        try {
            val categoriesTask = pool.submit(Callable { fetchCategories(run) })
            val entries = FilmEntries()
            val replyTask = pool.submit(
                Callable {
                    fetchFilms(run, RequestKind.REPLY, null, credentials.wholeReplyUrl(), if (verify) entries else null)
                },
            )
            val listed = categoriesTask.get()
            val reply = replyTask.get()
            val scan = if (verify) OneReplyScan(entries, listed.toSet(), reply.withoutId) else null
            return Scanned(categories = listed, duplicateCategoryIds = 0, reply = scan)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun byCategory(run: RunState): Scanned {
        val verify = readsFilms(run)
        val listed = fetchCategories(run)
        val unique = LinkedHashSet(listed)
        val duplicates = listed.size - unique.size
        if (unique.isEmpty() || run.cancelled) {
            return Scanned(categories = emptyList(), duplicateCategoryIds = duplicates)
        }
        val order = ArrayList(unique)
        val queue = ConcurrentLinkedQueue(order)
        val workers = run.request.step.arm.workers
        val shared = FilmEntries()
        val failed: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
        val withoutId = AtomicInteger()
        val pool = Executors.newFixedThreadPool(workers, NamedThreads("probe-worker"))
        try {
            val tasks = (1..workers).map {
                pool.submit(
                    Callable {
                        while (!run.cancelled) {
                            val id = queue.poll() ?: break
                            val local = if (verify) FilmEntries() else null
                            val result = fetchFilms(run, RequestKind.CATEGORY, id, credentials.categoryUrl(id), local)
                            if (!result.ok) failed.add(id)
                            if (local != null) synchronized(shared) { shared.addAll(local) }
                            withoutId.addAndGet(result.withoutId)
                        }
                    },
                )
            }
            for (task in tasks) task.get()
        } finally {
            pool.shutdownNow()
        }
        val walk = if (verify) WalkScan(shared, order, failed.toSet(), withoutId.get()) else null
        return Scanned(categories = order, duplicateCategoryIds = duplicates, walk = walk)
    }

    /** The categories the panel lists, by the provider's id; empty when the request did not end well. */
    private fun fetchCategories(run: RunState): List<String> {
        val ids = ArrayList<String>()
        val ok = run.fetch(RequestKind.CATEGORIES, null, credentials.categoriesUrl()) { stream ->
            XtreamParser.parseCategories(InputStreamReader(stream, Charsets.UTF_8), MediaKind.MOVIE) { ids.add(it.id) }
            drain(stream)
        }
        return if (ok) ids else emptyList()
    }

    private fun fetchFilms(
        run: RunState,
        kind: RequestKind,
        category: String?,
        url: String,
        into: FilmEntries?,
    ): FilmFetch {
        var withoutId = 0
        val ok = run.fetch(kind, category, url) { stream ->
            if (into == null) {
                drain(stream)
            } else {
                val counts = FilmFieldScanner.scan(InputStreamReader(stream, Charsets.UTF_8), category, StringPool(), into)
                withoutId = counts.withoutId
                drain(stream)
            }
        }
        return FilmFetch(ok, withoutId)
    }

    /** Reads to the end and keeps nothing: the stream counts every byte itself. */
    private fun drain(stream: InputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (stream.read(buffer, 0, buffer.size) >= 0) {
            // counted by the stream
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
