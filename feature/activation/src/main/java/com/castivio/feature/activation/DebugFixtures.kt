package com.castivio.feature.activation

/**
 * Values a debug build shows so the approved composition can be judged on a real
 * device, and that a release build cannot reach.
 *
 * The rule these exist under is narrow and worth stating exactly. `482731` is
 * **not** a device key. Nothing derives it — not from the MAC, not from a random
 * source — and no temporary issuing scheme was stood up to make the row work. It
 * is a constant, in one file, behind `BuildConfig.DEBUG`, so that the screen the
 * design approved is the screen that goes on a phone. See
 * `design/activation-spec.md` §4.2 and §5.3.1.
 *
 * When the issuing contract exists, the key arrives from it and this file goes.
 * Not extended, not made conditional on a second flag: a fixture with two ways to
 * be reached is a fixture that eventually is.
 */
internal object DebugFixtures {

    /**
     * The six-digit key, in a debug build only.
     *
     * Null in release, which is the honest answer there: no contract issues one
     * yet, so there is nothing to show and the row is not composed. That is a
     * different thing from the debug build, where the row is present precisely so
     * the composition can be reviewed.
     */
    fun deviceKey(): String? = if (BuildConfig.DEBUG) DEVICE_KEY else null

    /** Six decimal digits, one group, no separator — the final format. */
    private const val DEVICE_KEY = "482731"

    /* ----------------------------------------------------------- the media screens */

    /*
     * Why these are here, given that the four browse screens were written
     * deliberately empty.
     *
     * Reading the device's media is `MediaStore` and choosing a file is the document
     * picker, and both are the slice after this one. What a release build shows until
     * then is the empty state, because with no source that is the only true thing to
     * draw — and it stays that way below.
     *
     * But a wall of tiles cannot be judged from a sentence saying there are none. The
     * grid's column arithmetic, the fade at the fold, a long name against the duration
     * beside it, a Latin filename inside an Arabic layout: every one of those is a
     * question only a full screen answers, and every one of them is cheaper to answer
     * now than after a device says the design was wrong. That is the same argument
     * `482731` is here under, and it is granted on the same terms — a constant, in
     * this file, behind `BuildConfig.DEBUG`.
     *
     * So they are chosen to be awkward rather than flattering. The longest name is
     * longer than any row is wide, one is Arabic among Latin ones and one is Latin
     * among Arabic ones, the durations run from seconds to hours, and there are more
     * of each than a television screen holds — a fixture that fits is a fixture that
     * proves nothing.
     */

    /** What the video wall draws in a debug build, and nothing in a release one. */
    fun videos(): List<MediaTile> = if (BuildConfig.DEBUG) VIDEOS else emptyList()

    /** What the audio list draws in a debug build, and nothing in a release one. */
    fun tracks(): List<MediaTile> = if (BuildConfig.DEBUG) TRACKS else emptyList()

    /**
     * What a picker lists in a debug build, and nothing in a release one.
     *
     * [parentLabel] is passed in because it is the one line of the listing that is
     * prose — "Parent folder" is a sentence Castivio writes, and a sentence in Kotlin
     * is a sentence that never gets translated.
     *
     * Everything else is a name off a filesystem and stays here. `Movies` and `DCIM`
     * are what those directories are actually called on an Android device in every
     * language, and the filenames likewise: Latin names inside an Arabic layout is the
     * accurate case to show, not an oversight to correct.
     */
    fun folder(kind: PickerKind, parentLabel: String): List<PickerEntry> {
        if (!BuildConfig.DEBUG) return emptyList()
        val files = if (kind == PickerKind.Video) VIDEOS else TRACKS
        return buildList {
            add(PickerEntry(parentLabel, "", PickerEntry.EntryKind.Parent))
            FOLDERS.forEach { add(PickerEntry(it, "", PickerEntry.EntryKind.Folder)) }
            files.forEach { add(PickerEntry(it.name, it.duration, PickerEntry.EntryKind.File)) }
        }
    }

    /** Directory names, which Android does not translate either. */
    private val FOLDERS = listOf("DCIM", "Download", "Movies", "Music")

    private val VIDEOS = listOf(
        MediaTile("Sintel.2010.1080p.mkv", "14:48"),
        MediaTile("رحلة إلى الصحراء.mp4", "1:52:07"),
        MediaTile("Big_Buck_Bunny_60fps.mp4", "10:34"),
        MediaTile("holiday-clip-0042.mp4", "0:47"),
        MediaTile("Tears.of.Steel.2012.BluRay.x265.10bit.HDR.Atmos.mkv", "12:14"),
        MediaTile("drone-over-the-harbour-take-3.mov", "3:09"),
        MediaTile("Cosmos.S01E04.mkv", "44:21"),
        MediaTile("مباراة الأمس كاملة.mkv", "2:07:55"),
        MediaTile("screen-record-2026-05-11.mp4", "0:22"),
        MediaTile("Elephants_Dream_1024.avi", "10:53"),
        MediaTile("wedding-final-cut-v7.mp4", "1:18:40"),
        MediaTile("timelapse_sunrise.webm", "1:05"),
    )

    private val TRACKS = listOf(
        MediaTile("01 - Nocturne in E flat major.mp3", "4:31"),
        MediaTile("أم كلثوم - الأطلال.mp3", "58:12"),
        MediaTile("podcast-ep-114-the-long-one-about-everything.mp3", "1:47:03"),
        MediaTile("voice-memo-2026-04-02.m4a", "0:38"),
        MediaTile("Miles Davis - So What.flac", "9:22"),
        MediaTile("فيروز - كيفك إنت.mp3", "3:44"),
        MediaTile("track09.mp3", "2:58"),
        MediaTile("Ravel - Boléro (complete).mp3", "15:06"),
        MediaTile("ringtone_old.ogg", "0:12"),
        MediaTile("live-set-warehouse-2025-continuous.mp3", "2:31:19"),
    )
}
