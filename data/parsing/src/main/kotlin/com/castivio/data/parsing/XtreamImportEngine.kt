package com.castivio.data.parsing

import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.RequestTimes
import java.io.Reader
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.withContext

/**
 * Imports an Xtream catalogue: a section in one reply where the panel serves that, and
 * category by category where it does not or for what the reply left out.
 *
 * This is the single biggest performance win available in the whole app, and it
 * is an API shape rather than an optimisation: Xtream is addressable by category,
 * so a 100,000-channel provider is a few hundred category rows plus whatever the
 * user actually opens. The M3U path has no such option and must stream the entire
 * playlist; here we simply never download it.
 *
 * What that buys, concretely: categories arrive in one small request and the UI is
 * usable immediately. A full catalogue import — every category of every kind — is
 * still supported for users who want offline browsing, and it streams the same
 * way, in bounded batches with nothing accumulated.
 *
 * Series are imported as shells: one row per show, no episodes. Episode lists
 * cost one request each (`get_series_info`), so fetching 600 of them up front
 * would take minutes for data almost none of which gets looked at. [importEpisodes]
 * fills in a single show when the user opens it.
 *
 * A section's streams can also be asked for in one reply — see `wholeSection` on
 * [importCatalogue] — which turns a request per category into one request per section
 * for the panels that answer it, and falls back to this walk for the ones that do not.
 *
 * Blocking, like the writer it feeds. Call it on an IO dispatcher.
 */
class XtreamImportEngine(
    private val writer: CatalogWriter,
    private val batchSize: Int = CatalogImportEngine.DEFAULT_BATCH,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * How many category requests may be in flight at once.
     *
     * Four, and a constructor parameter rather than a constant, so the number can be
     * moved from measurements instead of from opinion. It is deliberately small: the
     * thing on the other end is usually one PHP host serving every customer of the
     * subscription, and the failure mode of getting this wrong is not a slow app but a
     * provider that starts refusing the user.
     */
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    /**
     * The clock a [PassReport] is measured on: monotonic, in nanoseconds. A parameter so a
     * test can step it; nothing the import does depends on what it says.
     */
    private val nanoClock: () -> Long = System::nanoTime,
    /**
     * How long the categories that would not load are left alone before they are asked for
     * again. A parameter so a test does not wait it out; nothing else reads it.
     */
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    init {
        require(batchSize in 1..CatalogImportEngine.MAX_BATCH) { "batchSize $batchSize out of range" }
        require(concurrency in 1..MAX_CONCURRENCY) { "concurrency $concurrency out of range" }
    }

    /**
     * What the engine needs from the network.
     *
     * Deliberately narrow and returning plain [Reader]s: HTTP, credentials and
     * retries live in `:data:networking`, which keeps this engine pure Kotlin and
     * therefore testable and benchmarkable without a device or a server.
     */
    interface Api {
        fun categories(kind: MediaKind): Reader
        fun streams(kind: MediaKind, categoryId: String): Reader
        fun series(categoryId: String): Reader
        fun seriesInfo(seriesId: String): Reader

        /**
         * `get_vod_info` for one film: the details its list row does not carry. Null for a source that
         * offers none, which is every source but Xtream.
         */
        fun vodInfo(vodId: String): Reader? = null

        /** The playable URL for a stream, built by the networking layer. */
        fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String

        /**
         * Every stream of [kind] in one reply — the streams action without a category —
         * or null when this API does not ask that question.
         *
         * Not every panel answers it, and nothing here assumes one does: a refusal is
         * [WholeSectionUnsupported], any other failure is thrown as it is, and the engine
         * falls back to asking category by category either way. Null by default, so an
         * implementation that says nothing is one that is asked per category, as before.
         */
        fun allStreams(kind: MediaKind): Reader? = null
    }

    /** The moments an import passes through, for a caller that owns a clock to time. */
    enum class Phase {
        /** The category list has been read. */
        CATEGORIES,

        /** Every category is written as a group, before any stream. */
        GROUPS,

        /** The first rows are durable, and a count query can see them. */
        FIRST_COMMIT,

        /** Every stream that is coming has arrived. */
        STREAMS,

        /** The writer has finished: prune, counts and search index included. */
        FINISHED,
    }

    /** How one section's streams were fetched. */
    enum class StreamStrategy {
        /** One reply for the whole section, then each category it left empty on its own. */
        WHOLE_SECTION,

        /**
         * The panel refused the one-reply question — an error status, a reply that is not
         * a list, or an empty list — so every category was asked for on its own.
         */
        REFUSED,

        /** It failed partway or before an answer, so every category was asked on its own. */
        FAILED,

        /** Category by category from the start: not asked, or not askable. */
        PER_CATEGORY,
    }

    /**
     * What happened to one section's streams, reported once per section.
     *
     * Counts and a class name, never an address: it is printed, and an Xtream URL carries
     * the user's password.
     */
    data class StreamsReport(
        val kind: MediaKind,
        val strategy: StreamStrategy,
        /** Rows the one reply delivered. Zero when it was not used. */
        val streams: Int = 0,
        /**
         * Streams in that reply naming a category the panel did not list, or none. Written
         * all the same, under no group: they are in the section's "all" and in its search,
         * which is what the subscription holds. The category-by-category walk cannot reach
         * them -- it asks only for the categories the panel listed -- so the one reply is
         * the only way they arrive, and dropping them is how a section came up short of
         * what the panel carries.
         */
        val orphans: Int = 0,
        /**
         * Streams in that reply filed under another section's categories -- the stations in
         * a load of the channels -- and left to that section's own load. Not written.
         */
        val elsewhere: Int = 0,
        /** Categories the reply left empty, then asked for one by one. */
        val gapFilled: Int = 0,
        /** Why the one reply was not used, by class name. */
        val cause: String? = null,
    ) {
        override fun toString(): String = when (strategy) {
            StreamStrategy.WHOLE_SECTION ->
                "$kind: one reply, $streams rows, $orphans under no category" +
                    (if (elsewhere > 0) ", $elsewhere left to another section" else "") +
                    ", $gapFilled categories asked again"
            StreamStrategy.REFUSED -> "$kind: one reply refused (${cause ?: "empty"}), asked per category"
            StreamStrategy.FAILED -> "$kind: one reply failed (${cause ?: "unknown"}), asked per category"
            StreamStrategy.PER_CATEGORY -> "$kind: asked per category"
        }
    }

    /**
     * What one pass over a section's streams cost, measured on both of the sides that run
     * it at the same time. A measurement only: nothing the import does reads it.
     *
     * ## Two sides, one window
     *
     * A pass is readers and a writer working at once. The readers ask, receive and parse;
     * the writer -- this engine's own thread -- writes what they hand over. Each side's
     * figures add up to its own time and never to the other's: the reader's waiting for the
     * save and the writer's writing are the same seconds seen from two threads, and adding
     * them would count those seconds twice.
     *
     *  - **Reader**, summed over every request of the pass: [askNanos] connecting, sending
     *    and waiting for the answer to begin; [readNanos] inside the answer's own reads,
     *    bytes arriving; [handOverNanos] blocked handing a batch to the writer because it
     *    was still busy with the last; and [parseNanos], whatever is left of [readerNanos].
     *    With several requests in flight at once ([parallelReaders]) the sums exceed the
     *    window, as four people's hours exceed the hour they worked together.
     *  - **Writer**, one thread, inside [windowNanos]: [writeNanos] handing rows to the
     *    database, [commitNanos] committing them, [idleNanos] waiting for the next batch,
     *    and [writerOtherNanos] for the bookkeeping between.
     *
     * Counts and category ids only, never a name or an address.
     */
    data class PassReport(
        val kind: MediaKind,
        /** The whole section in one reply, or its categories one by one. */
        val oneReply: Boolean,
        /** How many requests could be in flight at once: one for the reply. */
        val parallelReaders: Int,
        /** Requests the pass made. */
        val requests: Int,
        /** The pass on the wall clock, from its first request to its last row written. */
        val windowNanos: Long,
        val askNanos: Long,
        val readNanos: Long,
        val handOverNanos: Long,
        /** Every request's time from asking to its last batch handed over, summed. */
        val readerNanos: Long,
        val writeNanos: Long,
        val commitNanos: Long,
        val idleNanos: Long,
        /** Streams the readers parsed. */
        val parsed: Int,
        /** Rows handed to the writer: filed under a category, or under none ([orphans]). */
        val filed: Int,
        /** Streams naming a category the panel did not list: written under no group. */
        val orphans: Int,
        /**
         * Those streams by the category id they named, and how many named each; "none"
         * for a stream that named no category at all.
         */
        val orphanCategories: Map<String, Int>,
        /** The categories the pass covered, by the kind each was filed as. */
        val categories: Map<MediaKind, Int>,
        /** Streams of another section's categories in the one reply: parsed, left to that section. */
        val elsewhere: Int = 0,
    ) {
        /** The reader's time that was neither asking, receiving nor waiting: parsing. */
        val parseNanos: Long get() = (readerNanos - askNanos - readNanos - handOverNanos).coerceAtLeast(0)

        /** The writer's time that was neither writing, committing nor waiting. */
        val writerOtherNanos: Long get() = (windowNanos - writeNanos - commitNanos - idleNanos).coerceAtLeast(0)
    }

    /**
     * Imports categories and their contents for [kinds].
     *
     * @param isCancelled checked between categories and between batches — a
     *   category is one request, so this stops within one round trip.
     */
    fun importCatalogue(
        sourceId: String,
        api: Api,
        kinds: Set<MediaKind> = DEFAULT_KINDS,
        onProgress: (ImportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        /**
         * How this write relates to what is already stored.
         *
         * [ImportMode.REPLACE] for a whole catalogue, which is what a refresh is and
         * what the default has always been. [ImportMode.APPEND] when [kinds] is a
         * subset: a section fetched on its own must not prune the sections fetched
         * before it, and a replacing write would do exactly that at `finish()` —
         * every row of another generation, which after a per-kind import means every
         * row of every other kind.
         */
        mode: ImportMode = ImportMode.REPLACE,
        /**
         * The kinds whose streams are first asked for in one reply rather than one
         * category at a time.
         *
         * ## Why one reply
         *
         * Asked category by category, a section costs one round trip per category, and
         * the round trips are what the time goes on: the measured live section was 836
         * requests averaging 0.16s, four at a time, for 18 MB — while another player read
         * the same 18 MB from the same panel in two requests and about seven seconds. The
         * reply is the same list the per-category replies are pieces of, so it streams
         * through the same parser into the same batches, and every row is filed under the
         * category its own `category_id` names.
         *
         * ## Why only a first attempt
         *
         * Nothing guarantees a panel serves it. A refusal, a reply that is not a list, an
         * empty list, or a failure partway, and every category is asked for on its own
         * exactly as before — the rows the reply wrote are rewritten, since ids are stable
         * and the writer replaces. A category the reply left empty is asked for on its own
         * too, so a panel that leaves some out of the big reply loses nothing. A stream
         * whose category the panel does not list is written under no group: the walk cannot
         * reach it, so the reply is the only way it arrives. See [StreamsReport] for what is
         * reported.
         *
         * Empty by default: a caller that does not opt in is asked per category.
         */
        wholeSection: Set<MediaKind> = emptySet(),
        /** Told how each section's streams were fetched. */
        onStreams: (StreamsReport) -> Unit = {},
        /** Told as the import reaches each [Phase]. The engine has no clock; the caller does. */
        onPhase: (Phase) -> Unit = {},
        /** Told what each pass over a section's streams cost. See [PassReport]. */
        onPass: (PassReport) -> Unit = {},
    ): ImportSummary {
        val started = clock()
        val batch = ArrayList<CatalogItem>(batchSize)
        val perKind = IntArray(MediaKind.entries.size)
        var imported = 0
        var groups = 0
        var cancelled = false
        var done = 0
        val failures = ArrayList<Pair<String, Throwable>>()
        // The categories that would not load, kept to be asked for once more, and how many
        // are still missing when that is done: the figure the summary tells.
        val failedPlans = ArrayList<CategoryPlan>()
        var lostCategories = 0
        // Whether a category ending counts on the bar. The second asking does not: those
        // categories were counted when they failed, and counting them again would run the
        // bar past its total.
        var countsOnBar = true
        // Time spent committing, for [PassReport]: a pass reads it before and after.
        var commitNanos = 0L

        // ------------------------------------------------------ the commit policy
        //
        // Rows written into the open transaction but not yet committed, and when the
        // last commit was. Together with [committedOnce] these are the whole of the
        // rule in [commitDue] -- kept as locals rather than fields because they belong
        // to one import and an engine instance is used for exactly one.
        var pending = 0
        var lastCommitAt = started
        var committedOnce = false

        // ------------------------------------------------------ the categories listed
        //
        // A category is listed -- a screen opened now shows it -- once it is complete and
        // its count is committed. Complete categories wait here for the next commit, which
        // counts them first, so each count includes every row that commit makes visible:
        // by id, with whether it brought any rows. [listedIds] are the ones committed with
        // rows, and its size is what a screen waiting for its first category waits on.
        val toCount = LinkedHashMap<String, Boolean>()
        val listedIds = HashSet<String>()

        /** A category is complete: counted at the next commit. */
        fun complete(groupId: String, rows: Boolean) {
            toCount[groupId] = toCount[groupId] == true || rows
        }

        /**
         * Ends the batch's transaction, but only when the policy says so.
         *
         * `force` is the end of the import, where everything written has to become
         * durable whatever the counters say.
         */
        fun commitIfDue(force: Boolean = false) {
            if (pending == 0 && toCount.isEmpty()) return
            val now = clock()
            val due = force ||
                // The first content on the screen, and the reason this is the first
                // clause rather than one of the others: TTFC is measured from the
                // moment the count query can see a row, and that moment is this commit.
                (!committedOnce && pending > 0) ||
                // The first category complete, which is what the section opens with: on
                // the screen now, as the first rows are, rather than half a second later.
                (listedIds.isEmpty() && toCount.containsValue(true)) ||
                pending >= COMMIT_ROWS ||
                now - lastCommitAt >= COMMIT_INTERVAL_MS
            if (!due) return
            val rows = pending > 0
            val commitStarted = nanoClock()
            if (toCount.isNotEmpty()) writer.countGroups(toCount.keys)
            writer.commit()
            commitNanos += nanoClock() - commitStarted
            // The first commit that carried rows: one that only counted categories made
            // nothing new visible.
            if (rows && !committedOnce) {
                onPhase(Phase.FIRST_COMMIT)
                committedOnce = true
            }
            pending = 0
            lastCommitAt = now
            for ((id, hasRows) in toCount) if (hasRows) listedIds += id
            toCount.clear()
        }

        /**
         * One pass of category events, drained onto the writer on this thread.
         *
         * The producers only ever *parse*. The writer is one SQLite transaction and is not
         * thread-safe, so every write happens here, draining the channel — which is also
         * what keeps memory O(batch) rather than O(category): a producer hands batches
         * over as it parses them and blocks when the channel is full, so the most ever in
         * flight is the channel's capacity plus one batch per producer.
         *
         * Shared by the two ways a section's streams are fetched — category by category,
         * and the whole section in one reply — so a batch is written, committed and
         * reported the same way whichever of them produced it.
         *
         * @param countCategories whether this pass finishes categories one at a time. The
         *   one reply does not: every category in it finishes only when the reply does. So
         *   it reports the bar's numerator as unknown and the screen counts rows instead,
         *   as it does for a playlist — see [ImportProgress.Importing.categoriesDone].
         * @param meter where the pass's [PassReport] is measured into. The producers add the
         *   readers' side; this function adds the writer's and the window.
         */
        fun pass(
            countCategories: Boolean,
            meter: PassMeter,
            produce: suspend (SendChannel<CategoryEvent>) -> Unit,
        ) {
            val events = Channel<CategoryEvent>(capacity = concurrency)
            val passStarted = nanoClock()
            val commitsBefore = commitNanos

            fun progress(kind: MediaKind) = onProgress(
                ImportProgress.Importing(
                    imported,
                    groups,
                    kind,
                    if (countCategories) done else null,
                    categoriesListed = listedIds.size,
                ),
            )

            // `flush`, timed: the writer's share of the pass.
            fun write(): Int {
                val writeStarted = nanoClock()
                val written = flush(batch)
                meter.writeNanos += nanoClock() - writeStarted
                return written
            }

            runBlocking {
                val producers = launch {
                    try {
                        produce(events)
                    } finally {
                        events.close()
                    }
                }

                // Every event until the channel closes, as `for (event in events)` took them,
                // with the wait for each one timed: the writer idle, the readers behind it.
                while (true) {
                    val waitStarted = nanoClock()
                    val next = events.receiveCatching()
                    meter.idleNanos += nanoClock() - waitStarted
                    val event = next.getOrNull() ?: break
                    when (event) {
                        is CategoryEvent.Items -> {
                            meter.filed += event.items.size
                            for (item in event.items) {
                                // By the row's own kind: a batch from the one reply carries
                                // stations and channels together.
                                perKind[item.kind.ordinal]++
                                batch.add(item)
                                if (batch.size >= batchSize) {
                                    val written = write()
                                    imported += written
                                    pending += written
                                    commitIfDue()
                                    progress(event.kind)
                                }
                            }
                            // Written as each category arrives, and *committed* on the
                            // policy above rather than here. Both halves matter and
                            // they are no longer the same decision.
                            //
                            // Writing per category is what keeps memory O(batch) and
                            // what lets the first rows be committed the instant they
                            // exist. Committing per category is what made a 495-category
                            // section pay for 495 transactions -- 19,003 rows across
                            // them is a full commit every 38 rows, measured at 23.98s
                            // on a real provider, against 2.39s to first content. The
                            // rows were being made durable 38 at a time for the benefit
                            // of a reader that only needed the first batch.
                            val written = write()
                            imported += written
                            pending += written
                            // The categories the one reply moved past before this batch:
                            // every row of theirs is written now, so they are complete.
                            for (id in event.completed) complete(id, rows = true)
                            commitIfDue()
                            progress(event.kind)
                        }

                        // ----------------------------------------- partial failure
                        //
                        // One category that would not load no longer takes the
                        // catalogue with it. Before this, the whole import threw and
                        // `writer.abort` ended it, so a single bad request after 400
                        // good ones left the user with an activation screen. The
                        // failure is counted and named; the other categories carry on.
                        is CategoryEvent.Failed -> {
                            // A failure ends a category as surely as a success does,
                            // so the bar counts it. One that stalled at 833 of 834
                            // because a single request was refused would be a bar
                            // that lies about being stuck.
                            if (countsOnBar) done++
                            failures.add(event.category to event.cause)
                            failedPlans.add(event.plan)
                            progress(event.kind)
                        }

                        // Reported, not merely counted. Counting it silently would
                        // leave the last category's completion unannounced -- the bar
                        // sitting at 833 of 834 until the whole import ended -- and
                        // an empty category would never move it at all, because a
                        // category with no rows sends no `Items` to report against.
                        //
                        // And it is the moment the category is complete: its rows were
                        // written as they came, so it is counted at the next commit -- at
                        // once, when it is the first -- and a screen open now lists it.
                        is CategoryEvent.Finished -> {
                            if (countsOnBar) done++
                            complete(event.groupId, rows = event.rows > 0)
                            commitIfDue()
                            progress(event.kind)
                        }
                    }
                    if (isCancelled()) {
                        cancelled = true
                        break
                    }
                }

                // The channel is cancelled *before* the producers, and the order is
                // load-bearing rather than tidy. A producer hands batches over with
                // `trySendBlocking`, which parks the OS thread rather than suspending
                // the coroutine — so `producers.cancel()` cannot reach one that is
                // waiting for room, and the `join()` below would wait for a thread that
                // is waiting for a consumer that has already stopped consuming.
                // Cancelling the channel first makes those sends fail, which releases
                // the thread and lets the cancel land.
                events.cancel()
                if (cancelled) producers.cancel()
                producers.join()
            }
            // What the pass completed is counted as it ends, not when the import does: the
            // walk of the categories the one reply left empty can be minutes of requests,
            // and the categories the reply finished are listed in the meantime.
            if (toCount.isNotEmpty()) commitIfDue(force = true)
            meter.windowNanos = nanoClock() - passStarted
            meter.commitNanos = commitNanos - commitsBefore
        }

        writer.begin(sourceId, mode)
        try {
            for (requested in kinds) {
                if (cancelled) break
                // Radio has no Xtream endpoint of its own: stations live in live
                // categories, so they are found by category name below.
                val endpointKind = if (requested == MediaKind.RADIO) MediaKind.LIVE else requested
                if (requested == MediaKind.RADIO && MediaKind.LIVE in kinds) continue

                val categories = api.categories(endpointKind).use { reader ->
                    val list = ArrayList<XtreamCategory>(64)
                    XtreamParser.parseCategories(reader, endpointKind) { list.add(it) }
                    list
                }
                onPhase(Phase.CATEGORIES)

                // Groups are written here, in the provider's own order, before a single
                // stream is fetched — not emitted by the producers as they finish.
                //
                // They arrive out of order otherwise, and `RoomCatalogWriter` numbers
                // them by insertion, so the category rail would come out in a different
                // order after every import. That is the same defect as scrambled channel
                // numbers and it is fixed the same way: order comes from the provider's
                // list, never from whichever reply landed first. Affordable because the
                // category list is one small request already held in memory — the
                // repository's own contract calls it safe to hold.
                val classified = categories.withIndex().map { (index, category) ->
                    // A category named "RADIO" holds stations, not channels; they get
                    // their own kind so no live query has to exclude them.
                    val kind = if (endpointKind == MediaKind.LIVE && MediaClassifier.isRadioLabel(category.name)) {
                        MediaKind.RADIO
                    } else {
                        endpointKind
                    }
                    CategoryPlan(index, category, kind, StableIds.group(sourceId, kind, category.name))
                }
                // **Only the sections asked for.** The live categories carry the stations,
                // but a load of the channels is a load of the channels: it writes no radio
                // group and no station, and a load of the radio walks the radio categories
                // and touches no channel. Each section is fetched, written and marked on its
                // own. Positions stay the provider's, so both read in the provider's order.
                val ordered = classified.filter { it.kind in kinds }
                // The categories left to another section's load: their streams in the one
                // reply are passed over rather than counted as orphans of this one.
                val elsewhere = classified.filter { it.kind !in kinds }.mapTo(HashSet()) { it.category.id }
                for (plan in ordered) {
                    writer.writeGroups(listOf(MediaGroup(plan.groupId, plan.category.name, plan.kind)))
                    groups++
                }
                onPhase(Phase.GROUPS)

                // ------------------------------------------------------- the streams
                //
                // The whole section in one reply first, where the caller asked for it, and
                // category by category for whatever that leaves: everything when the reply
                // was refused or failed, the categories it left empty when it was not.
                // The one reply only for a pass whose own endpoint it is: radio asked for on its
                // own would download every channel in the panel to keep a few hundred stations,
                // so it walks its own categories instead -- a handful of small requests.
                val oneReply = endpointKind in wholeSection && requested == endpointKind
                val perCategory: List<CategoryPlan> = if (oneReply && !isCancelled()) {
                    val importedBefore = imported
                    val perKindBefore = perKind.copyOf()
                    var read: WholeSectionRead = WholeSectionRead.NotOffered
                    val meter = PassMeter(nanoClock)
                    pass(countCategories = false, meter = meter) { events ->
                        read = withContext(Dispatchers.IO) {
                            readWholeSection(sourceId, api, endpointKind, ordered, elsewhere, events, isCancelled, meter)
                        }
                    }
                    val outcome = read
                    // Reported whatever the reply came to, unless it was never asked: a refusal
                    // costs a request too, and the reading should show it.
                    if (outcome !is WholeSectionRead.NotOffered) {
                        onPass(meter.report(endpointKind, oneReply = true, parallelReaders = 1, categories = ordered.byKind()))
                    }
                    // Stopped while reading: nothing to fall back to and nothing to report,
                    // because the import is not going on.
                    if (cancelled || isCancelled()) emptyList() else when (outcome) {
                        is WholeSectionRead.Read -> {
                            val gaps = ordered.filter { !outcome.received[it.index] }
                            // Every category the reply filled is finished; the bar counts
                            // them now and the ones asked for again as they arrive.
                            done += ordered.size - gaps.size
                            // Said now rather than left to the walk of the gaps: with no
                            // gaps there is no walk, and the bar would sit empty through the
                            // commit and the counts that still follow, reading as stuck.
                            onProgress(ImportProgress.Importing(imported, groups, requested, done, categoriesListed = listedIds.size))
                            onStreams(
                                StreamsReport(
                                    kind = endpointKind,
                                    strategy = StreamStrategy.WHOLE_SECTION,
                                    streams = outcome.streams,
                                    orphans = outcome.orphans,
                                    elsewhere = outcome.elsewhere,
                                    gapFilled = gaps.size,
                                ),
                            )
                            gaps
                        }

                        else -> {
                            // Whatever the reply wrote is written again by the walk below --
                            // ids are stable and the writer replaces -- so it is counted once,
                            // by the walk, rather than twice.
                            imported = importedBefore
                            perKindBefore.copyInto(perKind)
                            onStreams(
                                StreamsReport(
                                    kind = endpointKind,
                                    strategy = when (outcome) {
                                        is WholeSectionRead.Refused -> StreamStrategy.REFUSED
                                        is WholeSectionRead.Failed -> StreamStrategy.FAILED
                                        else -> StreamStrategy.PER_CATEGORY
                                    },
                                    cause = outcome.cause?.reportName(),
                                ),
                            )
                            ordered
                        }
                    }
                } else {
                    onStreams(StreamsReport(kind = endpointKind, strategy = StreamStrategy.PER_CATEGORY))
                    ordered
                }

                // ------------------------------------------------ bounded concurrency
                //
                // This was a strictly sequential `for (category in categories)`, one
                // round trip at a time, and on a real subscription that is 497 calls at
                // ~0.4s each before the first row could be read. It is replaced by a
                // fixed number of workers pulling from the same list, never by
                // `async` per category: 497 sockets opened at once is a denial of
                // service aimed at the user's own provider, and the panel it talks to
                // is usually a single PHP host.
                if (perCategory.isNotEmpty() && !cancelled && !isCancelled()) {
                    val meter = PassMeter(nanoClock)
                    pass(countCategories = true, meter = meter) { events ->
                        val gate = Semaphore(concurrency)
                        coroutineScope {
                            for (plan in perCategory) {
                                launch(Dispatchers.IO) {
                                    gate.withPermit {
                                        if (!isCancelled()) {
                                            fetchCategory(sourceId, api, plan, events, meter)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    onPass(
                        meter.report(
                            endpointKind,
                            oneReply = false,
                            parallelReaders = minOf(concurrency, perCategory.size),
                            categories = perCategory.byKind(),
                        ),
                    )
                }

                // ----------------------------------------- the categories that failed
                //
                // Asked for once more, two at a time and after a rest, because a category that
                // failed under four requests at once is usually one a quieter panel answers:
                // the failure is a body that stalled past the read timeout, which no retry at
                // the connection catches, and a film category is tens of thousands of rows. Left
                // alone, every row of it was missing from the library and nothing said so.
                //
                // Not when nothing arrived at all, or when too many failed to be a few unlucky
                // requests: that is a panel that is down or refusing, and asking hundreds of
                // categories again would only spend minutes confirming it. Those failures stand
                // and are counted below, where the import's end can say how many.
                if (failedPlans.isNotEmpty() && imported > 0 && failedPlans.size <= MAX_RETRIED_CATEGORIES &&
                    !cancelled && !isCancelled()
                ) {
                    val again = failedPlans.toList()
                    failedPlans.clear()
                    countsOnBar = false
                    val meter = PassMeter(nanoClock)
                    pass(countCategories = true, meter = meter) { events ->
                        delay(retryDelayMs)
                        val gate = Semaphore(RETRY_CONCURRENCY)
                        coroutineScope {
                            for (plan in again) {
                                launch(Dispatchers.IO) {
                                    gate.withPermit {
                                        if (!isCancelled()) fetchCategory(sourceId, api, plan, events, meter)
                                    }
                                }
                            }
                        }
                    }
                    countsOnBar = true
                }
                // Whatever is still missing is the import's loss, said by the summary.
                lostCategories += failedPlans.size
                failedPlans.clear()

                // Asked again outside the consumer loop, because a run cancelled before
                // any event arrived would otherwise finish and report itself successful:
                // the only place `cancelled` was set is inside a loop that never ran.
                if (isCancelled()) cancelled = true
            }
            onPhase(Phase.STREAMS)

            val tail = flush(batch)
            imported += tail
            pending += tail
            // Forced: everything written has to be durable before the summary claims it
            // was imported. This is what the old unconditional commit in `flush` did at
            // this point, kept explicitly so the guarantee does not depend on `finish`
            // happening to commit -- which is a property of one writer, not of the
            // interface.
            commitIfDue(force = true)

            // Partial failure survives; total failure does not pretend to be success.
            // If not one category yielded a row and at least one of them failed, the
            // honest outcome is that first failure -- reported with its own cause, so
            // the caller still shows "timed out" or "unreachable" rather than the
            // "your provider carries nothing" that an empty success would produce.
            if (imported == 0 && failures.isNotEmpty() && !cancelled) throw failures.first().second

            val summary = ImportSummary(
                sourceId = sourceId,
                items = imported,
                groups = groups,
                skipped = 0,
                byKind = perKind.toKindMap(),
                durationMs = clock() - started,
                cancelled = cancelled,
                failedCategories = lostCategories,
            )
            if (cancelled) {
                writer.abort(null)
            } else {
                writer.finish(summary)
                onPhase(Phase.FINISHED)
                onProgress(ImportProgress.Done(imported, summary.durationMs))
            }
            return summary
        } catch (t: Throwable) {
            writer.abort(t)
            throw t
        }
    }

    /**
     * Fills in one show's episodes, on demand.
     *
     * Called when the user opens a series. The rows carry the same `seriesId` as
     * the shell written during the catalogue import, so the season list simply
     * appears — no reconciliation, no second identity scheme.
     */
    fun importEpisodes(
        sourceId: String,
        api: Api,
        providerSeriesId: String,
        seriesTitle: String,
        groupId: String?,
        onDetails: (XtreamSeriesInfo?, Map<String, String>) -> Unit = { _, _ -> },
        /** Told what the request cost, once it is kept: where the wait for a show's page went. */
        onTimes: (RequestTimes) -> Unit = {},
    ): Int {
        val started = clock()
        val seriesId = StableIds.seriesByProviderId(sourceId, providerSeriesId)
        val batch = ArrayList<CatalogItem>(64)
        var order = 0
        var written = 0
        // What the provider says of the show, and the plot of each episode by the row it was written as.
        // A show has hundreds of episodes at most, so these are small beside a catalogue's batch.
        var info: XtreamSeriesInfo? = null
        val plots = HashMap<String, String>()

        // APPEND, emphatically: a REPLACE write here would prune every row that
        // is not one of this show's episodes — the entire library.
        val openStarted = nanoClock()
        writer.begin(sourceId, ImportMode.APPEND)
        val openNanos = nanoClock() - openStarted
        try {
            val askStarted = nanoClock()
            val reply = api.seriesInfo(providerSeriesId)
            val askNanos = nanoClock() - askStarted
            val readStarted = nanoClock()
            reply.use { reader ->
                XtreamParser.parseSeriesInfo(reader, onInfo = { info = it }) { episode ->
                    val rowId = StableIds.item(sourceId, "episode:${episode.episodeId}")
                    episode.plot?.let { plots[rowId] = it }
                    batch.add(
                        CatalogItem(
                            id = rowId,
                            sourceId = sourceId,
                            kind = MediaKind.SERIES,
                            title = episode.title,
                            streamUrl = api.streamUrl(
                                MediaKind.SERIES,
                                episode.episodeId,
                                episode.containerExtension,
                            ),
                            artworkUrl = episode.coverUrl,
                            groupId = groupId,
                            providerRef = episode.episodeId,
                            providerOrder = order++,
                            durationSeconds = episode.durationSeconds,
                            seriesId = seriesId,
                            seriesTitle = seriesTitle,
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.episodeNumber,
                        ),
                    )
                    if (batch.size >= batchSize) written += flush(batch)
                }
            }
            val readNanos = nanoClock() - readStarted
            val saveStarted = nanoClock()
            written += flush(batch)
            writer.finish(
                ImportSummary(
                    sourceId = sourceId,
                    items = written,
                    groups = 0,
                    skipped = 0,
                    byKind = mapOf(MediaKind.SERIES to written),
                    durationMs = clock() - started,
                ),
            )
            val saveNanos = nanoClock() - saveStarted
            onDetails(info, plots)
            onTimes(RequestTimes(askNanos.ms(), readNanos.ms(), saveNanos.ms(), openNanos.ms()))
            return written
        } catch (t: Throwable) {
            writer.abort(t)
            throw t
        }
    }

    private fun item(
        sourceId: String,
        api: Api,
        kind: MediaKind,
        stream: XtreamStream,
        groupId: String?,
        order: Int,
    ) = CatalogItem(
        // Keyed on the provider's stream id, not the URL: Xtream URLs embed
        // credentials, so a password change would otherwise re-key the catalogue
        // and orphan every favourite.
        id = StableIds.item(sourceId, "stream:${stream.streamId}"),
        sourceId = sourceId,
        kind = kind,
        title = stream.name,
        streamUrl = api.streamUrl(kind, stream.streamId, stream.containerExtension),
        artworkUrl = stream.iconUrl,
        groupId = groupId,
        epgChannelId = stream.epgChannelId,
        // What `get_short_epg` and catch-up are addressed by.
        providerRef = stream.streamId,
        providerOrder = stream.number ?: order,
        durationSeconds = null,
    )

    /**
     * A show with no episodes yet.
     *
     * `episodeCount` in the Series screen counts rows that have an episode number,
     * so a shell reads as "no episodes loaded" rather than "one episode".
     */
    private fun seriesShell(
        sourceId: String,
        series: XtreamSeries,
        groupId: String?,
        order: Int,
    ) = CatalogItem(
        id = StableIds.item(sourceId, "series:${series.seriesId}"),
        sourceId = sourceId,
        kind = MediaKind.SERIES,
        title = series.name,
        // Not playable, and not meant to be: opening a show navigates to its
        // seasons, which is what triggers the episode import.
        streamUrl = "",
        artworkUrl = series.coverUrl,
        groupId = groupId,
        providerRef = series.seriesId,
        providerOrder = order,
        seriesId = StableIds.seriesByProviderId(sourceId, series.seriesId),
        seriesTitle = series.name,
        seasonNumber = null,
        episodeNumber = null,
    )

    /**
     * One category, fetched and parsed off the writer's thread.
     *
     * Everything here is read-only with respect to the database: the worker produces
     * [CategoryEvent]s and the single consumer performs every write. Batches are handed
     * over as they fill rather than at the end, so a category with 20,000 channels never
     * exists in memory as 20,000 objects — the channel's backpressure blocks the worker
     * instead, which is the same O(batch) guarantee the sequential loop gave.
     *
     * A failure is *reported*, never thrown. That is the whole of requirement 5: this
     * used to propagate out of the loop into `writer.abort` and end the import, so one
     * unlucky request discarded every category that had already succeeded.
     */
    private suspend fun fetchCategory(
        sourceId: String,
        api: Api,
        plan: CategoryPlan,
        events: kotlinx.coroutines.channels.SendChannel<CategoryEvent>,
        meter: PassMeter,
    ) {
        val readerStarted = nanoClock()
        try {
            fetchCategoryMeasured(sourceId, api, plan, events, meter)
        } finally {
            meter.readerNanos.addAndGet(nanoClock() - readerStarted)
        }
    }

    /** [fetchCategory]'s work, with the asking, the reading and the handing over timed. */
    private suspend fun fetchCategoryMeasured(
        sourceId: String,
        api: Api,
        plan: CategoryPlan,
        events: kotlinx.coroutines.channels.SendChannel<CategoryEvent>,
        meter: PassMeter,
    ) {
        val kind = plan.kind
        val groupId = plan.groupId
        val category = plan.category

        // A batch handed to the writer, the wait for room timed: the reader held up by it.
        fun hand(items: List<CatalogItem>) {
            val handStarted = nanoClock()
            events.trySendBlocking(CategoryEvent.Items(kind, items))
            meter.handOverNanos.addAndGet(nanoClock() - handStarted)
        }

        try {
            // Provider order, preserved under concurrency.
            //
            // The sequential loop used one counter incremented across the whole import,
            // which is the provider's own ordering and — for live television — the
            // channel numbering every remote navigates by. Categories now finish out of
            // order, so a shared counter would shuffle the channel list differently on
            // every import. The position is derived from the category's index instead,
            // which gives the same ordering the sequential loop produced and gives it
            // deterministically.
            val first = plan.index * CATEGORY_STRIDE
            var position = first
            val batch = ArrayList<CatalogItem>(batchSize)

            when (kind) {
                MediaKind.SERIES -> meter.ask { api.series(category.id) }.use { raw ->
                    XtreamParser.parseSeries(TimedReader(raw, nanoClock, meter.readNanos)) { series ->
                        batch.add(seriesShell(sourceId, series, groupId, position++))
                        if (batch.size >= batchSize) {
                            hand(ArrayList(batch))
                            batch.clear()
                        }
                    }
                }

                else -> meter.ask { api.streams(kind, category.id) }.use { raw ->
                    XtreamParser.parseStreams(TimedReader(raw, nanoClock, meter.readNanos)) { stream ->
                        batch.add(item(sourceId, api, kind, stream, groupId, position++))
                        if (batch.size >= batchSize) {
                            hand(ArrayList(batch))
                            batch.clear()
                        }
                    }
                }
            }
            val lastStarted = nanoClock()
            if (batch.isNotEmpty()) events.send(CategoryEvent.Items(kind, batch))
            events.send(CategoryEvent.Finished(kind, groupId, rows = position - first))
            meter.handOverNanos.addAndGet(nanoClock() - lastStarted)
        } catch (t: Throwable) {
            // Rethrown only for cancellation, which is not a failure of the category:
            // swallowing it would leave a worker running after the import was stopped.
            if (t is kotlinx.coroutines.CancellationException) throw t
            events.send(CategoryEvent.Failed(kind, category.name, t, plan))
        }
    }

    /**
     * Every stream of [kind] from one reply, filed under the category each names.
     *
     * Read off the writer's thread like a category is, and handed over in the same
     * batches through the same channel. A row's position is its category's index times
     * the stride plus its place among that category's rows in the reply — the same
     * shape [fetchCategory] gives it — and where the panel numbers its channels, the
     * number wins either way, so the order a viewer sees does not depend on which of the
     * two fetched it.
     *
     * ## When a category in the reply is complete
     *
     * When the reply moves on to another category: panels list a category's streams one
     * after another, so the category the reply leaves has sent everything it has. It is
     * handed over as complete with the batch that carries its last rows, and the one the
     * reply is in when it ends with the last batch. A panel that scatters a category over
     * the reply has it complete early and counted again each time the reply leaves it
     * again, so it is listed sooner and its count grows; it is never taken away.
     *
     * Never throws for the panel's sake: what went wrong is the result, so the caller
     * can fall back. Only cancellation is thrown, because that is not a failure of the
     * reply.
     */
    private fun readWholeSection(
        sourceId: String,
        api: Api,
        kind: MediaKind,
        plans: List<CategoryPlan>,
        elsewhere: Set<String>,
        events: SendChannel<CategoryEvent>,
        isCancelled: () -> Boolean,
        meter: PassMeter,
    ): WholeSectionRead {
        val readerStarted = nanoClock()
        try {
            return readWholeSectionMeasured(sourceId, api, kind, plans, elsewhere, events, isCancelled, meter)
        } finally {
            meter.readerNanos.addAndGet(nanoClock() - readerStarted)
        }
    }

    /** [readWholeSection]'s work, with the asking, the reading and the handing over timed. */
    private fun readWholeSectionMeasured(
        sourceId: String,
        api: Api,
        kind: MediaKind,
        plans: List<CategoryPlan>,
        elsewhere: Set<String>,
        events: SendChannel<CategoryEvent>,
        isCancelled: () -> Boolean,
        meter: PassMeter,
    ): WholeSectionRead {
        val byId = HashMap<String, CategoryPlan>(plans.size * 2)
        for (plan in plans) byId[plan.category.id] = plan
        // Indexed by the provider's position, which another section's categories leave gaps in.
        val slots = (plans.maxOfOrNull { it.index } ?: -1) + 1
        val received = BooleanArray(slots)
        val positions = IntArray(slots)
        var streams = 0
        var orphans = 0
        var passedOver = 0
        // Where the streams of no listed category sort: after every category's, in the order
        // the reply gave them. A panel that numbers its streams overrides it either way.
        val orphanFirst = slots * CATEGORY_STRIDE

        val reader = try {
            // Null is an API that cannot ask, which made no request: not counted as one.
            meter.ask { api.allStreams(kind) } ?: run {
                meter.requests.decrementAndGet()
                return WholeSectionRead.NotOffered
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: WholeSectionUnsupported) {
            return WholeSectionRead.Refused(e)
        } catch (e: Exception) {
            return WholeSectionRead.Failed(e)
        }

        return try {
            reader.use {
                val batch = ArrayList<CatalogItem>(batchSize)
                // The category the reply is in, and the ones it has left since the last
                // batch was handed over: see "When a category in the reply is complete".
                var current: CategoryPlan? = null
                val completed = LinkedHashSet<String>()

                /**
                 * One entry of the reply, filed: under the category it names, in the place that
                 * category gives it, or -- when no listed category is its own -- under no group.
                 * [build] makes the row from the three things only the filing knows: its kind, its
                 * group and its place. A channel, a film and a show differ in how they are read and
                 * built, and in nothing else about where they go.
                 */
                fun file(categoryId: String?, build: (MediaKind, String?, Int) -> CatalogItem) {
                    meter.parsed++
                    val plan = categoryId?.let(byId::get)
                    if (plan == null) {
                        if (categoryId?.let(elsewhere::contains) == true) {
                            // Another section's, and that section's own load fetches it.
                            passedOver++
                            meter.elsewhere++
                        } else {
                            // Written under no group rather than dropped. The category walk
                            // never asks for such an entry, so the one reply is the only way
                            // it arrives; leaving it out is how a library came up short of
                            // the count the panel carries. It is in the section's "all" and
                            // in search, and in no category's count.
                            meter.orphan(categoryId)
                            batch.add(build(kind, null, orphanFirst + orphans))
                            orphans++
                            if (batch.size >= batchSize) handOver(events, kind, batch, isCancelled, meter, completed)
                        }
                        return
                    }
                    if (plan.index != current?.index) {
                        current?.let { completed += it.groupId }
                        current = plan
                    }
                    received[plan.index] = true
                    val position = plan.index * CATEGORY_STRIDE + positions[plan.index]++
                    batch.add(build(plan.kind, plan.groupId, position))
                    streams++
                    if (batch.size >= batchSize) handOver(events, plan.kind, batch, isCancelled, meter, completed)
                }

                val timed = TimedReader(reader, nanoClock, meter.readNanos)
                if (kind == MediaKind.SERIES) {
                    XtreamParser.parseSeries(timed) { show ->
                        file(show.categoryId) { _, groupId, order -> seriesShell(sourceId, show, groupId, order) }
                    }
                } else {
                    XtreamParser.parseStreams(timed) { stream ->
                        file(stream.categoryId) { rowKind, groupId, order -> item(sourceId, api, rowKind, stream, groupId, order) }
                    }
                }
                current?.let { completed += it.groupId }
                if (batch.isNotEmpty() || completed.isNotEmpty()) {
                    handOver(events, batch.lastOrNull()?.kind ?: kind, batch, isCancelled, meter, completed)
                }
            }
            // An empty list is a refusal in the shape of an answer, and every category has to be
            // asked for on its own. A list naming none of the panel's categories is not one any
            // more: its streams were written under no group, so it was read, and the walk of the
            // categories it filled nothing in puts each stream under its own -- the ids are the
            // same, and the writer replaces.
            if (streams == 0 && orphans == 0) WholeSectionRead.Refused(null) else WholeSectionRead.Read(streams, orphans, passedOver, received)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: JsonFormatException) {
            // Not a list at all — an object, a page of HTML — before a single row is a
            // refusal; partway through a list it is a broken reply.
            if (streams == 0 && orphans == 0) WholeSectionRead.Refused(e) else WholeSectionRead.Failed(e)
        } catch (e: Exception) {
            WholeSectionRead.Failed(e)
        }
    }

    /**
     * Passes a batch to the writer's thread, or stops the read when nobody is consuming.
     *
     * `trySendBlocking` parks this thread while the channel is full, which is the
     * backpressure; a closed channel means the import stopped, and a reply of tens of
     * megabytes must not be read to its end for a consumer that has already gone.
     *
     * The categories the reply has left go with the batch, and are emptied with it: every
     * row of theirs is in this batch or an earlier one, so once the writer has written it
     * they are complete.
     */
    private fun handOver(
        events: SendChannel<CategoryEvent>,
        kind: MediaKind,
        batch: MutableList<CatalogItem>,
        isCancelled: () -> Boolean,
        meter: PassMeter,
        completed: MutableSet<String>,
    ) {
        if (isCancelled()) throw kotlinx.coroutines.CancellationException("the import stopped consuming")
        // The wait for room, timed: the reader held up by the writer.
        val handStarted = nanoClock()
        val sent = events.trySendBlocking(CategoryEvent.Items(kind, ArrayList(batch), completed.toList()))
        meter.handOverNanos.addAndGet(nanoClock() - handStarted)
        if (sent.isClosed) throw kotlinx.coroutines.CancellationException("the import stopped consuming")
        batch.clear()
        completed.clear()
    }

    /**
     * A pass's measurements while it runs: the readers' side added from as many threads as
     * there are requests in flight, the writer's from the engine's own. Read once, into a
     * [PassReport], when the pass has ended and every reader has returned.
     */
    private class PassMeter(private val clock: () -> Long) {
        val askNanos = AtomicLong()
        val readNanos = AtomicLong()
        val handOverNanos = AtomicLong()
        val readerNanos = AtomicLong()
        val requests = AtomicInteger()

        // The one reply's reader only: one thread, read after it has returned.
        var parsed = 0
        var orphans = 0
        var elsewhere = 0
        private val orphanCategories = LinkedHashMap<String, Int>()

        // The writer's side: the engine's own thread.
        var windowNanos = 0L
        var writeNanos = 0L
        var commitNanos = 0L
        var idleNanos = 0L
        var filed = 0

        /** A request made and timed until its answer begins. */
        inline fun <T> ask(open: () -> T): T {
            requests.incrementAndGet()
            val started = now()
            try {
                return open()
            } finally {
                askNanos.addAndGet(now() - started)
            }
        }

        fun now(): Long = clock()

        /** A stream filed under no category the panel listed, by the id it named. */
        fun orphan(categoryId: String?) {
            orphans++
            val key = categoryId?.takeIf { it.isNotBlank() } ?: NO_CATEGORY
            // Bounded: a reply could name any number of ids, and only counts are wanted.
            val counted = if (key in orphanCategories || orphanCategories.size < MAX_ORPHAN_IDS) key else OTHER_IDS
            orphanCategories[counted] = (orphanCategories[counted] ?: 0) + 1
        }

        fun report(kind: MediaKind, oneReply: Boolean, parallelReaders: Int, categories: Map<MediaKind, Int>) =
            PassReport(
                kind = kind,
                oneReply = oneReply,
                parallelReaders = parallelReaders,
                requests = requests.get(),
                windowNanos = windowNanos,
                askNanos = askNanos.get(),
                readNanos = readNanos.get(),
                handOverNanos = handOverNanos.get(),
                readerNanos = readerNanos.get(),
                writeNanos = writeNanos,
                commitNanos = commitNanos,
                idleNanos = idleNanos,
                // A category's streams are all filed under it: nothing parsed is left out.
                parsed = if (oneReply) parsed else filed,
                filed = filed,
                orphans = orphans,
                orphanCategories = LinkedHashMap(orphanCategories),
                categories = categories,
                elsewhere = elsewhere,
            )
    }

    /** A reader that adds the time spent inside its reads to [into]: the bytes arriving. */
    private class TimedReader(
        private val delegate: Reader,
        private val clock: () -> Long,
        private val into: AtomicLong,
    ) : Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            val started = clock()
            try {
                return delegate.read(cbuf, off, len)
            } finally {
                into.addAndGet(clock() - started)
            }
        }

        override fun close() = delegate.close()
    }

    private fun List<CategoryPlan>.byKind(): Map<MediaKind, Int> = groupingBy { it.kind }.eachCount()

    /**
     * The reason a report names: a refusal by what the panel answered — `HTTP 404` —
     * and anything else by its class. Never another exception's message, which may quote
     * the address the request went to, and that address carries the password.
     */
    private fun Throwable.reportName(): String =
        (this as? WholeSectionUnsupported)?.message ?: javaClass.simpleName

    /** What the one reply came to. */
    private sealed interface WholeSectionRead {
        val cause: Throwable? get() = null

        /** This API cannot ask the question. */
        data object NotOffered : WholeSectionRead

        /** Read to its end; [received] is which categories it filled, by plan index. */
        class Read(val streams: Int, val orphans: Int, val elsewhere: Int, val received: BooleanArray) : WholeSectionRead

        /** The panel said no, in so many words or in an empty answer. */
        class Refused(override val cause: Throwable?) : WholeSectionRead

        /** No answer, or a broken one. Not a verdict on the panel. */
        class Failed(override val cause: Throwable) : WholeSectionRead
    }

    /** One category, with everything decided about it before any request is made. */
    private data class CategoryPlan(
        val index: Int,
        val category: XtreamCategory,
        val kind: MediaKind,
        val groupId: String,
    )

    /**
     * What a worker hands to the writer's thread.
     *
     * Deliberately three cases and no "finished": the consumer stops when the channel
     * closes, which happens exactly once, after every worker has returned.
     */
    private sealed interface CategoryEvent {
        /**
         * @param completed categories that are complete once these rows are written: the
         *   ones the one reply moved past before this batch. Empty from the category walk,
         *   which says it with [Finished].
         */
        data class Items(
            val kind: MediaKind,
            val items: List<CatalogItem>,
            val completed: List<String> = emptyList(),
        ) : CategoryEvent
        /** [plan] is carried so the category can be asked for again once the rest has loaded. */
        data class Failed(
            val kind: MediaKind,
            val category: String,
            val cause: Throwable,
            val plan: CategoryPlan,
        ) : CategoryEvent

        /**
         * One category is done, having sent everything it had.
         *
         * Separate from [Items] because a category sends as many `Items` as it has
         * batches and exactly one of these, which is what makes it countable. The
         * loading screen's `120 / 834` is this counter over `groups`, and without it
         * the only honest bar would be an indeterminate one.
         *
         * It carries the kind for the same reason [Failed] now does: finishing a
         * category is a progress event, and `ImportProgress.Importing` names a kind.
         *
         * And the category, with how many rows it sent: it is complete, so it is counted
         * and listed -- see `CatalogWriter.countGroups` -- unless it turned out empty.
         */
        data class Finished(val kind: MediaKind, val groupId: String, val rows: Int) : CategoryEvent
    }

    /**
     * Hands one batch to the writer and empties it. **Does not commit.**
     *
     * It used to, and the two were one decision for as long as a batch and a commit
     * were the same thing. They are not: writing is what bounds memory, committing is
     * what makes rows visible and durable, and a section of 495 small categories needs
     * the first far more often than the second. The commit rule lives at the call site,
     * in `commitIfDue`, where it can see the clock and the running total.
     */
    private fun flush(batch: MutableList<CatalogItem>): Int {
        if (batch.isEmpty()) return 0
        val size = batch.size
        writer.writeItems(batch)
        batch.clear()
        return size
    }

    private fun IntArray.toKindMap(): Map<MediaKind, Int> {
        val map = LinkedHashMap<MediaKind, Int>(MediaKind.entries.size)
        for (kind in MediaKind.entries) {
            val count = this[kind.ordinal]
            if (count > 0) map[kind] = count
        }
        return map
    }

    companion object {
        /**
         * The whole catalogue. Radio is named, because a section is now written only when
         * it is asked for: asked for beside the channels, the stations come in the same walk
         * of the live categories, filed as radio.
         */
        val DEFAULT_KINDS: Set<MediaKind> = setOf(MediaKind.LIVE, MediaKind.MOVIE, MediaKind.SERIES, MediaKind.RADIO)

        /** [PassReport.orphanCategories]'s key for a stream that named no category. */
        const val NO_CATEGORY = "none"

        /** Its key for the ids past [MAX_ORPHAN_IDS], counted together. */
        const val OTHER_IDS = "other ids"

        /** Distinct orphan ids counted one by one; enough to read, never a reply's worth. */
        const val MAX_ORPHAN_IDS = 100

        /**
         * Requests in flight at once, by default.
         *
         * Four is a starting point chosen to be obviously safe rather than optimal: it
         * is four times fewer round trips end to end than the sequential loop it
         * replaces, and it is far below the point at which a single-host Xtream panel
         * starts refusing a subscription. Raise it from measurements on a real
         * provider, never from the fact that a bigger number sounds faster.
         */
        const val DEFAULT_CONCURRENCY = 4

        /**
         * The ceiling, which exists so nobody can turn this into 497 parallel requests.
         *
         * Above this, the thing being optimised stops being the user's wait and starts
         * being someone else's server.
         */
        const val MAX_CONCURRENCY = 8

        /**
         * How much ordering space each category is given.
         *
         * Categories are parsed concurrently and therefore finish out of order, so a
         * row's position is derived from its category's index rather than from a shared
         * counter — see `fetchCategory`. The stride has to exceed the largest category
         * a provider can ship, or two categories would interleave; 100,000 is well past
         * anything observed and still leaves room for 21,000 categories inside an Int.
         */
        const val CATEGORY_STRIDE = 100_000

        /**
         * Rows written since the last commit that force the next one.
         *
         * The ceiling on how much a failure or a cancellation rolls back, and on how
         * far the counted progress can run ahead of what a reader can see. Neither is
         * a loss: a section that did not finish is never marked loaded
         * (`LoadSection` writes the mark only on success), so the next open re-imports
         * it, and ids are stable under `INSERT OR REPLACE` so the rows that did land
         * are rewritten rather than duplicated.
         */
        const val COMMIT_ROWS = 2_000

        /**
         * And the longest a row waits to become visible when they arrive slowly.
         *
         * Half a second, because the number this protects is time-to-first-content and
         * the number it reduces is transaction count. A section whose categories are
         * tiny -- 38 rows each on the measured provider -- would otherwise commit on
         * every one of them; with this it commits on the clock instead, and a viewer
         * cannot perceive the difference between a row arriving now and 500ms from now
         * inside a load that runs for forty seconds.
         *
         * The first commit is exempt. See `commitIfDue`.
         */
        const val COMMIT_INTERVAL_MS = 500L

        /**
         * The rest before the categories that failed are asked for again, so a panel that
         * was overloaded by the first pass has a moment to recover.
         */
        const val RETRY_DELAY_MS = 2_000L

        /**
         * Requests in flight at once when asking again: half the first pass, for the same
         * reason the first pass is four -- the thing that failed was the load.
         */
        const val RETRY_CONCURRENCY = 2

        /**
         * The most categories asked for again. More failures than this are not a few
         * unlucky requests but a panel that is down or refusing, and asking them all again
         * would only spend minutes confirming it.
         */
        const val MAX_RETRIED_CATEGORIES = 50
    }
}

/**
 * A panel's answer that it does not serve a section in one reply.
 *
 * Thrown by an [XtreamImportEngine.Api] for a refusal it can recognise — an error status
 * for the request without a category — so the engine can tell "no" from "no answer". It
 * changes nothing about what happens next: either way the categories are asked for one
 * by one. What it changes is the report, which says the panel refused rather than that
 * the network failed.
 *
 * Its message is printed in that report, on the debug panel and in the log, so it says
 * what the panel answered — `HTTP 404` — and never where the question was sent.
 */
class WholeSectionUnsupported(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Nanoseconds as the milliseconds a [RequestTimes] is told in. */
private fun Long.ms(): Long = this / 1_000_000
