package com.castivio.feature.player

import com.castivio.domain.TimeShiftSource
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
 * What is deliberately absent is as much of the design as what is present. No poster:
 * a poster on the loading screen is a network request in front of the picture. No
 * synopsis, no episode list. Those arrive afterwards, into slots that are already laid
 * out, and the player asks for them only once there is a frame.
 *
 * ## What a channel brings, and why it is not a fetch
 *
 * A channel press brings its [face]: its logo and what was on when it was pressed. Both are
 * already on the screen the press was made on -- the row drew the logo and the board held
 * the guide -- so the loading screen is handed them and asks for nothing.
 */
data class PlayerRequest(
    val url: String,
    /** Shown immediately. The only text on the loading screen besides the spinner. */
    val title: String,
    val kind: MediaKind,
    /** A channel number, an episode number, a year — whatever the opener already knew. */
    val subtitle: String? = null,
    val channelNumber: String? = null,
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
    /**
     * The id of the channel whose archive this programme is from, or null for everything that is not a catch-up programme.
     *
     * What it changes in the player is the card of a programme the provider lists and does not keep: it says so in words of
     * its own -- that this programme is missing, not that the channel is -- instead of the card of a link that moved.
     */
    val archiveOf: String? = null,
    /**
     * Only the sound is wanted, as the radio wants it: no picture is selected, decoded or waited for.
     *
     * Part of what makes a request, so the same address asked for as a station and as a channel is two requests
     * and the second replaces the first. See [MediaRequest.audioOnly] for why a station must say so.
     */
    val audioOnly: Boolean = false,
    /** What the loading screen of a channel shows beside its name, or null where the opener had none. See [OpeningFace]. */
    val face: OpeningFace? = null,
    /**
     * What puts this channel's picture behind the live edge, from its provider's archive, or null where nothing can: a film, a station,
     * a channel whose provider keeps no archive that Castivio can address. Handed in by the shell, which knows the catalogue and the
     * provider; the player knows nothing of either and only asks. See [TimeShiftHandle] and `PlayerViewModel.openTimeShift`.
     */
    val timeShift: TimeShiftHandle? = null,
) {

    /**
     * When the viewer pressed for this, on the clock the player measures with.
     *
     * Every request is built in the handler of the press that asked for it -- a catalogue row's
     * in the shell, a file's in its host -- so the moment it is made is the moment of the press,
     * and an opening can be timed from where the viewer started waiting rather than from
     * wherever the player happened to be told.
     *
     * In the body and not the constructor, which is the point: it is not part of what makes two
     * requests the same. Pressing the channel that is already playing builds an equal request,
     * which the player rightly treats as nothing to do. With the time in the constructor every
     * press would be a different request, and pressing a playing channel would open it again.
     */
    val pressedAtNanos: Long = System.nanoTime()

    val isLive: Boolean get() = kind == MediaKind.LIVE

    /** A programme of a channel's archive, played from Catch-up. */
    val isCatchUp: Boolean get() = archiveOf != null

    /**
     * Whether the timeshift controls can exist on this source at all: a channel, whose provider says it keeps an archive, and which the
     * shell gave a way to ask it. Not yet whether they are drawn: that waits for the archive to be proved to reach back -- see
     * [PlayerState.timeShift].
     */
    val supportsTimeshift: Boolean get() = isLive && (catchUpHours ?: 0) > 0 && timeShift != null
}

/**
 * A [TimeShiftSource] as a request carries it, **equal to every other**, as [OpeningFace] is.
 *
 * A request is compared to decide whether a press is for what is already playing, and a source is a means of rewinding what plays and
 * not a part of what it is: with the source part of the comparison, pressing the channel that is playing would be a different request
 * the moment the shell made a new source for it, and would open the channel again.
 */
class TimeShiftHandle(val source: TimeShiftSource) {
    override fun equals(other: Any?): Boolean = other is TimeShiftHandle

    override fun hashCode(): Int = 0
}

/**
 * The channel's logo and what was on it when it was pressed, for the loading screen only.
 *
 * **Every one of these is equal to every other**, which is the point of the type. A request is compared to decide whether a
 * press is for what is already playing, and what is on changes while a channel plays: with the programme part of the
 * comparison, pressing the channel that is playing a minute after the programme changed would be a different request and
 * would open it again. A face is decoration, and two requests that differ only in it are the same request.
 */
class OpeningFace(
    val artworkUrl: String?,
    val programme: String?,
    val programmeHours: String?,
) {
    override fun equals(other: Any?): Boolean = other is OpeningFace

    override fun hashCode(): Int = 0
}
