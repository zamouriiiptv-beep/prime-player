package com.castivio.data.epg

import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import com.castivio.domain.asPlaylistSource

/**
 * What the active subscription can be asked, as far as an Xtream panel's own endpoints go.
 *
 * Three answers and not a nullable, because a screen has to say different things to them: no subscription at all, a
 * subscription whose protocol this layer does not speak, and one it does.
 */
internal sealed interface ActiveProvider {

    /** No subscription is active, or the active one has no usable address and credentials. */
    data object None : ActiveProvider

    /** A subscription of a kind whose archive has not been checked against how it really works. */
    data object Other : ActiveProvider

    /** An Xtream panel, or a playlist read through one, with the account to ask it by. */
    data class Xtream(val source: ProviderSource) : ActiveProvider
}

/**
 * The active subscription as an [ActiveProvider].
 *
 * The same reading of the active source as `XtreamNowNextRefresher` makes for the guide: a playlist read through its
 * panel is asked with the account its link names, and which way a playlist is read is `asPlaylistSource`'s decision --
 * the one the section loader made -- so a playlist whose rows came from the file is not asked by stream id.
 */
internal suspend fun SourceRepository.activeProvider(): ActiveProvider {
    val source = activeNow() ?: return ActiveProvider.None
    val panel = (source.asPlaylistSource() as? PlaylistSource.M3u)?.xtream
    if (panel != null) {
        return ActiveProvider.Xtream(source.copy(url = panel.host, username = panel.username, password = panel.password))
    }
    if (source.kind != SourceKind.XTREAM) return ActiveProvider.Other
    if (source.url == null || source.username == null || source.password == null) return ActiveProvider.None
    return ActiveProvider.Xtream(source)
}
