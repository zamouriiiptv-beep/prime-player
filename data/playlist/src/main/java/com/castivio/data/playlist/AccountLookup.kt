package com.castivio.data.playlist

import com.castivio.domain.PlaylistSource
import com.castivio.domain.SourceRepository
import com.castivio.domain.asAccountSource
import java.util.concurrent.ConcurrentHashMap

/**
 * The panel account a subscription is read through, remembered for a few minutes.
 *
 * A show's page and a film's page each asked the catalogue for it -- a read of its own, in the line of the others the
 * page's first screen is waiting on -- and it does not change between one page and the next. The source itself for Xtream,
 * and the account a playlist's link names for M3U; null for a source with no panel to ask.
 */
class AccountLookup(
    private val sources: SourceRepository,
    private val clock: () -> Long,
) {
    private class Held(val account: PlaylistSource.Xtream, val atMs: Long)

    private val held = ConcurrentHashMap<String, Held>()

    suspend fun of(sourceId: String): PlaylistSource.Xtream? {
        held[sourceId]?.let { if (clock() - it.atMs < MEMORY_MS) return it.account }
        val account = when (val source = sources.get(sourceId)?.asAccountSource()) {
            is PlaylistSource.Xtream -> source
            is PlaylistSource.M3u -> source.xtream
            else -> null
        } ?: return null
        held[sourceId] = Held(account, clock())
        return account
    }

    /** A provider that refused the account is read again next time: the credentials may have been edited since. */
    fun forget(sourceId: String) {
        held.remove(sourceId)
    }

    private companion object {
        /** Long enough to cover a session of browsing, short enough that an edited password is not ignored for it. */
        const val MEMORY_MS = 5 * 60 * 1000L
    }
}
