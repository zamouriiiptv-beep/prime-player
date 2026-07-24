package com.castivio.core.platform

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent

/**
 * Default remote mapping. Covers standard Android TV remotes and, because it
 * maps by key code rather than by device, Fire TV and generic box remotes too —
 * they simply report fewer keys, which the flags below describe honestly.
 */
class AndroidRemoteProfile(private val context: Context) : RemoteProfile {

    override fun map(keyCode: Int): RemoteAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> RemoteAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> RemoteAction.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> RemoteAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteAction.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteAction.SELECT
        KeyEvent.KEYCODE_BACK -> RemoteAction.BACK
        KeyEvent.KEYCODE_HOME -> RemoteAction.HOME
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteAction.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteAction.FAST_FORWARD
        KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteAction.REWIND
        KeyEvent.KEYCODE_MEDIA_STOP -> RemoteAction.STOP
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> RemoteAction.CHANNEL_UP
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> RemoteAction.CHANNEL_DOWN
        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_CONTENTS_MENU -> RemoteAction.GUIDE
        KeyEvent.KEYCODE_INFO -> RemoteAction.INFO
        KeyEvent.KEYCODE_MENU -> RemoteAction.MENU
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            RemoteAction.entries[RemoteAction.NUMBER_0.ordinal + (keyCode - KeyEvent.KEYCODE_0)]
        else -> null
    }

    /** Fire TV remotes ship without a number pad; on-screen entry is required there. */
    override val hasNumericKeys: Boolean =
        !context.packageManager.hasSystemFeature(FIRE_TV_FEATURE)

    override val hasDedicatedGuideKey: Boolean =
        !context.packageManager.hasSystemFeature(FIRE_TV_FEATURE)

    override val hasPlayPauseKey: Boolean = true

    private companion object {
        // A capability probe, not a brand check: it asks the OS what it provides.
        const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"
    }
}

class AndroidPlatformServices(
    private val context: Context,
    override val voiceSearch: VoiceSearchProvider? = null,
) : PlatformServices {

    override val hasPlayServices: Boolean by lazy {
        runCatching {
            context.packageManager.getPackageInfo("com.google.android.gms", 0)
            true
        }.getOrDefault(false)
    }

    override val store: StoreTarget by lazy {
        when (installerPackage()) {
            "com.android.vending" -> StoreTarget.PLAY
            "com.amazon.venezia", "com.amazon.mShop.android" -> StoreTarget.AMAZON
            null -> StoreTarget.SIDELOAD
            else -> StoreTarget.UNKNOWN
        }
    }

    override val isLeanback: Boolean by lazy {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        mode == Configuration.UI_MODE_TYPE_TELEVISION ||
            (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun installerPackage(): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }.getOrNull()
}
