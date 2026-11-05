package com.castivio.domain

import com.castivio.core.common.PlayOutcome
import kotlinx.coroutines.flow.Flow

/**
 * What the viewer keeps of the world's stations: the favourites, the ones listened to last, and what listening has said about each.
 *
 * **Held apart from the subscription's own** -- its own store, its own tables, not one row shared -- so a catalogue import, a refresh or the
 * deletion of a subscription cannot touch it, and nothing here can reach the subscription's favourites or its recently watched.
 */
interface WorldRadioLibrary {
    /** The favourites, the one added last first. A handful the viewer chose: held whole. */
    fun favorites(): Flow<List<WorldStation>>

    /** The favourites' uuids, as the list changes, so a row asks whether it is one by a set lookup. */
    fun favoriteIds(): Flow<Set<String>>

    /** The stations listened to last, the latest first, at most [limit]. */
    fun recents(limit: Int): Flow<List<WorldStation>>

    /** Keeps [station] as a favourite, or lets it go; the copy kept is the one given, so it is there when the directory is not. */
    suspend fun setFavorite(station: WorldStation, favorite: Boolean)

    /** Notes that [station] was listened to at [nowMs]: what the recent list is read from. */
    suspend fun recordListened(station: WorldStation, nowMs: Long)

    /** What listening has said about each of [uuids]; a station it has said nothing about is absent. */
    suspend fun records(uuids: Collection<String>): Map<String, StationRecord>

    /** Writes down what a listen of [uuid] said, by [StationHealth.after]. An outcome that says nothing writes nothing. */
    suspend fun recordOutcome(uuid: String, outcome: PlayOutcome, nowMs: Long)

    /** Replaces what is kept of a favourite with the directory's newer word for it, if it is still a favourite. */
    suspend fun refreshSaved(station: WorldStation)
}
