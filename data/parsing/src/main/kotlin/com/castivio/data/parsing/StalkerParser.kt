package com.castivio.data.parsing

import com.castivio.domain.MediaKind
import java.io.Reader

/**
 * Reads what a Stalker or Ministra portal answers.
 *
 * ## The envelope
 *
 * Every response is `{"js": …}` and nothing else is promised. `js` is an object for a
 * handshake and a profile, an object with a `data` array for a list, and — on the
 * portals that have nothing to say — `false`, an empty array, or an empty string. A
 * reader that assumed a shape would throw on a perfectly ordinary "no channels yet",
 * so each function below states what it needs and treats everything else as absent.
 *
 * ## Streamed, like everything else here
 *
 * A portal's channel list is one response holding every channel the account has, and
 * on a large provider that is tens of thousands. [channels] therefore hands them over
 * one at a time and holds nothing: the rule the rest of this module is built on, for
 * the same reason — a 1 GB stick with a 400,000-item library.
 */
object StalkerParser {

    /**
     * The token a handshake returns, or null if this was not a portal talking.
     *
     * Null is the useful answer here and not an error: it is what a web server
     * returning its index page produces, and the caller's next move — try the next
     * endpoint — depends on telling that apart from a portal that refused.
     */
    fun parseHandshake(reader: Reader): String? {
        var token: String? = null
        val scanner = JsonScanner(reader)
        scanner.readObject { field ->
            if (field == "js") {
                scanner.readObject { inner ->
                    when (inner) {
                        "token" -> token = scanner.string()
                        else -> scanner.skip()
                    }
                }
            } else {
                scanner.skip()
            }
        }
        return token?.takeIf { it.isNotBlank() }
    }

    /**
     * What the portal says about the account behind the token.
     *
     * The field names are the ones Stalker uses and Ministra kept. `status` is an
     * integer there rather than a word, and `0` is the healthy value — the opposite
     * convention to Xtream's `"Active"`, which is exactly the kind of difference that
     * has to be absorbed here rather than at the screen.
     */
    fun parseProfile(reader: Reader): StalkerProfile? {
        var found = false
        var blocked = false
        var expires: String? = null
        var status: Int? = null
        var name: String? = null

        val scanner = JsonScanner(reader)
        scanner.readObject { field ->
            if (field == "js") {
                scanner.readObject { inner ->
                    found = true
                    when (inner) {
                        "blocked" -> blocked = scanner.boolean()
                        "status" -> status = scanner.int()
                        "phone" -> expires = scanner.string()
                        "name" -> name = scanner.string()
                        else -> scanner.skip()
                    }
                }
            } else {
                scanner.skip()
            }
        }
        if (!found) return null
        return StalkerProfile(
            blocked = blocked,
            status = status,
            // Stalker has no expiry field of its own: resellers put the date in
            // `phone`, which is why it is read here and why it is a string the caller
            // may fail to make sense of rather than a timestamp this promises.
            expiresText = expires?.takeIf { it.isNotBlank() },
            accountName = name?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The channels in one page of `get_all_channels` or `get_ordered_list`.
     *
     * [onChannel] is called as each one is read and the reader never holds more than
     * the channel it is on. `total_items` arrives beside the array and is handed back
     * through [onTotal] when the portal states it, because paging needs to know when
     * to stop asking and a page that is merely empty is a weaker signal.
     */
    fun channels(
        reader: Reader,
        onTotal: (Int) -> Unit = {},
        onChannel: (StalkerChannel) -> Unit,
    ) {
        val scanner = JsonScanner(reader)
        scanner.readObject { field ->
            if (field != "js") {
                scanner.skip()
                return@readObject
            }
            scanner.readObject { inner ->
                when (inner) {
                    "total_items" -> onTotal(scanner.int())
                    "data" -> scanner.readArray {
                        readChannel(scanner)?.let(onChannel)
                    }
                    else -> scanner.skip()
                }
            }
        }
    }

    private fun readChannel(scanner: JsonScanner): StalkerChannel? {
        var id: String? = null
        var name: String? = null
        var command: String? = null
        var logo: String? = null
        var number: String? = null
        var archive = false

        scanner.readObject { field ->
            when (field) {
                "id" -> id = scanner.string()
                "name" -> name = scanner.string()
                "cmd" -> command = scanner.string()
                "logo" -> logo = scanner.string()
                "number" -> number = scanner.string()
                "tv_archive" -> archive = scanner.boolean()
                else -> scanner.skip()
            }
        }

        val channelId = id?.takeIf { it.isNotBlank() } ?: return null
        val title = name?.takeIf { it.isNotBlank() } ?: return null
        val cmd = command?.takeIf { it.isNotBlank() } ?: return null
        return StalkerChannel(
            id = channelId,
            name = title,
            command = cmd,
            logo = logo?.takeIf { it.isNotBlank() },
            number = number?.takeIf { it.isNotBlank() },
            hasArchive = archive,
        )
    }
}

/**
 * The account a token belongs to.
 *
 * Deliberately not [com.castivio.domain.ProviderStatus]: this says what the portal
 * said, in the portal's own terms, and the translation into the app's idea of usable
 * belongs where the two meet rather than in a parser.
 */
data class StalkerProfile(
    val blocked: Boolean,
    /** Stalker's own code. Zero is healthy; anything else is the portal refusing. */
    val status: Int?,
    /** Where resellers write an expiry date, when they write one at all. */
    val expiresText: String?,
    val accountName: String?,
) {
    val isUsable: Boolean get() = !blocked && (status == null || status == 0)
}

/**
 * One channel, as the portal describes it.
 *
 * [command] is the part that makes a portal a portal rather than a playlist: it is not
 * a URL but an instruction — `ffmpeg http://…`, or a `/media/…` reference the portal
 * resolves per play — so it is carried whole and read where a stream is actually
 * opened, never guessed at here.
 */
data class StalkerChannel(
    val id: String,
    val name: String,
    val command: String,
    val logo: String?,
    val number: String?,
    val hasArchive: Boolean,
) {
    /** Every channel a portal lists this way is live television. */
    val kind: MediaKind get() = MediaKind.LIVE

    /**
     * The address inside [command], when it carries one plainly.
     *
     * Portals send `ffmpeg http://host/stream` and `http://host/stream` about equally
     * often, and both mean the same thing to a player. A command that names neither —
     * the `/media/file.mpg` form a portal resolves on demand — returns null, because
     * inventing a URL for it would produce a link that 404s rather than an honest
     * "this needs the portal to resolve it".
     */
    val directUrl: String?
        get() {
            val trimmed = command.trim()
            val start = trimmed.indexOf("http")
            if (start < 0) return null
            return trimmed.substring(start).substringBefore(' ').takeIf { it.length > "http://".length }
        }
}
