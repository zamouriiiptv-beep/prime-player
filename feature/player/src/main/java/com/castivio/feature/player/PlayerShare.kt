package com.castivio.feature.player

/**
 * What the share button is allowed to hand to another application.
 *
 * ## Why this is a decision and not an intent builder
 *
 * A player has two kinds of source and they must not be shared the same way. A file on the
 * device is the user's own and sharing it is the obvious thing: the receiving application
 * gets the file. A subscription stream is a URL with the subscriber's username, password or
 * session token in its query, and handing that to a messaging application is handing over
 * the account — the same reason `Media3Engine` strips the query before a URL reaches a
 * diagnostic report.
 *
 * So the rule is written once, here, where it can be tested without an `Intent`, an
 * `Activity` or a device: the screen asks what may be shared and then builds the intent for
 * whichever answer it gets.
 */
internal sealed interface ShareOffer {

    /** A file on the device, handed over as itself. */
    data class File(val uri: String, val title: String) : ShareOffer

    /**
     * A name, and nothing else.
     *
     * Deliberately not the URL. It is worth less to the person receiving it and it is the
     * only version of this that cannot leak a subscription.
     */
    data class Words(val title: String) : ShareOffer
}

/**
 * What may be shared for a given source.
 *
 * `content://` and `file://` are the device's own schemes and nothing else is treated as
 * local: an `http://` URL that happens to point at a home server is still a URL that may
 * carry credentials, and a rule that tried to tell those apart would be a rule that is
 * sometimes wrong about a password.
 */
internal fun shareOffer(request: PlayerRequest): ShareOffer {
    val url = request.url
    val local = LOCAL_SCHEMES.any { url.startsWith(it, ignoreCase = true) }
    return if (local) {
        ShareOffer.File(uri = url, title = request.title)
    } else {
        ShareOffer.Words(title = request.title)
    }
}

private val LOCAL_SCHEMES = listOf("content://", "file://")
