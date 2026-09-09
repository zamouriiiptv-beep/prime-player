package com.castivio.domain

import com.castivio.core.common.AppError
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
     * @param items how many rows have been committed so far. Rising, never resetting.
     * @param groups categories finished, which fill before the rows do.
     */
    data class Loading(val items: Int, val groups: Int) : SectionLoad

    /** Reached the end and committed. The screen can read its rows. */
    data class Done(val items: Int) : SectionLoad

    /**
     * @param retryable false for the failures no amount of trying will fix — a
     *   playlist the parser could not read is not a slow server.
     */
    data class Failed(val error: AppError, val retryable: Boolean) : SectionLoad
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
            // fetchable source — a portal registration, or a row missing its host.
            // Not retryable: pressing again reconstructs exactly the same nothing.
            emit(SectionLoad.Failed(AppError.NOT_CONFIGURED, retryable = false))
            return@flow
        }

        var items = 0
        var failed = false

        importer.importKind(source, kind).collect { progress ->
            when (progress) {
                is ImportProgress.CheckingForChanges -> emit(SectionLoad.Loading(items, 0))

                is ImportProgress.Importing -> {
                    // Never backwards. A single-file import reports every kind it
                    // passes through, and a counter that drops when the parser moves
                    // from films to series reads as a fault rather than as progress.
                    items = maxOf(items, progress.itemsImported)
                    emit(SectionLoad.Loading(items, progress.groupsReady))
                }

                // Nothing changed upstream, so nothing was downloaded. For a section
                // being fetched for the first time that still counts as arriving:
                // whatever is on disk is the answer, and it is now marked as fetched.
                is ImportProgress.UpToDate -> Unit

                is ImportProgress.Done -> items = maxOf(items, progress.totalItems)

                is ImportProgress.Failed -> {
                    failed = true
                    emit(SectionLoad.Failed(progress.error, retryable = progress.error.retryable))
                }
            }
        }

        if (!failed) {
            marks.markLoaded(provider.id, kind, nowMs)
            // A single-file provider hands over every kind in one pass, so marking
            // only the one that was asked for would download the same file again on
            // the next section. See [PlaylistSource.carriesEveryKind].
            if (source.carriesEveryKind) {
                for (other in MediaKind.entries) {
                    if (other != kind) marks.markLoaded(provider.id, other, nowMs)
                }
            }
            emit(SectionLoad.Done(items))
        }
    }
}

/**
 * Whether one fetch of this source brings the whole catalogue with it.
 *
 * An M3U playlist is one file containing channels, films and episodes together;
 * there is no request that asks it for only the films. So the first section opened
 * pays for all of them, and the other three are free — which is worth recording as
 * a property of the source rather than as an `if` inside the loader.
 *
 * Xtream is the opposite and the reason this exists: live, VOD and series are three
 * separate endpoints, so a section really can be fetched on its own.
 */
val PlaylistSource.carriesEveryKind: Boolean
    get() = when (this) {
        is PlaylistSource.M3u, is PlaylistSource.LocalFile -> true
        is PlaylistSource.Xtream, is PlaylistSource.Portal -> false
    }

/**
 * A stored provider, back as something that can be fetched from.
 *
 * The registration is lossless for the three kinds that can be re-fetched — the URL
 * column holds the playlist link, the file URI or the Xtream host, and the two
 * credential columns hold the rest — so this is a re-reading rather than a guess.
 * Null for a portal, which has no stored address to go back to.
 */
fun ProviderSource.asPlaylistSource(): PlaylistSource? = when (kind) {
    SourceKind.M3U_URL -> url?.let { PlaylistSource.M3u(it, userAgent) }
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
    SourceKind.PORTAL -> null
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
