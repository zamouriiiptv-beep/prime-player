package com.castivio.domain.activation

import com.castivio.domain.PlaylistSource
import com.castivio.domain.provider.FieldValidation
import com.castivio.domain.provider.M3uFormCheck
import com.castivio.domain.provider.XtreamFormCheck

/**
 * What the user has typed so far, and whether it is enough to try.
 *
 * A sealed pair rather than one class with every field and a mode flag: an Xtream
 * form has a username, a playlist form does not, and a shape that admits both is a
 * shape where "which fields matter now" is answered at runtime by something that
 * could be wrong. Here the compiler answers it.
 *
 * Every property is derived. There is no cached validity to fall out of step with the
 * text, which is the bug that produces a Continue button enabled over an empty field.
 */
sealed interface ActivationForm {

    /** Optional, as the user decided. Empty means "pick a sensible default for me". */
    val name: String

    val canSubmit: Boolean

    /** The label to store, or null to let the data layer derive one from the host. */
    val label: String?

    /** What to hand the importer, or null while anything is still wrong. */
    val source: PlaylistSource?

    data class Xtream(
        override val name: String = "",
        val serverUrl: String = "",
        val username: String = "",
        val password: String = "",
    ) : ActivationForm {

        val checked: XtreamFormCheck
            get() = XtreamFormCheck.of(name, serverUrl, username, password)

        override val canSubmit: Boolean get() = checked.canSubmit
        override val label: String? get() = checked.label
        override val source: PlaylistSource.Xtream? get() = checked.source
    }

    data class Playlist(
        override val name: String = "",
        val url: String = "",
    ) : ActivationForm {

        val checked: M3uFormCheck get() = M3uFormCheck.of(name, url)

        override val canSubmit: Boolean get() = checked.canSubmit
        override val label: String? get() = checked.label
        override val source: PlaylistSource.M3u? get() = checked.source

        /**
         * The Xtream credentials hiding in this URL, when there are any.
         *
         * An offer for the screen to make, never a rewrite. `get.php?username=…` is
         * the link providers actually e-mail, and it works as a playlist — but read as
         * Xtream it also carries categories, series, catch-up and a subscription status
         * the app can show. See [FieldValidation.detectXtream].
         */
        val detectedXtream: PlaylistSource.Xtream? get() = checked.xtream
    }
}

/** The Xtream form the user gets after accepting a detected link, with the fields filled. */
fun PlaylistSource.Xtream.asForm(name: String = ""): ActivationForm.Xtream = ActivationForm.Xtream(
    name = name,
    serverUrl = host,
    username = username,
    password = password,
)
