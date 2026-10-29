package com.castivio.tv.debug.lab

/** The secondary indices of `media`, by the names Room gives them (see `MediaEntity`). */
internal object LabIndex {
    const val KIND_ORDER = "idx_media_source_kind_order"
    const val KIND_GROUP_ORDER = "idx_media_source_kind_group_order"
    const val SORT_TITLE = "idx_media_source_kind_sort_title"
    const val SERIES = "idx_media_series"
    const val GENERATION = "idx_media_source_generation"
    const val EPG = "idx_media_epg"

    /** In the order the entity declares them. */
    val ALL: List<String> = listOf(KIND_ORDER, KIND_GROUP_ORDER, SORT_TITLE, SERIES, GENERATION, EPG)
}

/**
 * What is compared, and what each case does to the indices of its own scratch database.
 *
 * The indices a case drops are dropped *before* anything is timed, from a database Room has just created, so
 * every case starts from the same schema and differs only here. An index left out of the insert and built
 * afterwards ([rebuilt]) is built inside the timed total: a cost moved, not a cost removed.
 *
 * Two cases leave `idx_media_epg` out, and they answer different questions, so they are never read as one:
 * [B_DEL] ends without the index (removing it for good, which nothing in the app reads), and [B_DEF] builds it
 * after the rows and pays for that in its total (deferring it).
 */
internal enum class LabCase(
    val code: String,
    val summary: String,
    /** What a difference from A means for this case; printed beside every difference so none is misread. */
    val caveat: String,
    val dropped: List<String> = emptyList(),
    val rebuilt: List<String> = emptyList(),
    /** A larger page cache for this case (kibibytes); 0 leaves SQLite's own. */
    val cacheKib: Int = 0,
) {
    A("A", "all six secondary indices, as the app has them", "the reference"),

    B_DEL(
        "B_del",
        "idx_media_epg removed for good (the schema ends without it)",
        "removal: ends with one index fewer than A; not a deferral",
        dropped = listOf(LabIndex.EPG),
    ),

    B_DEF(
        "B_def",
        "idx_media_epg built after the insert (the schema ends as A's)",
        "deferral of idx_media_epg: its build is in the total",
        dropped = listOf(LabIndex.EPG),
        rebuilt = listOf(LabIndex.EPG),
    ),

    C(
        "C",
        "idx_media_source_kind_sort_title built after the insert",
        "deferral of sort_title: its build is in the total",
        dropped = listOf(LabIndex.SORT_TITLE),
        rebuilt = listOf(LabIndex.SORT_TITLE),
    ),

    D2(
        "D2",
        "all six secondary indices built after the insert",
        "deferral of every secondary index: all six builds are in the total",
        dropped = LabIndex.ALL,
        rebuilt = LabIndex.ALL,
    ),

    A_C20(
        "A_c20",
        "A with a 20 MB page cache",
        "page cache size, not an index",
        cacheKib = 20_000,
    );

    /** The secondary indices `media` must have once the case is over. */
    fun expectedIndices(): Set<String> = (LabIndex.ALL - dropped.toSet() + rebuilt).toSet()
}
