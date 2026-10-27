package com.castivio.domain

/**
 * One show's episodes, asked of its provider when the show is opened and written to the catalogue.
 *
 * The catalogue holds a show as a row with no episodes -- listing six hundred of them would cost six hundred
 * requests to show a few -- so the episodes of the one a viewer opens are fetched then, and the season list
 * the page reads is what this has written. A show already holding episodes is fetched again as well, because
 * a show that is still running has new ones.
 */
interface SeriesEpisodes {

    /** Asks the show's provider for its episodes and stores them. Never throws for a provider that cannot answer. */
    suspend fun load(seriesId: String): EpisodesLoad
}

/** How asking a provider for a show's episodes ended. */
sealed interface EpisodesLoad {

    /** The provider answered, with [episodes] of them. Zero is an answer: the provider lists the show and has none. */
    data class Loaded(val episodes: Int) : EpisodesLoad

    /** There is nobody to ask: the show has no row of its own, or its source cannot be asked for episodes. */
    data object NoProvider : EpisodesLoad

    /** The provider could not be reached, or answered with something that is not a list of episodes. */
    data class Failed(val cause: Throwable) : EpisodesLoad
}
