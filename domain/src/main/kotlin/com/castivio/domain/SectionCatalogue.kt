package com.castivio.domain

import com.castivio.core.common.AppError
import com.castivio.domain.provider.FieldValidation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * When each section of a provider's catalogue was last brought onto this device.
 *
 * ## Why a section is the unit
 *
 * Because it is the unit a user waits for. A provider with 180,000 films and 1,600
 * channels makes someone who only ever watches television pay for the films every
 * time they set the app up — and pay again on the next box. Downloading a section
 * when it is first opened turns one unavoidable four-minute wait into three optional
 * ones, and two of them are never taken.
 *
 * It is also the unit the provider offers. Xtream is category-addressable per kind:
 * live, VOD and series are separate calls, so "just the channels" is a smaller
 * request and not merely a smaller write.
 *
 * ## What a mark means, and what it does not
 *
 * A timestamp here means *this kind was imported for this source and committed*. It
 * is not a count and not a promise that the section is non-empty: a provider that
 * genuinely carries no radio imports zero stations successfully, and that has to be
 * distinguishable from a section nobody has fetched yet. Deriving the answer from
 * `count(kind) > 0` cannot make that distinction, and would re-download an empty
 * section on every visit for the rest of the install's life.
 */
interface SectionCatalogue {

    /** Null when this kind has never been imported for this source. */
    suspend fun loadedAt(sourceId: String, kind: MediaKind): Long?

    /** Every kind this source has, with when it arrived. Re-emits as sections land. */
    fun loaded(sourceId: String): Flow<Map<MediaKind, Long>>

    suspend fun markLoaded(sourceId: String, kind: MediaKind, atMs: Long)

    /** A source the user removed, or replaced with different credentials. */
    suspend fun forget(sourceId: String)
}

/**
 * What a screen waiting for its section shows.
 *
 * Deliberately not [ImportProgress]. That is the importer's vocabulary — checking
 * validators, up to date, batches committed — and most of it answers a question a
 * section screen is not asking. What a screen needs is: is it here, is it coming,
 * how far along, or what went wrong.
 */
sealed interface SectionLoad {

    /** Already on the device. The overwhelmingly common case after the first visit. */
    data object Ready : SectionLoad

    /** No provider configured, so there is nothing to fetch and nothing to wait for. */
    data object NoSource : SectionLoad

    /**
     * Asked for, and waiting its turn behind another section's load. Only [SectionLoads]
     * says this: [LoadSection] runs when it is called and never waits.
     */
    data object Queued : SectionLoad

    /**
     * @param items how many rows have been committed so far. Rising, never resetting.
     * @param groups how many categories this source has in total — the denominator.
     * @param groupsDone how many of them have finished, succeeded or failed. With
     *   [groups] this is what a determinate progress bar needs. **Null** where the
     *   importer cannot answer it at all — see [ImportProgress.Importing.categoriesDone]
     *   — and the screen counts rows instead of drawing a bar that cannot move.
     * @param bytes what the provider has actually sent so far. Zero where nothing
     *   counts them — a local file has no socket to measure.
     * @param listed how many categories are complete and on the device with their rows:
     *   what the section's screen lists if it is opened now. Rising, never resetting, and
     *   zero until the first category is complete -- which is what Live TV opens with. See
     *   [ImportProgress.Importing.categoriesListed].
     */
    data class Loading(
        val items: Int,
        val groups: Int,
        val groupsDone: Int? = null,
        val bytes: Long = 0,
        val listed: Int = 0,
    ) : SectionLoad

    /** Reached the end and committed. The screen can read its rows. */
    data class Done(val items: Int) : SectionLoad

    /**
     * @param retryable false for the failures no amount of trying will fix — a
     *   playlist the parser could not read is not a slow server.
     * @param diagnosis the importer's figures for this failure, passed through unchanged.
     *   Null where there are none — see [ImportProgress.Failed.diagnosis].
     */
    data class Failed(
        val error: AppError,
        val retryable: Boolean,
        val diagnosis: FetchDiagnosis? = null,
    ) : SectionLoad
}

/**
 * Fetches one section of the active provider's catalogue, once.
 *
 * ## The whole of the laziness, in one place
 *
 * Activation stops after checking the credentials, so the app opens on a Home with
 * four empty sections and no wait. This is what fills one of them, the first time it
 * is opened, and never again unless asked.
 *
 * Pure, and therefore tested without a device: it takes the instant to mark with,
 * opens no clock, and touches no Android. The three interesting cases — no provider,
 * already loaded, a provider whose stored credentials cannot be turned back into a
 * source — are all decided here rather than in a view model.
 *
 * ## Why the mark is written after the import and only on success
 *
 * A mark written first would turn a failed download into a section that is
 * permanently empty and permanently "loaded": the user would press it, get nothing,
 * and have no way to ask again. Writing it last means a failure costs exactly one
 * retry and nothing else.
 */
class LoadSection(
    private val sources: SourceRepository,
    private val importer: CatalogImporter,
    private val marks: SectionCatalogue,
) {

    /**
     * @param force ignores an existing mark. This is what a refresh is: the same
     *   fetch, asked for deliberately, rather than a second code path.
     */
    fun load(kind: MediaKind, nowMs: Long, force: Boolean = false): Flow<SectionLoad> = flow {
        val provider = sources.activeNow()
        if (provider == null) {
            emit(SectionLoad.NoSource)
            return@flow
        }

        if (!force && marks.loadedAt(provider.id, kind) != null) {
            emit(SectionLoad.Ready)
            return@flow
        }

        val source = provider.asPlaylistSource()
        if (source == null) {
            // A stored provider whose credentials cannot be turned back into a
            // fetchable source — a row missing its host or its address.
            // Not retryable: pressing again reconstructs exactly the same nothing.
            emit(SectionLoad.Failed(AppError.NOT_CONFIGURED, retryable = false))
            return@flow
        }

        var items = 0
        var listed = 0
        var failed = false

        // A refresh asks the provider; a first open may be answered by what the device
        // already holds of it -- a playlist's kept copy.
        importer.importKind(source, kind, fresh = force).collect { progress ->
            when (progress) {
                is ImportProgress.CheckingForChanges -> emit(SectionLoad.Loading(items, 0))

                is ImportProgress.Importing -> {
                    // Never backwards. A single-file import reports every kind it
                    // passes through, and a counter that drops when the parser moves
                    // from films to series reads as a fault rather than as progress.
                    // The categories listed likewise: a screen that opened with one
                    // must never be told it has none.
                    items = maxOf(items, progress.itemsImported)
                    listed = maxOf(listed, progress.categoriesListed)
                    emit(
                        SectionLoad.Loading(
                            items = items,
                            groups = progress.groupsReady,
                            groupsDone = progress.categoriesDone,
                            bytes = progress.bytes,
                            listed = listed,
                        ),
                    )
                }

                // Nothing changed upstream, so nothing was downloaded. For a section
                // being fetched for the first time that still counts as arriving:
                // whatever is on disk is the answer, and it is now marked as fetched.
                is ImportProgress.UpToDate -> Unit

                is ImportProgress.Done -> items = maxOf(items, progress.totalItems)

                is ImportProgress.Failed -> {
                    failed = true
                    emit(
                        SectionLoad.Failed(
                            progress.error,
                            retryable = progress.error.retryable,
                            diagnosis = progress.diagnosis,
                        ),
                    )
                }
            }
        }

        if (!failed) {
            // Every section the fetch actually delivered, not only the one that was asked
            // for: marking less would download the same thing again on the next press. A
            // file picked from storage hands over every kind in one pass; a panel, a portal
            // and a playlist hand over the one section asked for. See
            // [PlaylistSource.kindsArrivingWith].
            //
            // Asked of the subscription as it stands now rather than as it was read
            // before the import: a playlist read through its panel can have been read as
            // the playlist after all -- the importer falls back when the panel's API says
            // no -- and it is then a playlist like any other.
            val settled = sources.get(provider.id)?.asPlaylistSource() ?: source
            for (arrived in settled.kindsArrivingWith(kind)) {
                marks.markLoaded(provider.id, arrived, nowMs)
            }
            emit(SectionLoad.Done(items))
        }
    }
}

/**
 * Whether one fetch of this source brings the whole catalogue with it.
 *
 * A file picked from storage is read whole: the first section opened pays for all of
 * them, and the other three are free — which is worth recording as a property of the
 * source rather than as an `if` inside the loader.
 *
 * Every other source is fetched a section at a time. Xtream and a portal ask a separate
 * endpoint for each kind. A playlist is one file holding every kind, and no request asks
 * it for the films alone — but it is downloaded once and kept, and each section is read
 * from the copy and written on its own, so the first section opened waits for its own
 * rows rather than for the whole library's. See [PlaylistCopies].
 */
val PlaylistSource.carriesEveryKind: Boolean
    get() = when (this) {
        is PlaylistSource.LocalFile -> true
        // Live, films, series and radio are each a fetch of their own: separate calls on a
        // panel and on a portal, and separate readings of a playlist's kept copy -- see
        // [kindsArrivingWith].
        is PlaylistSource.M3u, is PlaylistSource.Xtream, is PlaylistSource.Portal -> false
    }

/**
 * Every section one fetch of [kind] writes, [kind] included.
 *
 * ## Why radio is its own fetch, on a panel too
 *
 * An Xtream panel has no radio endpoint: its stations are live categories with a radio
 * name. They used to come with the channels -- one walk, both sections written and both
 * marked -- and opening the radio then meant the channels too, rewritten for a few hundred
 * stations. Now a load of the channels writes the channels and passes over the stations,
 * and a load of the radio asks for the radio categories alone, so each section is the
 * fetch of its own and is marked on its own. A playlist read through its panel is read
 * the same way, a portal answers `radio` on a call of its own anyway, and a playlist's
 * kept copy is read for its stations alone.
 */
fun PlaylistSource.kindsArrivingWith(kind: MediaKind): Set<MediaKind> =
    if (carriesEveryKind) MediaKind.entries.toSet() else setOf(kind)

/**
 * A stored provider, back as something that can be fetched from.
 *
 * The registration is lossless for the three kinds that can be re-fetched — the URL
 * column holds the playlist link, the file URI or the Xtream host, and the two
 * credential columns hold the rest — so this is a re-reading rather than a guess.
 * Null for a portal, which has no stored address to go back to.
 *
 * It is also where a playlist is told which way it is read — see [panelAccount] — so
 * that every reader of a subscription reads it the same way.
 */
fun ProviderSource.asPlaylistSource(): PlaylistSource? = when (kind) {
    SourceKind.M3U_URL -> url?.let { PlaylistSource.M3u(it, userAgent, xtream = panelAccount(it)) }
    SourceKind.LOCAL_FILE -> url?.let { PlaylistSource.LocalFile(it, label) }
    SourceKind.XTREAM -> {
        val host = url
        val user = username
        val secret = password
        if (host != null && user != null && secret != null) {
            PlaylistSource.Xtream(host, user, secret)
        } else {
            null
        }
    }
    // A portal is its address, so the row holds everything needed to reach it again.
    // It used to be `null` here, from when a portal had no address to be reconstructed
    // from — and that null is what made every section on a portal subscription fail
    // with NOT_CONFIGURED before a request was ever sent.
    SourceKind.PORTAL -> url?.let { PlaylistSource.Portal(it) }
}

/**
 * The panel account an M3U subscription is read through, or null to read its playlist.
 *
 * ## Through the panel when the link names one
 *
 * `get.php?username=…&password=…` is an Xtream account fetched as one file. Read through
 * the panel's API instead, each section arrives on its own and comes with categories,
 * series and a status — and the panel's API often answers when its playlist endpoint
 * does not. The link is read here, locally; nothing is asked of the network to decide.
 * See [FieldValidation.detectXtream] for what makes a link an account.
 *
 * ## Not when the catalogue on the device came from the playlist
 *
 * A subscription whose playlist was imported to the end keeps being read as a playlist.
 * The two readings give the same channel different ids — a playlist row is keyed on its
 * stream URL, an API row on its stream id — so switching would put every channel on the
 * screen twice, and favourites and history, which follow ids, would stay behind with the
 * old rows. `contentHash` is the mark of that import: only a playlist read to its end
 * records one, and the API path never does. A migration that forgets sync state without
 * dropping the catalogue would erase that mark, which `CastivioMigrations` has to know.
 */
private fun ProviderSource.panelAccount(link: String): PlaylistSource.Xtream? =
    if (sync.contentHash != null) null else FieldValidation.detectXtream(link)

/**
 * The subscription as its provider is asked about it: whether it is usable, and until when.
 *
 * [asPlaylistSource] but for one thing. A playlist whose link names a panel account is asked
 * through that panel even when its catalogue is read from the playlist. The reading follows
 * the rows already on the device -- see [panelAccount] -- and an answer about the account
 * writes no rows, so nothing ties the question to that reading.
 *
 * Asked the other way, it was a probe of the playlist file: the heaviest request a panel
 * serves, and one that says nothing about the account. Once a playlist had been read to its
 * end -- by the import that runs when the panel refuses a first section -- refresh probed
 * the file and failed where the panel would have answered, and the expiry date could never
 * be shown again: a playlist on its own has none, so it was not even asked.
 */
fun ProviderSource.asAccountSource(): PlaylistSource? = when (kind) {
    SourceKind.M3U_URL -> url?.let { PlaylistSource.M3u(it, userAgent, xtream = FieldValidation.detectXtream(it)) }
    else -> asPlaylistSource()
}

/**
 * Whether pressing the button again could plausibly work.
 *
 * The same split the activation screen already makes, stated once here so a section
 * screen does not invent a second opinion about which failures are worth a retry.
 */
private val AppError.retryable: Boolean
    get() = when (this) {
        AppError.NETWORK_UNAVAILABLE, AppError.TIMEOUT, AppError.SERVER_ERROR, AppError.UNKNOWN -> true
        AppError.UNAUTHORIZED, AppError.NOT_FOUND, AppError.MALFORMED_PLAYLIST, AppError.NOT_CONFIGURED -> false
    }
