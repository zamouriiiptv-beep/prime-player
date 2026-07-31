package com.castivio.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the user's language choice lives between launches.
 *
 * One tag, and it is the *resolved* one — `zh-Hant`, never `zh`. Storing the bare
 * language would let a later change to the phone's settings switch a user
 * between Simplified and Traditional without them touching anything.
 *
 * ## Why `SharedPreferences` here, where the rest of Castivio uses DataStore
 *
 * Because the one caller that matters is `attachBaseContext`, which runs before
 * the activity exists, has no scope to launch a coroutine in, and must return a
 * `Context` that is *already* in the right language. DataStore is asynchronous by
 * design; bridging that would mean either blocking on it, which is the thing it
 * exists to prevent, or keeping a second synchronous copy in step forever. One
 * synchronous store for one string is smaller than either.
 *
 * ## Why not `AppCompatDelegate`
 *
 * `AppCompatDelegate.setApplicationLocales` does this job and Google maintains
 * it. Its price is `AppCompatActivity` and an AppCompat theme parent, in an
 * application whose entire interface is Compose and Material 3 — a view toolkit
 * and a theming system adopted to hold one string. So the storage is ours, a
 * `ContextWrapper` in `:app` applies it, and on API 33 and above the same tag
 * goes through the platform's `LocaleManager` as well, so Android's own Settings
 * agrees with us instead of disagreeing silently.
 *
 * See `design/activation-spec.md` §10.6.1 for the comparison this came out of.
 */
@Singleton
class LanguageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The chosen tag, or null.
     *
     * **Null is not English.** It means the user has never chosen, and the device's
     * language decides — which is a different thing from having chosen English,
     * and the difference is the whole first-launch rule.
     */
    fun stored(): String? = prefs.getString(KEY, null)

    fun set(tag: String) {
        prefs.edit().putString(KEY, tag).apply()
    }

    /** For anything that wants to react rather than ask. */
    fun changes(): Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY) trySend(p.getString(KEY, null))
        }
        trySend(prefs.getString(KEY, null))
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    private companion object {
        const val FILE = "castivio_language"
        const val KEY = "language_tag"
    }
}
