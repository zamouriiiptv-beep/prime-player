package com.castivio.tv.probe.core

import java.util.Locale

/**
 * Numbers as the strip prints them: Western digits whatever the device's language, a point for the decimal and a
 * comma between thousands. `Locale.ROOT` is the whole of how.
 */
object Fmt {

    private const val BYTES_PER_MB = 1_048_576.0

    fun decimal(value: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", value)

    /** Milliseconds as seconds with one decimal. */
    fun seconds(ms: Long): String = decimal(ms / 1000.0, 1)

    fun seconds2(ms: Long): String = decimal(ms / 1000.0, 2)

    fun seconds(ms: Double): String = decimal(ms / 1000.0, 1)

    fun seconds2(ms: Double): String = decimal(ms / 1000.0, 2)

    fun megabytes(bytes: Long): String = decimal(bytes / BYTES_PER_MB, 1)

    /** `0.35` as `35.0%`. */
    fun percent(fraction: Double): String = decimal(fraction * 100.0, 1) + "%"

    /** `0.35` as `+35.0%` and `-0.12` as `-12.0%`. */
    fun signedPercent(fraction: Double): String = String.format(Locale.ROOT, "%+.1f%%", fraction * 100.0)

    fun count(value: Int): String = String.format(Locale.ROOT, "%,d", value)

    fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value)
}
