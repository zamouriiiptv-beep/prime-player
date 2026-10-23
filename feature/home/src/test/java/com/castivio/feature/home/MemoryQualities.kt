package com.castivio.feature.home

import com.castivio.domain.ChannelQualities
import com.castivio.domain.StreamQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The measurements, in memory, and a record of what was written to them. */
internal class MemoryQualities(initial: Map<String, StreamQuality> = emptyMap()) : ChannelQualities {
    private val _known = MutableStateFlow(initial)
    override val known: StateFlow<Map<String, StreamQuality>> = _known.asStateFlow()

    /** Every call to [record], in order. */
    val writes = mutableListOf<Pair<String, StreamQuality>>()

    override fun record(channelId: String, quality: StreamQuality) {
        writes += channelId to quality
        _known.value = _known.value + (channelId to quality)
    }
}
