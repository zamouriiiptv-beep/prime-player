package com.castivio.feature.player

import com.castivio.playback.api.MediaKind

/**
 * Everything the player is given when it opens, and everything it is allowed to have.
 *
 * ## The title is here, and that is the point
 *
 * The performance contract says the title must come from the item that opened the player
 * and never from a lookup. That is a rule somebody has to remember unless the type makes
 * it impossible to break — so it is the type: there is no id to resolve, no repository
 * handle, no way for the player to *ask* who this is. Everything the first frame needs is
 * a field, and the player has no means of fetching anything else before it draws.
 *
 * What is deliberately absent is as much of the design as what is present. No artwork
 * URL: a poster on the loading screen is a network request in front of the picture. No
 * programme, no synopsis, no episode list. Those arrive afterwards, into slots that are
 * already laid out, and the player asks for them only once there is a frame.
 *
 * [epgChannelId] is the one identifier that survives, and it is not used before the first
 * frame either — it is what the guide is fetched *with*, later.
 */
data class PlayerRequest(
    val url: String,
    /** Shown immediately. The only text on the loading screen besides the spinner. */
    val title: String,
    val kind: MediaKind,
    /** A channel number, an episode number, a year — whatever the opener already knew. */
    val subtitle: String? = null,
    val channelNumber: String? = null,
    val epgChannelId: String? = null,
    /**
     * Hours of archive the provider claims, or null.
     *
     * Null means the rewind affordance is not drawn at all. Castivio never shows a
     * disabled control, so "does this channel have catch-up" has to be answerable before
     * the tools row is composed — which is why it travels with the request rather than
     * being discovered from the stream.
     */
    val catchUpHours: Int? = null,
    /**
     * What the stream is asked for with, beyond its address. **Nothing fills it today.**
     *
     * A press in the catalogue becomes a request in the shell, and the selection it is built
     * from carries no headers and no agent: the importers read no per-channel options, and
     * the subscription's own user agent -- the one providers gate on -- goes with the
     * catalogue, account and guide requests and not with the stream. Both engines therefore
     * ask for the stream with their own default agent, which is not the one the catalogue
     * was fetched with. Carrying it here is a change to the selection and the shell, and it
     * has not been made.
     */
    val headers: Map<String, String> = emptyMap(),
    /** Not filled today either; see [headers]. */
    val userAgent: String? = null,
) {

    val isLive: Boolean get() = kind == MediaKind.LIVE

    /** Whether the timeshift controls exist on this source at all. */
    val supportsTimeshift: Boolean get() = isLive && (catchUpHours ?: 0) > 0
}
