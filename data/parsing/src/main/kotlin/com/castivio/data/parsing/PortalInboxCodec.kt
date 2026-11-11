package com.castivio.data.parsing

import com.castivio.domain.portal.SentSource
import com.castivio.domain.portal.SentSubscription
import java.io.Reader

/**
 * The portal's JSON, in both directions: what it sends the device, and what the device sends it.
 *
 * Nothing here knows a server, a path or a network. It is given a reader and hands back
 * values, or is given values and hands back a string, so the whole exchange is a unit test.
 *
 * ## Reading
 *
 * Tolerant in the way the rest of this package is, and for the same reason: an element the
 * device cannot use must cost that element and not the answer. A subscription of a kind this
 * build has no form for — the portal is ahead of the app, and will be — is left out and stays
 * waiting on the website, where it is true to say the device has not taken it. One with a
 * field missing that its kind cannot do without is left out too.
 *
 * What is **not** tolerated is a document that is not an object at all. An HTML error page
 * served with a 200 must reach the caller as a parse failure, not as "nothing was sent",
 * which would tell the owner their subscription was never there.
 *
 * ## Writing
 *
 * By hand, and in one place, because the bodies are four short objects and a JSON library
 * would be a dependency for the sake of escaping a handful of strings. [quote] is the whole
 * of the escaping, and `PortalInboxCodecTest` holds it to the rules JSON has.
 */
object PortalInboxCodec {

    /** `POST /device/inbox`'s answer: `{"playlists":[{id, version, name, sourceType, …}]}`. */
    fun parseWaiting(reader: Reader): List<SentSubscription> {
        val found = ArrayList<SentSubscription>()
        val scanner = JsonScanner(reader)
        scanner.readObject { field ->
            if (field == "playlists") {
                scanner.readArray {
                    readSubscription(scanner)?.let(found::add)
                }
            }
        }
        return found
    }

    private fun readSubscription(scanner: JsonScanner): SentSubscription? {
        var id: String? = null
        var version: String? = null
        var name: String? = null
        var kind: String? = null
        var server: String? = null
        var username: String? = null
        var password: String? = null
        var link: String? = null

        scanner.readObject { field ->
            when (field) {
                "id" -> id = scanner.string()
                "version" -> version = scanner.string()
                "name" -> name = scanner.string()
                "sourceType" -> kind = scanner.string()
                "serverUrl" -> server = scanner.string()
                "username" -> username = scanner.string()
                "password" -> password = scanner.string()
                "m3uUrl" -> link = scanner.string()
            }
        }

        val sentId = id?.takeIf { it.isNotBlank() } ?: return null
        val sentVersion = version?.takeIf { it.isNotBlank() } ?: return null
        val source: SentSource = when (kind) {
            "xtream" -> SentSource.Xtream(
                server = server ?: return null,
                username = username ?: return null,
                password = password ?: return null,
            )

            "m3u_url" -> SentSource.Playlist(link ?: return null)

            else -> return null
        }
        return SentSubscription(sentId, sentVersion, name?.trim().orEmpty(), source)
    }

    /** `POST /device/register`. [modelName] and [platform] are what the device reports, and are left out when it reports nothing. */
    fun registerBody(mac: String, key: String, modelName: String?, platform: String?): String = buildString {
        append('{')
        append("\"macAddress\":").append(quote(mac))
        append(",\"deviceKey\":").append(quote(key))
        if (!modelName.isNullOrBlank()) append(",\"modelName\":").append(quote(modelName))
        if (!platform.isNullOrBlank()) append(",\"platform\":").append(quote(platform))
        append('}')
    }

    /** `POST /device/inbox`. */
    fun inboxBody(mac: String, key: String): String =
        "{\"macAddress\":${quote(mac)},\"deviceKey\":${quote(key)}}"

    /** `POST /device/inbox/ack`: the exact versions that were added. */
    fun acknowledgeBody(mac: String, key: String, taken: List<SentSubscription>): String = buildString {
        append('{')
        append("\"macAddress\":").append(quote(mac))
        append(",\"deviceKey\":").append(quote(key))
        append(",\"delivered\":[")
        taken.forEachIndexed { index, sent ->
            if (index > 0) append(',')
            append("{\"id\":").append(quote(sent.id))
            append(",\"version\":").append(quote(sent.version)).append('}')
        }
        append("]}")
    }

    /** A JSON string literal: quotes and backslashes escaped, control characters as `\u00XX`. */
    internal fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}
