package com.castivio.tv.debug.lab

/** The secondary indices of `media`, by the names Room gives them (see `MediaEntity`). */
internal object LabIndex {
    const val KIND_ORDER = "idx_media_source_kind_order"
    const val KIND_GROUP_ORDER = "idx_media_source_kind_group_order"
    const val SORT_TITLE = "idx_media_source_kind_sort_title"
    const val SERIES = "idx_media_series"
    const val GENERATION = "idx_media_source_generation"

    /** In the order the entity declares them. */
    val ALL: List<String> = listOf(KIND_ORDER, KIND_GROUP_ORDER, SORT_TITLE, SERIES, GENERATION)
}

/**
 * What is compared, and what each case does to the indices of its own scratch database.
 *
 * The indices a case drops are dropped *before* anything is timed, from a database Room has just created, so
 * every case starts from the same schema and differs only here. An index left out of the insert and built
 * afterwards ([rebuilt]) is built inside the timed total: a cost moved, not a cost removed.
 *
 * `idx_media_epg`, which the first runs of this lab measured and nothing read, was removed in database version 9, so
 * the two cases that were about it are gone with it. The lab times the writer with its own deferral of the
 * sort-title index switched off, so a case is what it says: the app's own first load now does what [C] does.
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
    A("A", "all five secondary indices kept up during the insert, as the app did before it deferred one", "the reference"),

    C(
        "C",
        "idx_media_source_kind_sort_title built after the insert",
        "deferral of sort_title: its build is in the total",
        dropped = listOf(LabIndex.SORT_TITLE),
        rebuilt = listOf(LabIndex.SORT_TITLE),
    ),

    D2(
        "D2",
        "all five secondary indices built after the insert",
        "deferral of every secondary index: all five builds are in the total",
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
