package com.castivio.data.subtitles

import android.content.Context
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The download count, kept in a preferences file, stamped with the day it belongs to.
 *
 * ## The day is stored, not the expiry
 *
 * Two values: a count and the day it was counted on. A read that finds a different day
 * answers zero — the reset is a comparison and not a job that has to run, so there is no
 * scheduled work to miss, nothing to do when the process was not alive at midnight, and no
 * way for yesterday's number to survive by being read before something cleared it.
 *
 * A clock moved backwards makes yesterday current again and yesterday's count returns. That
 * is correct rather than merely acceptable: the downloads did happen on that day, and the
 * provider's own count is the authority in any case.
 */
@Singleton
class StoredSubtitleAllowance @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SubtitleAllowance {

    /**
     * Where "now" comes from, so a test can be tomorrow.
     *
     * A property and not a constructor parameter, because Dagger would need a binding for a
     * `() -> Long` and there is no sensible one to give it. The reset is the behaviour most
     * worth testing here and the least testable by waiting.
     */
    internal var clock: () -> Long = System::currentTimeMillis

    private val prefs get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override val dailyLimit: Int = FREE_ACCOUNT_DOWNLOADS

    override fun spentToday(): Int {
        val stored = prefs
        val day = stored.getLong(DAY, NEVER)
        if (day != localDayOf(clock())) return 0
        return stored.getInt(COUNT, 0).coerceAtLeast(0)
    }

    override fun recordDownload() {
        val before = spentToday()
        write(before + 1)
        Log.i(TAG, "quotaIncremented=true dailyDownloadsBefore=$before dailyDownloadsAfter=${before + 1}")
    }

    override fun markSpent() {
        val before = spentToday()
        if (before >= dailyLimit) return
        write(dailyLimit)
        Log.i(
            TAG,
            "quotaIncremented=true reason=PROVIDER_SAYS_SPENT " +
                "dailyDownloadsBefore=$before dailyDownloadsAfter=$dailyLimit",
        )
    }

    private fun write(count: Int) {
        prefs.edit()
            .putLong(DAY, localDayOf(clock()))
            .putInt(COUNT, count)
            .apply()
    }

    private companion object {
        /**
         * Its own file, not the one the appearance settings live in.
         *
         * They have different lifetimes and different owners: one is a preference a person
         * set and expects to keep, the other is a tally that is meaningless tomorrow.
         */
        const val FILE = "castivio.subtitles.allowance"
        const val DAY = "day"
        const val COUNT = "count"

        /** No stored day. Not a valid day number, so it can never equal today. */
        const val NEVER = Long.MIN_VALUE

        /**
         * What a free OpenSubtitles account is given in a day.
         *
         * Stated here rather than discovered, because the API does not volunteer it and
         * asking would be a request per sheet-opening to learn a number that changes when
         * somebody buys a subscription. It decides only when this application stops
         * offering; the provider decides what actually happens, and `markSpent` is how it
         * corrects this number when the two disagree.
         */
        const val FREE_ACCOUNT_DOWNLOADS = 5

        const val TAG = OpenSubtitlesApi.TAG
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SubtitleAllowanceModule {
    @Binds
    abstract fun allowance(stored: StoredSubtitleAllowance): SubtitleAllowance
}
