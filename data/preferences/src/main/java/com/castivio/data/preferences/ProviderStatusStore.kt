package com.castivio.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.castivio.domain.ProviderStatus
import com.castivio.domain.ProviderStatusCatalogue
import com.castivio.domain.Recorded
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The provider's last answer about the subscription, per source.
 *
 * ## Why this is preferences and not a column
 *
 * The same reason [SectionCatalogueStore] is, and it is worth repeating rather than
 * cross-referencing, because the next person to read this file will be deciding
 * whether to add a fifth one: a Room column means a migration and a regenerated
 * schema export, CI verifies that export against the one the build produces, and the
 * export can only be produced by running the Android annotation processor — which
 * the development sandbox cannot do. Writing a migration blind and hoping the JSON
 * matches is the one change guaranteed to break the build in a way nobody can check
 * before pushing it.
 *
 * So four scalars live beside the theme and the language. **This is a deliberate
 * debt, not a design** — the fields belong on the `sources` row they describe. The
 * interface is in `:domain` so that the day a migration is affordable, this file is
 * the only one that changes.
 *
 * ## Absent is a state, and it is not "expired"
 *
 * A source with nothing recorded returns null rather than a [Recorded] with default
 * values. A provider that was never asked, and one that answered "no expiry stated",
 * are different facts and a screen says different things about them; collapsing them
 * into `expiresAtMs = 0` would put the epoch on a dashboard.
 */
@Singleton
class ProviderStatusStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ProviderStatusCatalogue {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Bumped on every write, so readers re-read. See [SectionCatalogueStore.revision]. */
    private val revision = MutableStateFlow(0L)

    override suspend fun record(sourceId: String, status: ProviderStatus, atMs: Long) {
        prefs.edit()
            .putBoolean(key(sourceId, USABLE), status.usable)
            .putLong(key(sourceId, EXPIRES), status.expiresAtMs ?: NONE)
            .putString(key(sourceId, LABEL), status.statusLabel)
            .putLong(key(sourceId, AT), atMs)
            .apply()
        revision.value = revision.value + 1
    }

    override fun of(sourceId: String): Flow<Recorded?> = revision.map { read(sourceId) }

    override suspend fun forget(sourceId: String) {
        val editor = prefs.edit()
        for (field in FIELDS) editor.remove(key(sourceId, field))
        editor.apply()
        revision.value = revision.value + 1
    }

    /**
     * The recorded answer, or null.
     *
     * [AT] is what decides: it is written on every record and on no other path, so a
     * missing timestamp means nothing was ever recorded — where a missing expiry
     * means the provider stated none.
     */
    private fun read(sourceId: String): Recorded? {
        val at = prefs.getLong(key(sourceId, AT), NONE)
        if (at == NONE) return null
        return Recorded(
            usable = prefs.getBoolean(key(sourceId, USABLE), false),
            expiresAtMs = prefs.getLong(key(sourceId, EXPIRES), NONE).takeIf { it != NONE },
            label = prefs.getString(key(sourceId, LABEL), null),
            atMs = at,
        )
    }

    private fun key(sourceId: String, field: String) = "$sourceId|$field"

    private companion object {
        const val FILE = "castivio_provider_status"

        const val USABLE = "usable"
        const val EXPIRES = "expires"
        const val LABEL = "label"
        const val AT = "at"
        val FIELDS = listOf(USABLE, EXPIRES, LABEL, AT)

        /** `getLong` needs a default, and zero is a real epoch instant. */
        const val NONE = -1L
    }
}
