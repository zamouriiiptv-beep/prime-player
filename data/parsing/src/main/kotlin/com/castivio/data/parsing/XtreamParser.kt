package com.castivio.data.parsing

import com.castivio.domain.MediaKind
import java.io.Reader

/**
 * Reads Xtream Codes API responses.
 *
 * Streaming, for the reason that shapes this whole layer: `get_vod_streams` for a
 * large provider is tens of megabytes of JSON, and a category can hold twenty
 * thousand entries. Nothing here builds a list — every response is handed to a
 * callback one row at a time.
 *
 * Everything is lenient. Real Xtream servers send numbers as strings and strings
 * as numbers, `null` where text belongs, `"0"` for false, base64 for EPG titles,
 * and occasionally an empty string where an id should be. A strict parser here
 * would mean failing to import from providers that work fine in every other
 * player.
 */
object XtreamParser {

    /**
     * `get_live_categories`, `get_vod_categories`, `get_series_categories`.
     *
     * Categories are the whole reason Xtream is fast: a few hundred rows arrive
     * instantly and the streams inside one load only when the user opens it, so a
     * 100,000-channel provider never has to be downloaded at all.
     */
    fun parseCategories(reader: Reader, kind: MediaKind, onCategory: (XtreamCategory) -> Unit): Int {
        var count = 0
        val scanner = JsonScanner(reader)
        scanner.readArray {
            var id: String? = null
            var name: String? = null
            scanner.readObject { field ->
                when (field) {
                    "category_id" -> id = scanner.string()
                    "category_name" -> name = scanner.string()
                }
            }
            val categoryId = id
            if (categoryId != null) {
                onCategory(XtreamCategory(categoryId, name ?: categoryId, kind))
                count++
            }
        }
        return count
    }

    /**
     * `get_live_streams` and `get_vod_streams`.
     *
     * One parser for both: the field sets overlap almost entirely, and reading a
     * field a given response does not contain costs nothing.
     */
    fun parseStreams(reader: Reader, onStream: (XtreamStream) -> Unit): Int {
        var count = 0
        val scanner = JsonScanner(reader)
        scanner.readArray {
            var streamId: String? = null
            var name: String? = null
            var icon: String? = null
            var categoryId: String? = null
            var epgChannelId: String? = null
            var extension: String? = null
            var archiveDays = 0
            var hasArchive = false
            var number: Int? = null
            var added: Long? = null

            scanner.readObject { field ->
                when (field) {
                    "stream_id" -> streamId = scanner.string()
                    "name" -> name = scanner.string()
                    "stream_icon", "cover" -> icon = scanner.string()
                    "category_id" -> categoryId = scanner.string()
                    "epg_channel_id" -> epgChannelId = scanner.string()
                    "container_extension" -> extension = scanner.string()
                    // Days, not hours — a provider offering "7" means a week.
                    "tv_archive_duration" -> archiveDays = scanner.int()
                    "tv_archive" -> hasArchive = scanner.boolean()
                    "num" -> number = scanner.int()
                    "added" -> added = scanner.long()
                }
            }

            val id = streamId
            val title = name
            if (id != null && title != null) {
                onStream(
                    XtreamStream(
                        streamId = id,
                        name = title,
                        iconUrl = icon,
                        categoryId = categoryId,
                        epgChannelId = epgChannelId,
                        containerExtension = extension,
                        // Only a real archive counts: a provider that reports
                        // `tv_archive: 0` with a duration is not offering catch-up,
                        // and showing a rewind control that fails is worse than
                        // not showing one.
                        catchUpHours = if (hasArchive && archiveDays > 0) archiveDays * 24 else null,
                        number = number?.takeIf { it > 0 },
                        addedEpochSeconds = added?.takeIf { it > 0 },
                    ),
                )
                count++
            }
        }
        return count
    }

    /** `get_series` — the show list, without episodes. */
    fun parseSeries(reader: Reader, onSeries: (XtreamSeries) -> Unit): Int {
        var count = 0
        val scanner = JsonScanner(reader)
        scanner.readArray {
            var seriesId: String? = null
            var name: String? = null
            var cover: String? = null
            var categoryId: String? = null
            var plot: String? = null
            var lastModified: Long? = null

            scanner.readObject { field ->
                when (field) {
                    "series_id" -> seriesId = scanner.string()
                    "name" -> name = scanner.string()
                    "cover" -> cover = scanner.string()
                    "category_id" -> categoryId = scanner.string()
                    "plot" -> plot = scanner.string()
                    "last_modified" -> lastModified = scanner.long()
                }
            }

            val id = seriesId
            val title = name
            if (id != null && title != null) {
                onSeries(XtreamSeries(id, title, cover, categoryId, plot, lastModified?.takeIf { it > 0 }))
                count++
            }
        }
        return count
    }

    /**
     * `get_series_info` — one show's episodes.
     *
     * The response keys episodes by season number in an *object*, not an array,
     * so the season comes from the key when the episode itself does not carry it.
     */
    fun parseSeriesInfo(reader: Reader, onEpisode: (XtreamEpisode) -> Unit): Int {
        var count = 0
        val scanner = JsonScanner(reader)
        scanner.readObject { field ->
            if (field != "episodes") return@readObject
            scanner.readObject { seasonKey ->
                val seasonFromKey = seasonKey.toIntOrNull()
                scanner.readArray {
                    var episodeId: String? = null
                    var title: String? = null
                    var season: Int? = null
                    var episode: Int? = null
                    var extension: String? = null
                    var cover: String? = null
                    var durationSeconds: Int? = null

                    scanner.readObject { episodeField ->
                        when (episodeField) {
                            "id" -> episodeId = scanner.string()
                            "title" -> title = scanner.string()
                            "season" -> season = scanner.int()
                            "episode_num" -> episode = scanner.int()
                            "container_extension" -> extension = scanner.string()
                            "info" -> scanner.readObject { infoField ->
                                when (infoField) {
                                    "movie_image", "cover_big" -> cover = scanner.string()
                                    "duration_secs" -> durationSeconds = scanner.int()
                                }
                            }
                        }
                    }

                    val id = episodeId
                    if (id != null) {
                        onEpisode(
                            XtreamEpisode(
                                episodeId = id,
                                title = title ?: "Episode ${episode ?: count + 1}",
                                seasonNumber = season ?: seasonFromKey ?: 1,
                                episodeNumber = episode ?: (count + 1),
                                containerExtension = extension,
                                coverUrl = cover,
                                durationSeconds = durationSeconds?.takeIf { it > 0 },
                            ),
                        )
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * The login response: `player_api.php?username=&password=` with no action.
     *
     * This is how activation is validated — an expired or disabled account has to
     * be reported as such rather than as an empty catalogue.
     */
    fun parseAccount(reader: Reader): XtreamAccount? {
        var found = false
        var auth = true
        var status: String? = null
        var expires: Long? = null
        var trial = false
        var active = 0
        var maximum = 0
        var timezone: String? = null

        val scanner = JsonScanner(reader)
        scanner.readObject { field ->
            when (field) {
                "user_info" -> {
                    found = true
                    scanner.readObject { userField ->
                        when (userField) {
                            "auth" -> auth = scanner.boolean()
                            "status" -> status = scanner.string()
                            "exp_date" -> expires = scanner.long()
                            "is_trial" -> trial = scanner.boolean()
                            "active_cons" -> active = scanner.int()
                            "max_connections" -> maximum = scanner.int()
                        }
                    }
                }
                "server_info" -> scanner.readObject { serverField ->
                    if (serverField == "timezone") timezone = scanner.string()
                }
            }
        }

        if (!found) return null
        return XtreamAccount(
            authenticated = auth,
            status = status,
            // Xtream sends seconds; a zero or missing value means "never expires".
            expiresAtMs = expires?.takeIf { it > 0 }?.times(1000L),
            isTrial = trial,
            activeConnections = active,
            maxConnections = maximum,
            timezone = timezone,
        )
    }

    /**
     * `get_short_epg` — now/next for one channel, in kilobytes.
     *
     * Preferred over downloading a full XMLTV guide when all the user needs is
     * what is on: the difference is a 2 KB response versus 100 MB.
     *
     * Titles and descriptions are base64 in most implementations and plain text in
     * a few, so [decodeBase64OrSelf] handles both.
     */
    fun parseShortEpg(reader: Reader, onEntry: (XtreamEpgEntry) -> Unit): Int {
        var count = 0
        val scanner = JsonScanner(reader)
        scanner.readObject { field ->
            if (field != "epg_listings") return@readObject
            scanner.readArray {
                var channelId: String? = null
                var title: String? = null
                var description: String? = null
                var start = 0L
                var stop = 0L

                scanner.readObject { entryField ->
                    when (entryField) {
                        "channel_id" -> channelId = scanner.string()
                        "title" -> title = scanner.string()?.let(::decodeBase64OrSelf)
                        "description" -> description = scanner.string()?.let(::decodeBase64OrSelf)
                        // Epoch seconds, and far more reliable than the local-time
                        // strings the same response also carries.
                        "start_timestamp" -> start = scanner.long()
                        "stop_timestamp" -> stop = scanner.long()
                    }
                }

                val id = channelId
                if (id != null && start > 0) {
                    onEntry(
                        XtreamEpgEntry(
                            channelId = id,
                            title = title ?: "",
                            description = description,
                            startMs = start * 1000L,
                            stopMs = if (stop > start) stop * 1000L else start * 1000L + DEFAULT_PROGRAMME_MS,
                        ),
                    )
                    count++
                }
            }
        }
        return count
    }

    /**
     * Decodes base64, or returns the input when it is not base64.
     *
     * `java.util.Base64` is API 26 and `android.util.Base64` is Android-only, and
     * this module is deliberately pure Kotlin — so it is done by hand. The
     * fallback matters: providers disagree about whether EPG titles are encoded,
     * and showing `U3BvcnQ=` as a programme title is worse than either.
     */
    internal fun decodeBase64OrSelf(value: String): String {
        if (value.length < 4 || value.length % 4 != 0) return value
        var accumulator = 0
        var bits = 0
        val out = ByteArray(value.length / 4 * 3)
        var written = 0
        for (c in value) {
            if (c == '=') break
            val digit = BASE64_DIGITS.indexOf(c)
            if (digit < 0) return value // not base64 after all
            accumulator = (accumulator shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[written++] = ((accumulator shr bits) and 0xFF).toByte()
            }
        }
        if (written == 0) return value
        val decoded = String(out, 0, written, Charsets.UTF_8)
        // A decode that yields control characters — or U+FFFD, which is what
        // invalid UTF-8 turns into — was not text to begin with. `////` is valid
        // base64 and decodes to 0xFF bytes; showing those as a programme title
        // would be worse than showing the provider's own string.
        val isText = decoded.none { it == '�' || (it.isISOControl() && it != '\n' && it != '\t') }
        return if (isText) decoded else value
    }

    private const val BASE64_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** When a provider gives a start but no usable stop. */
    private const val DEFAULT_PROGRAMME_MS = 30 * 60 * 1000L
}

data class XtreamCategory(val id: String, val name: String, val kind: MediaKind)

data class XtreamStream(
    val streamId: String,
    val name: String,
    val iconUrl: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    val containerExtension: String?,
    /** Non-null only when the provider genuinely exposes an archive. */
    val catchUpHours: Int?,
    val number: Int?,
    val addedEpochSeconds: Long?,
)

data class XtreamSeries(
    val seriesId: String,
    val name: String,
    val coverUrl: String?,
    val categoryId: String?,
    val plot: String?,
    val lastModifiedEpochSeconds: Long?,
)

data class XtreamEpisode(
    val episodeId: String,
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val containerExtension: String?,
    val coverUrl: String?,
    val durationSeconds: Int?,
)

/** The account behind the credentials, including why it might not work. */
data class XtreamAccount(
    val authenticated: Boolean,
    val status: String?,
    val expiresAtMs: Long?,
    val isTrial: Boolean,
    val activeConnections: Int,
    val maxConnections: Int,
    val timezone: String?,
) {
    /** `Active` is the usual value; some panels send `Banned` or `Expired`. */
    val isUsable: Boolean
        get() = authenticated && (status == null || status.equals("Active", ignoreCase = true))

    fun isExpiredAt(nowMs: Long): Boolean = expiresAtMs?.let { it in 1 until nowMs } == true

    /** True when every allowed connection is already in use elsewhere. */
    val atConnectionLimit: Boolean
        get() = maxConnections in 1..activeConnections
}

data class XtreamEpgEntry(
    val channelId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val stopMs: Long,
)
