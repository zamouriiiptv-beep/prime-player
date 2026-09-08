package com.castivio.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which of the two grounds Castivio stands on, between launches.
 *
 * ## Why `SharedPreferences`, next to [LanguageStore]
 *
 * The same reason, and it is the only reason: this is read **before the first frame**.
 * A theme resolved asynchronously means one composition on the default ground and a
 * second on the chosen one, which the user sees as the application changing its mind
 * on every launch. DataStore is asynchronous by design; one synchronous read of one
 * boolean is smaller than bridging that, and the file sits beside the language's for
 * the same lifetime and the same reason.
 *
 * No flow, unlike [LanguageStore]. Nothing outside the composition needs to react to
 * this — `:app` holds the choice in state, writes it here when it changes, and reads
 * it once at startup. A listener would exist to keep a second copy in step, and there
 * is no second copy.
 */
@Singleton
class ThemeStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The stored choice, or the void.
     *
     * **The deep ground is not a fallback here, it is the product.** Castivio offers a
     * second ground; it does not follow the system. A user who has never chosen gets
     * the ground every drawing was made against, on a television as on a phone, and is
     * not switched at dusk by a setting they made for their mail client.
     *
     * True is the near-black void and false the lifted slate beside it — both dark, so
     * this is which darkness rather than dark against light. The key on disc is still
     * `"dark"` and stays that way: renaming it would read as unset on every device that
     * already has a choice stored, which is a silent reset of a user's preference to
     * make a string match a comment.
     */
    fun isDark(): Boolean = prefs.getBoolean(KEY, true)

    fun setDark(dark: Boolean) {
        prefs.edit().putBoolean(KEY, dark).apply()
    }

    private companion object {
        const val FILE = "castivio_theme"
        const val KEY = "dark"
    }
}
