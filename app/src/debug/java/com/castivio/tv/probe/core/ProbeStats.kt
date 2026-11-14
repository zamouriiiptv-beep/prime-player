package com.castivio.tv.probe.core

import kotlin.math.ceil

/** The few statistics the report needs, over samples small enough that their definition matters. */
object ProbeStats {

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    /**
     * The nearest-rank percentile: the smallest value that has at least [percent] of the sample at or below it.
     *
     * Chosen over interpolation because the samples are a few hundred requests at most, and sometimes one: a p95
     * that is always a value somebody actually measured is the one that can be read back against the runs.
     */
    fun percentile(values: List<Double>, percent: Double): Double? {
        if (values.isEmpty()) return null
        require(percent > 0.0 && percent <= 100.0) { "percent must be in (0, 100]" }
        val sorted = values.sorted()
        val rank = ceil(percent / 100.0 * sorted.size - 1e-9).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun minimum(values: List<Double>): Double? = values.minOrNull()

    fun maximum(values: List<Double>): Double? = values.maxOrNull()
}
