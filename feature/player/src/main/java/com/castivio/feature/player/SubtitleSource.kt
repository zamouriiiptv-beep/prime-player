package com.castivio.feature.player

import android.content.Context
import com.castivio.data.subtitles.OpenSubtitlesApi
import com.castivio.data.subtitles.SubtitleHash
import com.castivio.data.subtitles.SubtitleOffer
import com.castivio.data.subtitles.SubtitleQuery
import com.castivio.data.subtitles.SubtitleResult
import com.castivio.data.subtitles.SubtitleTrack
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the player gets subtitles it does not already have.
 *
 * ## Why the player does not simply take an `OpenSubtitlesApi`
 *
 * The same reason it takes a [ProgrammeSource] rather than an `EpgRepository`: a narrower
 * type makes a class of mistake impossible. The API can be asked to log in at any moment,
 * and having it in the player's constructor is an invitation to warm a session at startup —
 * which would send the viewer's password to a server because they opened a film, for a
 * feature they may never touch.
 *
 * It also makes the whole flow testable with no credentials, no network and no device. The
 * view model's tests hand it a source that answers from a literal.
 */
interface SubtitleSource {

    /**
     * Whether this build can search at all.
     *
     * False on every APK built by CI and on any clone without credentials, and the sheet
     * says so rather than offering a control that leads to a refusal.
     */
    val available: Boolean

    /**
     * What is offered for this file.
     *
     * [url] is the source the player was opened with, and it is used for one thing only: the
     * file hash, when there are local bytes to hash. It is emphatically **not** where the
     * name comes from — that was the defect this signature exists to prevent. A URL is a
     * route to bytes and says nothing about what they are, so an IPTV address ending in
     * `/502` produced a search for "502" and results for five unrelated series.
     *
     * [query] is what is being looked for, and it comes from the title the player was
     * opened with, or from what the viewer typed over it.
     */
    suspend fun search(
        url: String,
        query: SubtitleQuery,
        languages: List<String>,
    ): SubtitleResult<List<SubtitleOffer>>

    /** One of them, downloaded and parsed. Nothing is written to disk. */
    suspend fun download(offer: SubtitleOffer): SubtitleResult<SubtitleTrack>
}

/**
 * The real one: OpenSubtitles, with the file hash taken through the `ContentResolver`.
 *
 * ## Why the hash is computed here and not in `:data:subtitles`
 *
 * Because it needs a `ContentResolver`, and the hash itself must not. `SubtitleHash` takes a
 * function that opens a stream at an offset, so it stays a piece of arithmetic that can be
 * tested against a byte array; this class is the half that knows what a `content://` URI is.
 * The seam is the reason the arithmetic has tests at all.
 */
@Singleton
class OpenSubtitles @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: OpenSubtitlesApi,
    credentials: com.castivio.data.subtitles.OpenSubtitlesCredentials,
) : SubtitleSource {

    override val available: Boolean = credentials.configured

    override suspend fun search(
        url: String,
        query: SubtitleQuery,
        languages: List<String>,
    ): SubtitleResult<List<SubtitleOffer>> = api.search(
        hash = hash(url),
        query = query,
        languages = languages,
    )

    override suspend fun download(offer: SubtitleOffer): SubtitleResult<SubtitleTrack> =
        when (val link = api.link(offer.fileId)) {
            is SubtitleResult.Refused -> link
            is SubtitleResult.Found -> api.fetch(link.value)
        }

    /**
     * The file's hash, or null when there is no file to hash.
     *
     * Null for a stream — an IPTV channel has no bytes on this device to identify — and null
     * for a file the resolver will not open, which is an ordinary outcome for a URI whose
     * permission grant has lapsed. Either way the search falls back to the name, so this
     * never fails outward.
     */
    private suspend fun hash(url: String): Long? = withContext(Dispatchers.IO) {
        if (!url.startsWith(CONTENT_SCHEME, ignoreCase = true)) return@withContext null

        runCatching {
            val uri = android.net.Uri.parse(url)
            context.contentResolver.openFileDescriptor(uri, READ)?.use { descriptor ->
                val size = descriptor.statSize
                if (size <= 0) return@use null
                SubtitleHash.of(size) { at ->
                    // A fresh stream per window, positioned by the channel. `skip` is not
                    // used: it is allowed to skip fewer bytes than asked and a short skip
                    // would hash the wrong 64 KiB, which produces a stable hash that
                    // matches nothing.
                    FileInputStream(descriptor.fileDescriptor).apply { channel.position(at) }
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val CONTENT_SCHEME = "content://"
        const val READ = "r"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SubtitleSourceModule {
    @Binds
    abstract fun source(real: OpenSubtitles): SubtitleSource
}
