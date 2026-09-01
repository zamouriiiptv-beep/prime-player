package com.castivio.data.subtitles

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader

/**
 * One subtitle somebody has uploaded, as much of it as is worth showing — and, separately,
 * as much of it as is needed to decide whether to show it at all.
 *
 * The first four fields are the row. The API returns thirty per result — upload date,
 * uploader rank, comments, a ratings breakdown — and a list showing thirty fields is a list
 * nobody reads. What decides between two results is the language, whether it matches this
 * exact file, and how many people have used it.
 *
 * The fields under [release] are not for the eye at all. They are what [SubtitleMatch] reads
 * to answer "is this even the right programme", and they exist because the answer cannot be
 * found in the four above: a file called `road-ar.srt` says nothing about which road.
 */
data class SubtitleOffer(
    /** What the download call needs. Opaque, and the only field that is not for the eye. */
    val fileId: Long,
    val language: String,
    val name: String,
    val downloads: Int,
    /**
     * Whether this was found by the file's own hash rather than by its name.
     *
     * The single most useful thing in the list. A hash match is a subtitle somebody has
     * already watched against these exact bytes, so it is in step; a name match is a
     * subtitle for this film, from some copy of it, and may be two minutes out.
     */
    val matchesThisFile: Boolean,

    /** The release this was timed to, as the uploader named it. Empty when unstated. */
    val release: String = "",

    /**
     * What OpenSubtitles says this subtitle is *for*, from its own catalogue.
     *
     * The authoritative answer and the reason the filter is worth trusting: it is the site's
     * own identification of the work, not an uploader's file name. For an episode it is the
     * episode's title and [parentTitle] is the series — which is why both are carried and
     * why [describes] joins them.
     */
    val featureTitle: String = "",
    val parentTitle: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
) {

    /**
     * The text that says what this subtitle is for, best source first.
     *
     * The catalogue's own titles when it gave any, and the uploader's names when it did not.
     * The fallback matters more than it looks: a great many older uploads carry no feature
     * details at all, and a filter that treated "no details" as "no match" would hide them
     * all rather than judge them on the only evidence there is.
     */
    val describes: String
        get() = listOf(parentTitle, featureTitle)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { listOf(release, name).filter { it.isNotBlank() }.joinToString(" ") }
}

/** Why a search or a download could not be done, in the terms the screen has to explain. */
enum class SubtitleFailure {
    /** No key, or no account. The one failure the user can do something about. */
    NOT_CONFIGURED,

    /** Nothing answered, or the answer never arrived. */
    NETWORK,

    /** The credentials were refused. A wrong key or a wrong password, and unrecoverable here. */
    REFUSED,

    /** OpenSubtitles' daily download allowance for this account is spent. */
    OUT_OF_DOWNLOADS,

    /** It answered, and the answer was not one this client understands. */
    UNREADABLE,
}

/** Either the thing asked for, or why not. Small enough not to need a library. */
sealed interface SubtitleResult<out T> {
    data class Found<T>(val value: T) : SubtitleResult<T>
    data class Refused(val reason: SubtitleFailure) : SubtitleResult<Nothing>
}

/**
 * OpenSubtitles' REST API, in the three calls this product makes.
 *
 * ## Why there are three and not one
 *
 * Searching takes the API key alone. Downloading does not: the API issues a link only to a
 * logged-in session, which is how it counts a person's daily allowance. So a download is
 * login → link → fetch, and the login is deferred until the first download rather than done
 * at startup — a viewer who never opens the subtitle search should never have their
 * password sent anywhere.
 *
 * ## What is not here
 *
 * No JSON library. `org.json` is in the platform, the three responses are read for a
 * handful of fields each, and a parser dependency for that would be a dependency added to
 * read four keys. No caching layer either: a search is a deliberate act a viewer performs
 * once per film, not a feed.
 *
 * ## The token is held, and only in memory
 *
 * A session lasts about a day. Keeping it saves a round trip and a password send on the
 * second download of a sitting; writing it to disk would mean a credential at rest, in a
 * file, for a feature that can perfectly well log in again next time. It goes when the
 * process goes.
 */
class OpenSubtitlesApi(
    private val client: OkHttpClient,
    private val credentials: OpenSubtitlesCredentials,
    private val base: HttpUrl = DEFAULT_BASE.toHttpUrl(),
) {

    private var token: String? = null

    /**
     * What is available for [query], and nothing that is available for something else.
     *
     * The hash is sent alongside the name rather than instead of it: OpenSubtitles accepts
     * both together and returns hash matches first, so asking twice would be a second round
     * trip for a subset of the same list.
     *
     * ## The query is structured, not a sentence
     *
     * `query` carries the name alone and the season and episode go in their own parameters.
     * Putting "Friends S05E02" in the text field asks the server to find those characters in
     * a title, which is a different and much worse question than the one it has fields for.
     *
     * ## And the answer is filtered
     *
     * What comes back is passed through [SubtitleMatch] before anyone sees it. The server
     * answers the query it was given, correctly, and "correctly" includes returning episode
     * 502 of five other series when the query was poor. Only this side knows what is
     * playing, so only this side can say that those are not it.
     *
     * [languages] is a list of two-letter codes. Empty means every language, which is what
     * the sheet asks for when the viewer has not chosen one.
     */
    suspend fun search(
        hash: Long?,
        query: SubtitleQuery,
        languages: List<String>,
    ): SubtitleResult<List<SubtitleOffer>> = call {
        if (!credentials.configured) return@call SubtitleResult.Refused(SubtitleFailure.NOT_CONFIGURED)

        val url = base.newBuilder()
            .addPathSegment("subtitles")
            .addQueryParameter("query", query.title)
            .apply {
                query.season?.let { addQueryParameter("season_number", it.toString()) }
                query.episode?.let { addQueryParameter("episode_number", it.toString()) }
                query.year?.let { addQueryParameter("year", it.toString()) }
                hash?.let { addQueryParameter("moviehash", it.asSubtitleHash()) }
                if (languages.isNotEmpty()) {
                    addQueryParameter("languages", languages.joinToString(","))
                }
            }
            .build()

        val response = client.newCall(get(url).build()).execute()
        response.use {
            if (!it.isSuccessful) return@call SubtitleResult.Refused(failure(it.code))
            val body = it.body?.string() ?: return@call SubtitleResult.Refused(SubtitleFailure.UNREADABLE)
            SubtitleResult.Found(SubtitleMatch.relevant(query, offers(body)))
        }
    }

    /**
     * A link for one result, which is where the account is actually spent.
     *
     * The token is fetched on demand and reused. A 401 on a held token means it has expired,
     * which is worth exactly one retry: logging in again and asking once more is the normal
     * course of a session that has been open since yesterday, and a second failure is a
     * password that is wrong rather than a token that is old.
     */
    suspend fun link(fileId: Long): SubtitleResult<String> = call {
        if (!credentials.configured) return@call SubtitleResult.Refused(SubtitleFailure.NOT_CONFIGURED)

        var attempt = requestLink(fileId)
        if (attempt is SubtitleResult.Refused && attempt.reason == SubtitleFailure.REFUSED) {
            token = null
            attempt = requestLink(fileId)
        }
        attempt
    }

    /**
     * The file itself, parsed as it arrives.
     *
     * Parsed here rather than saved and parsed later, because there is no reason for a
     * subtitle file to touch the disk: it is 60 KB, it is wanted now, and a file written to
     * a cache is a file somebody has to remember to delete.
     */
    suspend fun fetch(link: String): SubtitleResult<SubtitleTrack> = call {
        val response = client.newCall(Request.Builder().url(link).build()).execute()
        response.use {
            if (!it.isSuccessful) return@call SubtitleResult.Refused(failure(it.code))
            val body = it.body ?: return@call SubtitleResult.Refused(SubtitleFailure.UNREADABLE)
            val track = body.charStream().buffered().use(SrtParser::parse)
            if (track.cues.isEmpty()) {
                SubtitleResult.Refused(SubtitleFailure.UNREADABLE)
            } else {
                SubtitleResult.Found(track)
            }
        }
    }

    private fun requestLink(fileId: Long): SubtitleResult<String> {
        val session = token ?: when (val fresh = login()) {
            is SubtitleResult.Refused -> return fresh
            is SubtitleResult.Found -> fresh.value.also { token = it }
        }

        val body = JSONObject().put("file_id", fileId).toString().toRequestBody(JSON)
        val request = get(base.newBuilder().addPathSegment("download").build())
            .post(body)
            .header("Authorization", "Bearer $session")
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) return SubtitleResult.Refused(failure(it.code))
            val text = it.body?.string() ?: return SubtitleResult.Refused(SubtitleFailure.UNREADABLE)
            val link = runCatching { JSONObject(text).optString("link") }.getOrNull()
            return if (link.isNullOrBlank()) {
                SubtitleResult.Refused(SubtitleFailure.UNREADABLE)
            } else {
                SubtitleResult.Found(link)
            }
        }
    }

    private fun login(): SubtitleResult<String> {
        val body = JSONObject()
            .put("username", credentials.username)
            .put("password", credentials.password)
            .toString()
            .toRequestBody(JSON)

        val request = get(base.newBuilder().addPathSegment("login").build())
            .post(body)
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) return SubtitleResult.Refused(failure(it.code))
            val text = it.body?.string() ?: return SubtitleResult.Refused(SubtitleFailure.UNREADABLE)
            val session = runCatching { JSONObject(text).optString("token") }.getOrNull()
            return if (session.isNullOrBlank()) {
                SubtitleResult.Refused(SubtitleFailure.REFUSED)
            } else {
                SubtitleResult.Found(session)
            }
        }
    }

    /**
     * The headers every call carries.
     *
     * `Api-Key` on all of them, including the login. A `User-Agent` that names the product
     * and is not a browser's, because OpenSubtitles asks for one and refuses the default.
     */
    private fun get(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Api-Key", credentials.apiKey)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")

    /**
     * What a status code means to a viewer.
     *
     * 406 is the one worth naming separately: it is how OpenSubtitles says the account's
     * daily downloads are spent, and it is the only failure here that is neither the user's
     * mistake nor a broken network. Telling somebody "try again tomorrow" needs it.
     */
    private fun failure(code: Int): SubtitleFailure = when (code) {
        401, 403 -> SubtitleFailure.REFUSED
        406, 429 -> SubtitleFailure.OUT_OF_DOWNLOADS
        in 500..599 -> SubtitleFailure.NETWORK
        else -> SubtitleFailure.UNREADABLE
    }

    /**
     * Off the main thread, and no exception escapes.
     *
     * A subtitle search is the least important thing a player does, and there is no failure
     * of it that should be able to reach a viewer as a crash over a film. Everything below
     * turns into a reason the sheet can put in a sentence.
     */
    private suspend fun <T> call(work: () -> SubtitleResult<T>): SubtitleResult<T> =
        withContext(Dispatchers.IO) {
            runCatching(work).getOrElse { error ->
                Log.w(TAG, "subtitle request failed", error)
                SubtitleResult.Refused(SubtitleFailure.NETWORK)
            }
        }

    private fun offers(body: String): List<SubtitleOffer> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val offers = mutableListOf<SubtitleOffer>()
        for (index in 0 until data.length()) {
            val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
            val file = attributes.optJSONArray("files")?.optJSONObject(0) ?: continue
            val fileId = file.optLong("file_id", -1L)
            if (fileId <= 0) continue
            // The site's own identification of what this subtitle is for. Absent on a great
            // many older uploads, which is why every field below tolerates being missing and
            // why `SubtitleOffer.describes` has a fallback.
            val feature = attributes.optJSONObject("feature_details")
            offers += SubtitleOffer(
                fileId = fileId,
                language = attributes.optString("language").ifBlank { UNKNOWN_LANGUAGE },
                // The uploader's file name, falling back to the release it was timed to.
                // One of the two is always there and it is the only thing in the row that
                // tells two results for the same film apart.
                name = file.optString("file_name").ifBlank {
                    attributes.optString("release").ifBlank { UNKNOWN_NAME }
                },
                downloads = attributes.optInt("download_count"),
                matchesThisFile = attributes.optBoolean("moviehash_match"),
                release = attributes.optString("release"),
                featureTitle = feature?.optString("title").orEmpty(),
                parentTitle = feature?.optString("parent_title").orEmpty(),
                season = feature?.number("season_number"),
                episode = feature?.number("episode_number"),
                year = feature?.number("year"),
            )
        }
        // Hash matches first, then by how many people have used it. The order is the whole
        // recommendation: a viewer picks the top row, and the top row should be the one that
        // fits this file.
        return offers.sortedWith(
            compareByDescending<SubtitleOffer> { it.matchesThisFile }.thenByDescending { it.downloads },
        )
    }

    /**
     * A whole number that may not be there, and may be there as `null` or as `""`.
     *
     * `optInt` cannot express the difference: it returns 0 for all three, and a season 0 is a
     * season the filter would then insist on. This returns null unless a number was actually
     * stated.
     */
    private fun JSONObject.number(key: String): Int? =
        if (isNull(key)) null else optString(key).toIntOrNull() ?: optInt(key, -1).takeIf { it >= 0 }

    companion object {
        const val DEFAULT_BASE = "https://api.opensubtitles.com/api/v1/"
        const val TAG = "CastivioSubtitles"

        /** OpenSubtitles requires a named agent and refuses a generic one. */
        const val USER_AGENT = "Castivio v1"

        const val UNKNOWN_LANGUAGE = "??"
        const val UNKNOWN_NAME = "subtitle"

        private val JSON = "application/json".toMediaType()
    }
}
