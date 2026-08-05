package com.castivio.tv.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import com.castivio.tv.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Castivio's one sound, played once.
 *
 * ## What it is
 *
 * A major-ninth rise — D5, A5, D6, and a fourth voice blooming a beat later —
 * over a low swell, struck rather than beeped: the partials are slightly
 * inharmonic, which is the difference between a bar being hit and a test tone
 * being switched on. It is 1.05 seconds, peaks at −1.5 dBFS, and starts and ends
 * on silence so no speaker clicks at either end. Generated rather than licensed,
 * so it is Castivio's to ship. See `tools/startup-sound.py`.
 *
 * ## The rules it obeys, and why each one is here
 *
 * **Once per process.** [played] is a process-wide latch, not an activity flag.
 * Coming back from the launcher, rotating, or having the activity recreated
 * underneath a live process are all the same thing to a user — they did not
 * start the app again — and a chime on each of them is the fastest way to make
 * somebody turn the sound off for good.
 *
 * **Never on a restore.** A `savedInstanceState` means the process was rebuilt
 * around a session that already existed. Cold to Android, warm to the user.
 *
 * **Silent means silent.** Media playback does not follow the ringer, so nothing
 * would stop this from sounding in a meeting except this check. A phone set to
 * silent or vibrate gets nothing, and so does one with the media volume at zero.
 *
 * **It never delays anything.** `SoundPool` loads off the calling thread and the
 * play happens in the load callback, so the first frame does not wait for a
 * decode. If the load fails the app simply starts quietly.
 *
 * **It cannot loop.** `loop = 0` on the one and only stream, and the pool is
 * released a moment after the sound is over rather than held for a second play
 * that is never coming.
 */
object StartupSound {

    private val played = AtomicBoolean(false)

    /**
     * Play it, if this is the first time and the device wants to hear it.
     *
     * @param restored whether the activity is being rebuilt from a saved state.
     */
    fun playOnce(context: Context, restored: Boolean) {
        val audio = context.getSystemService(AudioManager::class.java)
        val allowed = shouldPlay(
            restored = restored,
            ringerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_SILENT,
            mediaVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0,
        )
        // The latch is claimed even when the sound is suppressed. A user who
        // starts the app on silent, then unmutes to watch something, should not
        // get a startup chime in the middle of it.
        if (!played.compareAndSet(false, true)) return
        if (!allowed) return

        val pool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // MEDIA, so the volume the user reaches for is the one that
                    // changes it, and SONIFICATION, so the system knows this is
                    // interface feedback and ducks it against real playback
                    // rather than the other way round.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()

        pool.setOnLoadCompleteListener { loaded, sampleId, status ->
            if (status == 0) {
                loaded.play(sampleId, VOLUME, VOLUME, PRIORITY, NO_LOOP, RATE)
            }
            // Released once the sample cannot still be playing. Holding a pool
            // for the life of the process would keep a decoded second of audio
            // resident on a stick with a gigabyte of RAM, for a sound that will
            // not be played again.
            Handler(Looper.getMainLooper()).postDelayed({ loaded.release() }, RELEASE_AFTER_MS)
        }
        pool.load(context, R.raw.castivio_startup, PRIORITY)
    }

    /**
     * The whole decision, with nothing Android in it.
     *
     * Separated so it can be tested without a device, which is the only way the
     * silent-mode rule gets tested at all — the alternative is somebody
     * remembering to put a phone on vibrate before a release.
     */
    internal fun shouldPlay(restored: Boolean, ringerMode: Int, mediaVolume: Int): Boolean = when {
        restored -> false
        ringerMode != AudioManager.RINGER_MODE_NORMAL -> false
        mediaVolume <= 0 -> false
        else -> true
    }

    /**
     * Full, which is not loud: this is a fraction of the media stream, so the
     * system volume is what decides how loud the sound actually is.
     */
    private const val VOLUME = 1f
    private const val PRIORITY = 1
    private const val NO_LOOP = 0
    private const val RATE = 1f

    /** The sample is 1.05s. This is that, rounded up, plus a margin. */
    private const val RELEASE_AFTER_MS = 2_000L
}
