package com.castivio.tv.shell

import com.castivio.core.design.components.WatchState

/**
 * Mock data for the UX-validation build.
 *
 * This exists so the shell can be evaluated on a real phone before any provider is
 * wired in — navigation, focus, typography, spacing, colour, motion and feel. It is
 * deliberately fixed and obviously not real: no network, no database, no import.
 * The real Home reads the same shapes from a repository through a ViewModel; this is
 * the harness that let the look be signed off first.
 */
data class DemoChannel(
    val id: String,
    val name: String,
    val nowPlaying: String,
    val number: String,
    val seed: Int,
    val watch: WatchState = WatchState.None,
)

data class DemoPoster(
    val id: String,
    val title: String,
    val subtitle: String,
    val seed: Int,
    val watch: WatchState = WatchState.None,
)

data class DemoResumeItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val caption: String,
    val seed: Int,
    val progress: Float,
)

data class DemoCategory(val id: String, val label: String, val count: Int)

object DemoCatalog {

    const val PROVIDER = "Nova IPTV"

    // Cached counts, as a real provider would report them.
    const val LIVE_COUNT = 12_480
    const val MOVIE_COUNT = 24_318
    const val SERIES_COUNT = 3_904
    const val RADIO_COUNT = 0 // this provider carries no radio — the empty-section case
    const val FAVOURITE_COUNT = 38

    val spotlightTitle = "UEFA Cup Final — Real Madrid v Inter"
    val spotlightChannel = "Nova Sports 1"
    val spotlightNumber = "101"
    val spotlightStart = "20:45"
    val spotlightEnd = "22:30"
    val spotlightNext = "Post-match analysis"
    const val spotlightProgress = 0.44f

    val continueWatching = listOf(
        DemoResumeItem("cw1", "Dune: Part Two", "Movie · 2024", "42 min left", 0, 0.58f),
        DemoResumeItem("cw2", "The Last Frontier", "Series · S2 E4", "S2 E4", 1, 0.31f),
        DemoResumeItem("cw3", "Sicario", "Movie · 2015", "1 h 06 left", 3, 0.22f),
        DemoResumeItem("cw4", "Blue Planet II", "Series · S1 E9", "S1 E9", 2, 0.73f),
        DemoResumeItem("cw5", "Oppenheimer", "Movie · 2023", "1 h 30 left", 4, 0.12f),
    )

    val liveChannels = listOf(
        DemoChannel("l1", "Nova Sports 1", "UEFA Cup Final", "101", 0, WatchState.Playing),
        DemoChannel("l2", "BBC One HD", "Ten O'Clock News", "102", 1, WatchState.Watched),
        DemoChannel("l3", "Al Jazeera", "الحصاد", "103", 4),
        DemoChannel("l4", "CNN International", "Quest Means Business", "104", 2),
        DemoChannel("l5", "MBC 2", "فيلم السهرة", "105", 3),
        DemoChannel("l6", "beIN Sports 1", "Match of the Day", "106", 5),
        DemoChannel("l7", "National Geographic", "Wild Arabia", "107", 6),
        DemoChannel("l8", "Sky News", "The World Tonight", "108", 1),
    )

    val movies = listOf(
        DemoPoster("m1", "Oppenheimer", "2023 · 3 h 00", 0),
        DemoPoster("m2", "Interstellar", "2014 · 2 h 49", 1),
        DemoPoster("m3", "Heat", "1995 · 2 h 50", 3),
        DemoPoster("m4", "Arrival", "2016 · 1 h 56", 2),
        DemoPoster("m5", "Blade Runner 2049", "2017 · 2 h 44", 5),
        DemoPoster("m6", "Dune", "2021 · 2 h 35", 4, WatchState.Watched),
        DemoPoster("m7", "Sicario", "2015 · 2 h 01", 1, WatchState.InProgress(0.22f)),
        DemoPoster("m8", "Tenet", "2020 · 2 h 30", 0),
    )

    val series = listOf(
        DemoPoster("s1", "The Last Frontier", "2 seasons", 1, WatchState.InProgress(0.31f)),
        DemoPoster("s2", "Blue Planet II", "1 season", 2, WatchState.InProgress(0.73f)),
        DemoPoster("s3", "Dark Matter", "3 seasons", 5),
        DemoPoster("s4", "The Bureau", "5 seasons", 3),
        DemoPoster("s5", "Chernobyl", "1 season", 4),
        DemoPoster("s6", "Severance", "2 seasons", 0),
    )

    val movieCategories = listOf(
        DemoCategory("all", "All movies", MOVIE_COUNT),
        DemoCategory("action", "Action", 4_182),
        DemoCategory("arabic", "Arabic", 2_940),
        DemoCategory("comedy", "Comedy", 3_106),
        DemoCategory("drama", "Drama", 5_477),
        DemoCategory("scifi", "Sci-Fi", 1_392),
        DemoCategory("thriller", "Thriller", 2_236),
    )

    val liveCategories = listOf(
        DemoCategory("all", "All channels", LIVE_COUNT),
        DemoCategory("sports", "Sports", 1_204),
        DemoCategory("news", "News", 862),
        DemoCategory("movies", "Movies", 2_040),
        DemoCategory("kids", "Kids", 511),
        DemoCategory("arabic", "Arabic", 3_318),
    )

    /** A flat title index the demo search filters over. */
    val searchable: List<DemoPoster> = movies + series
}
