package com.castivio.feature.home

import java.util.Locale

/**
 * `7.2 MB`, from bytes: the gate's tally and the performance readout's requests.
 *
 * Binary units, because that is what a download is measured in and what every other
 * tool on the device will agree with. [Locale.ROOT] for the separator, for the reason
 * `PerformancePanel` gives: a figure that changes shape with the interface language is
 * a figure that gets transcribed wrongly.
 *
 * In a file of its own, without Compose, so the readout that uses it stays a unit test.
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < MEGABYTE -> "${bytes / KILOBYTE} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / MEGABYTE)
}

private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024L
