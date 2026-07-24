package com.castivio.core.platform

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display

/**
 * Queries the platform for real capability, with conservative fallbacks.
 *
 * Every answer comes from the framework — codec lists, HDR capabilities, memory
 * class — so a device released after this code was written is described
 * correctly without a code change.
 */
class AndroidDeviceCapabilities(private val context: Context) : DeviceCapabilities {

    private val codecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    override fun supports(codec: Codec, mode: DecodeMode): Boolean {
        val mime = codec.mime ?: return false
        return codecList.codecInfos.any { info ->
            if (info.isEncoder) return@any false
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) return@any false
            when (mode) {
                DecodeMode.ANY -> true
                DecodeMode.HARDWARE -> info.isHardwareAcceleratedCompat()
                DecodeMode.SOFTWARE -> !info.isHardwareAcceleratedCompat()
            }
        }
    }

    override val hdrFormats: Set<HdrFormat> by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@lazy emptySet()
        val display = runCatching { context.mainDisplayCompat() }.getOrNull() ?: return@lazy emptySet()
        val types = runCatching { display.hdrCapabilities?.supportedHdrTypes }.getOrNull().orEmpty()
        buildSet {
            for (t in types) when (t) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> add(HdrFormat.HDR10)
                Display.HdrCapabilities.HDR_TYPE_HLG -> add(HdrFormat.HLG)
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> add(HdrFormat.DOLBY_VISION)
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    t == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) add(HdrFormat.HDR10_PLUS)
            }
        }
    }

    override val audioPassthrough: Set<AudioFormat> = emptySet() // filled by the engine at open time

    override val maxResolution: Resolution by lazy {
        val d = runCatching { context.mainDisplayCompat() }.getOrNull() ?: return@lazy Resolution.FHD
        @Suppress("DEPRECATION")
        val w = d.mode?.physicalWidth ?: d.width
        @Suppress("DEPRECATION")
        val h = d.mode?.physicalHeight ?: d.height
        Resolution(w, h)
    }

    override val memoryClass: MemoryClass by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        when (val mb = am?.memoryClass ?: 0) {
            in 0..127 -> MemoryClass.LOW
            in 128..255 -> MemoryClass.MEDIUM
            else -> if (mb > 0) MemoryClass.HIGH else MemoryClass.LOW
        }
    }

    override val supportsRefreshRateSwitching: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            (runCatching { context.mainDisplayCompat()?.supportedModes?.size ?: 0 }.getOrDefault(0) > 1)
    }
}

private fun Context.mainDisplayCompat(): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
    }

private fun android.media.MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isHardwareAccelerated
    else !name.startsWith("OMX.google", ignoreCase = true) && !name.startsWith("c2.android", ignoreCase = true)

private val Codec.mime: String?
    get() = when (this) {
        Codec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        Codec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        Codec.AV1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaFormat.MIMETYPE_VIDEO_AV1 else null
        Codec.VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
        Codec.MPEG2 -> MediaFormat.MIMETYPE_VIDEO_MPEG2
    }
