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
     * episode's own title and [parentTitle] is the series, which is why both are carried —
     * a search for *Friends* is answered by the second of them.
     */
    val featureTitle: String = "",
    val parentTitle: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
)

/**
 * A work as OpenSubtitles' own catalogue holds it: an identifier, a name, a date.
 *
 * The thing a title is resolved *to* before any subtitle is asked for. Searching by [id]
 * cannot return another film — not because a filter rejected it, but because the question
 * was never about a name.
 */
data class SubtitleFeature(
    val id: Long,
    val title: String,
    val year: Int?,
    /** Whether [id] is a series, whose episodes are found through it rather than as it. */
    val isSeries: Boolean,
)

/** Why a search or a download could not be done, in the terms the screen has to explain. */
enum class SubtitleFailure {
    /** No key, or no account. The one failure the user can do something about. */
    NOT_CONFIGURED,

    /** Nothing answered, or the answer never arrived. */
    NETWORK,

    /** The credentials were refused. A wrong key or a wrong password, and unrecoverable here. */
    REFUSED,

    /**
     * OpenSubtitles' daily download allowance for this account is spent.
     *
     * Only ever set from a `406` on a *download* request, which is the provider stating it.
     * It used to be set from a `429` on anything, including a search — so a throttle on a
     * lookup was reported as a spent quota, to people who had downloaded nothing and could
     * not have fixed it by waiting until tomorrow. A rate limit is [RATE_LIMITED] and is a
     * different sentence with a different remedy.
     */
    OUT_OF_DOWNLOADS,

    /**
     * Too many requests, too quickly. Nothing is used up and the remedy is a moment.
     *
     * Reachable from a search as easily as from a download, because a search is now several
     * requests: the catalogue lookup and the subtitle query, once per rung of the ladder.
     */
    RATE_LIMITED,

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
     * ## The work is identified before its subtitles are asked for
     *
     * The first request is not for subtitles at all. It is `/features`, which is
     * OpenSubtitles' catalogue of works, and it turns a name and a year into an identifier.
     * Once there is one, the subtitles are asked for *by it* — and a search by identifier
     * cannot return another film, not because a filter rejected it but because the question
     * was never about a name.
     *
     * That is the difference between this and a keyword search. `query=Pursuit` is a
     * question that *The Pursuit of Happyness* and *Cold Pursuit* are both correct answers
     * to. `id=1234` is not.
     *
     * When nothing resolves — an obscure work, a name the catalogue does not carry — the
     * search falls back to the name, and [SubtitleMatch] then holds the results to a title
     * that has to *equal* the query rather than contain it. The fallback is narrower than
     * the keyword search it replaces, not wider.
     *
     * ## The query is structured, not a sentence
     *
     * The name goes in `query` and the season and episode go in their own parameters.
     * Putting "Friends S05E02" in the text field asks the server to find those characters in
     * a title, which is a different and much worse question than the one it has fields for.
     *
     * The hash rides on every request. It costs nothing and it is the only evidence here
     * about *this file* rather than about somebody's spelling.
     *
     * ## Asked more than once before it is given up on
     *
     * [SubtitleQuery.attempts] is a ladder — the name with everything worked out about it,
     * then without the year, then without the subtitle — and each rung is tried only because
     * the one above it found nothing. Each is filtered against *itself*, which is the point
     * of dropping the assumption from the question at all: if this name in this year found
     * nothing, that year is not the year the catalogue holds, and going on to reject answers
     * for having a different one would be asking a question whose answers are thrown away.
     *
     * Every rung is a narrowing of the *name*, never a broadening of what is accepted. So
     * "no subtitles available" is reached after up to three real attempts rather than one,
     * and none of the three can produce somebody else's film.
     *
     * A refusal ends it immediately. Three attempts at a wrong key is three ways of being
     * told the same thing, more slowly.
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

        for (attempt in query.attempts()) {
            val feature = resolve(attempt)
            val found = when (val outcome = ask(hash, attempt, languages, feature)) {
                is SubtitleResult.Refused -> return@call outcome
                is SubtitleResult.Found -> outcome.value
            }
            Log.i(
                TAG,
                "searched: title=\"${attempt.title}\" year=${attempt.year} " +
                    "season=${attempt.season} episode=${attempt.episode} " +
                    "feature=${feature?.id ?: "unresolved"} languages=${languages.joinToString("+")} " +
                    "matches=${found.size}",
            )
            if (found.isNotEmpty()) return@call SubtitleResult.Found(found)
        }
        SubtitleResult.Found(emptyList())
    }

    /**
     * The work this name refers to, or null when the catalogue does not recognise it.
     *
     * Held to the same equality [SubtitleMatch] uses, and for the same reason: `/features`
     * is a search too, and asked for `Pursuit` it will offer *Cold Pursuit* among the
     * answers. Taking its first row would be a keyword search with an extra round trip.
     *
     * Null is an ordinary outcome and not a failure. A network problem is also null here —
     * the subtitle request that follows will meet the same problem and report it properly,
     * and a resolution step that could fail the whole search would make the feature less
     * reliable than the keyword search it improves on.
     */
    private fun resolve(query: SubtitleQuery): SubtitleFeature? = runCatching {
        val url = base.newBuilder()
            .addPathSegment("features")
            .addQueryParameter("query", query.title)
            .apply { query.year?.let { addQueryParameter("year", it.toString()) } }
            .build()

        client.newCall(get(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            features(body).firstOrNull { candidate ->
                SubtitleMatch.sameWork(query, candidate.title, candidate.year)
            }
        }
    }.getOrNull()

    private fun features(body: String): List<SubtitleFeature> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val found = mutableListOf<SubtitleFeature>()
        for (index in 0 until data.length()) {
            val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
            val kind = attributes.optString("feature_type")
            val series = kind.equals(TV_SHOW, ignoreCase = true)
            // A series is found through its own id; an episode's row carries the series' id
            // in `parent_feature_id`, which is the one a subtitle search wants.
            val id = when {
                kind.equals(EPISODE, ignoreCase = true) -> attributes.number("parent_feature_id")
                else -> attributes.number("feature_id")
            } ?: continue
            // `title` is the catalogue's name and `original_title` the one it was released
            // under. Both are offered because a provider may use either.
            listOfNotNull(
                attributes.optString("title").takeIf { it.isNotBlank() },
                attributes.optString("original_title").takeIf { it.isNotBlank() },
            ).forEach { name ->
                found += SubtitleFeature(
                    id = id.toLong(),
                    title = name,
                    year = attributes.number("year"),
                    isSeries = series || kind.equals(EPISODE, ignoreCase = true),
                )
            }
        }
        return found
    }

    /**
     * One rung of the ladder: one request, and what is left of it after the filter.
     *
     * Asked by [feature] when the catalogue recognised the name, and by the name when it did
     * not. The two are mutually exclusive on purpose — sending both would let the name widen
     * a question the identifier had already closed.
     *
     * A series' identifier goes in `parent_feature_id` with the season and episode beside
     * it, because it names the series and the viewer is watching one episode of it. A film's
     * goes in `id`, which names the film itself.
     */
    private fun ask(
        hash: Long?,
        query: SubtitleQuery,
        languages: List<String>,
        feature: SubtitleFeature?,
    ): SubtitleResult<List<SubtitleOffer>> {
        val url = base.newBuilder()
            .addPathSegment("subtitles")
            .apply {
                when {
                    feature == null -> {
                        addQueryParameter("query", query.title)
                        query.year?.let { addQueryParameter("year", it.toString()) }
                    }

                    feature.isSeries -> addQueryParameter("parent_feature_id", feature.id.toString())
                    else -> addQueryParameter("id", feature.id.toString())
                }
                query.season?.let { addQueryParameter("season_number", it.toString()) }
                query.episode?.let { addQueryParameter("episode_number", it.toString()) }
                hash?.let { addQueryParameter("moviehash", it.asSubtitleHash()) }
                if (languages.isNotEmpty()) {
                    addQueryParameter("languages", languages.joinToString(","))
                }
            }
            .build()

        client.newCall(get(url).build()).execute().use {
            if (!it.isSuccessful) return SubtitleResult.Refused(searchFailure(it.code))
            val body = it.body?.string() ?: return SubtitleResult.Refused(SubtitleFailure.UNREADABLE)
            val offers = offers(body)
            return SubtitleResult.Found(
                if (feature == null) {
                    SubtitleMatch.relevant(query, offers)
                } else {
                    SubtitleMatch.ofFeature(query, offers)
                },
            )
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
     *
     * ## What this does not do
     *
     * It does not count. Nothing in this class touches the day's download tally — not the
     * request, not a `200`, not a parsed file. This returns cues or a reason, and whether
     * that amounts to a download is decided in one place, once, after the cues are actually
     * in use. A counter incremented where a byte arrives is a counter that charges people
     * for failures.
     *
     * [fileId] is carried only so the log line can name what was being fetched.
     */
    suspend fun fetch(link: String, fileId: Long = 0L): SubtitleResult<SubtitleTrack> = call {
        val response = client.newCall(Request.Builder().url(link).build()).execute()
        response.use {
            val status = it.code
            if (!it.isSuccessful) {
                return@call refusedDownload(fileId, status, "HTTP_ERROR", failure(status))
            }
            val body = it.body
                ?: return@call refusedDownload(fileId, status, "EMPTY_RESPONSE", SubtitleFailure.UNREADABLE)
            val track = body.charStream().buffered().use(SrtParser::parse)
            if (track.cues.isEmpty()) {
                return@call refusedDownload(fileId, status, "INVALID_SUBTITLE_FILE", SubtitleFailure.UNREADABLE)
            }
            Log.i(
                TAG,
                "downloadStarted=true subtitleId=$fileId httpStatus=$status fileValid=true " +
                    "cues=${track.cues.size} downloadSuccess=true",
            )
            SubtitleResult.Found(track)
        }
    }

    /**
     * A download that did not happen, said in the terms the diagnosis needs.
     *
     * `quotaIncremented=false` on every one of them, and the reason beside it, because the
     * question these logs exist to answer is "why does it say the day is used up" — and the
     * answer is usually that something which was not a download was being counted as one.
     */
    private fun refusedDownload(
        fileId: Long,
        status: Int,
        reason: String,
        failure: SubtitleFailure,
    ): SubtitleResult<SubtitleTrack> {
        Log.w(
            TAG,
            "downloadStarted=true subtitleId=$fileId httpStatus=$status " +
                "downloadSuccess=false quotaIncremented=false reason=$reason failure=$failure",
        )
        return SubtitleResult.Refused(failure)
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
     * Two of these were one, and the conflation was a defect people could not work around.
     *
     *  - **406** is OpenSubtitles saying the account's *downloads* for the day are gone. It
     *    can only arrive on a download, and "try again tomorrow" is the right answer to it.
     *  - **429** is *too many requests*: a throttle, on any endpoint, cleared by waiting a
     *    moment. A search is several requests now, so it is the likelier of the two to be
     *    met — and it used to be reported as a spent download quota, to viewers who had
     *    downloaded nothing that day and for whom tomorrow would change nothing.
     *
     * They are separate values with separate sentences, and 406 is not reachable from a
     * search at all.
     */
    private fun failure(code: Int): SubtitleFailure = when (code) {
        401, 403 -> SubtitleFailure.REFUSED
        406 -> SubtitleFailure.OUT_OF_DOWNLOADS
        429 -> SubtitleFailure.RATE_LIMITED
        in 500..599 -> SubtitleFailure.NETWORK
        else -> SubtitleFailure.UNREADABLE
    }

    /**
     * The same, for a request that is not a download — and [SubtitleFailure.OUT_OF_DOWNLOADS]
     * is not in it.
     *
     * The rule made structural instead of merely intended. "The day's downloads are used up"
     * is a sentence about downloads, so no lookup is able to cause it however the server
     * answers: a `406` on a search, which OpenSubtitles does not send but which no client can
     * prevent, comes out as a throttle. That is the entire defect closed at the type level —
     * a search cannot produce the sentence, so it cannot produce it wrongly.
     */
    private fun searchFailure(code: Int): SubtitleFailure = when (val reason = failure(code)) {
        SubtitleFailure.OUT_OF_DOWNLOADS -> SubtitleFailure.RATE_LIMITED
        else -> reason
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
            // why `SubtitleMatch` has a second, stricter rule for judging a release name.
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
        // Unordered on purpose. The ranking belongs with the filter that knows what was
        // asked for -- the top row should be the one that fits *this file*, and half of that
        // judgement is the query. `SubtitleMatch` does both in one pass.
        return offers
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

        /** What `/features` calls the two kinds of row that carry a series' identifier. */
        private const val TV_SHOW = "Tvshow"
        private const val EPISODE = "Episode"

        private val JSON = "application/json".toMediaType()
    }
}
