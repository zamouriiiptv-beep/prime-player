package com.castivio.core.platform

/**
 * What this box can actually do.
 *
 * Castivio never asks "is this a Xiaomi?" — the badge tells you nothing useful.
 * It asks what can be decoded, how much memory there is, and whether the display
 * can switch refresh rate. Those answers are the same shape on every device, so
 * one implementation covers Android TV, Google TV, Fire TV and generic boxes.
 */
interface DeviceCapabilities {

    fun supports(codec: Codec, mode: DecodeMode = DecodeMode.HARDWARE): Boolean

    val hdrFormats: Set<HdrFormat>
    val audioPassthrough: Set<AudioFormat>
    val maxResolution: Resolution
    val memoryClass: MemoryClass
    val supportsRefreshRateSwitching: Boolean

    /** Cache budget in bytes, derived from [memoryClass]. */
    val recommendedCacheBytes: Long
        get() = when (memoryClass) {
            MemoryClass.LOW -> 256L * 1024 * 1024
            MemoryClass.MEDIUM -> 1024L * 1024 * 1024
            MemoryClass.HIGH -> 2048L * 1024 * 1024
        }

    /** Poster width to request. Downloading 4K artwork onto a 2 GB stick is waste. */
    val posterWidthPx: Int
        get() = when (memoryClass) {
            MemoryClass.LOW -> 240
            MemoryClass.MEDIUM -> 360
            MemoryClass.HIGH -> 480
        }
}

enum class Codec { H264, HEVC, AV1, VP9, MPEG2 }
enum class DecodeMode { HARDWARE, SOFTWARE, ANY }
enum class HdrFormat { HDR10, HDR10_PLUS, HLG, DOLBY_VISION }
enum class AudioFormat { AC3, EAC3, DTS, DTS_HD, TRUEHD, ATMOS }
enum class MemoryClass { LOW, MEDIUM, HIGH }

data class Resolution(val width: Int, val height: Int) {
    companion object {
        val HD = Resolution(1280, 720)
        val FHD = Resolution(1920, 1080)
        val UHD = Resolution(3840, 2160)
    }
}

/** Safe fallback for an unknown device: assume the least, degrade gracefully. */
object ConservativeCapabilities : DeviceCapabilities {
    override fun supports(codec: Codec, mode: DecodeMode) = codec == Codec.H264
    override val hdrFormats = emptySet<HdrFormat>()
    override val audioPassthrough = emptySet<AudioFormat>()
    override val maxResolution = Resolution.FHD
    override val memoryClass = MemoryClass.LOW
    override val supportsRefreshRateSwitching = false
}
