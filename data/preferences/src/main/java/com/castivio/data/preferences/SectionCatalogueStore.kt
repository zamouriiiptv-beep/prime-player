package com.castivio.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.castivio.domain.MediaKind
import com.castivio.domain.SectionCatalogue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which sections of which provider are on this device, and when they arrived.
 *
 * ## Why this is preferences and not a column
 *
 * It should be a column. A mark belongs beside the source row it describes, and a
 * `sources` table that already carries `last_import_at` and `item_count` is the
 * obvious home for four more of each.
 *
 * It is here because a Room column means a migration and a regenerated schema
 * export, and this project's CI verifies the exported schema against the one the
 * build produces — a file that can only be produced by running the Android
 * annotation processor, which the development sandbox cannot do. Writing the
 * migration blind and hoping the JSON matches is the one change guaranteed to break
 * the build in a way nobody can check locally.
 *
 * So it lives beside [ThemeStore] and [LanguageStore], and for one of the same
 * reasons: it is a handful of longs, read on the path to a screen, with no
 * relational meaning of its own. **This is a deliberate debt, not a design.** When a
 * migration is affordable it moves, and the interface it implements is in `:domain`
 * precisely so that move costs nothing above this file.
 *
 * ## Keyed by source, and cleared with it
 *
 * A mark is meaningless without the provider it belongs to: the same box can carry
 * three subscriptions, and "the films are here" is true of one of them. [forget]
 * exists so that removing a provider does not leave marks that would tell the next
 * registration of the same id that its sections were already fetched.
 */
@Singleton
class SectionCatalogueStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SectionCatalogue {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Bumped on every write, so readers re-read.
     *
     * `SharedPreferences` has a listener API and this deliberately does not use it:
     * the listener fires on the thread that wrote, must be held against garbage
     * collection, and has to be unregistered by somebody. A counter that says "the
     * file changed" is the whole of what a reader here needs, and the read behind it
     * is four `getLong`s.
     */
    private val revision = MutableStateFlow(0L)

    override suspend fun loadedAt(sourceId: String, kind: MediaKind): Long? =
        prefs.getLong(key(sourceId, kind), NEVER).takeIf { it != NEVER }

    override fun loaded(sourceId: String): Flow<Map<MediaKind, Long>> =
        revision.map { readAll(sourceId) }

    override suspend fun markLoaded(sourceId: String, kind: MediaKind, atMs: Long) {
        prefs.edit().putLong(key(sourceId, kind), atMs).apply()
        revision.value = revision.value + 1
    }

    override suspend fun forget(sourceId: String) {
        val editor = prefs.edit()
        for (kind in MediaKind.entries) editor.remove(key(sourceId, kind))
        editor.apply()
        revision.value = revision.value + 1
    }

    private fun readAll(sourceId: String): Map<MediaKind, Long> = buildMap {
        for (kind in MediaKind.entries) {
            val at = prefs.getLong(key(sourceId, kind), NEVER)
            if (at != NEVER) put(kind, at)
        }
    }

    /**
     * The kind's name and not its ordinal.
     *
     * An ordinal written to disk is a value whose meaning changes the day somebody
     * adds an entry to the middle of the enum, and it changes silently — the films
     * would start reporting the channels' timestamp.
     */
    private fun key(sourceId: String, kind: MediaKind) = "$sourceId|${kind.name}"

    private companion object {
        const val FILE = "castivio_sections"

        /** `getLong` needs a default, and zero is a real epoch instant. */
        const val NEVER = -1L
    }
}
