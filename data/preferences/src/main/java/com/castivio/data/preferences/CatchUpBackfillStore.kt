package com.castivio.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.castivio.domain.BackfillOutcome
import com.castivio.domain.CatchUpBackfillMarks
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which subscriptions have had their catch-up hours filled, so that it is done once and not at every start.
 *
 * Preferences and not a column, for the reason [SectionCatalogueStore] gives: a mark beside a source row would mean a
 * second migration for one word, and this is a flag that is true or absent. It is keyed by the subscription's id and
 * says how it ended -- filled, or refused by its provider -- which is only for whoever reads the log; nothing but
 * "has it been tried" is asked of it.
 *
 * A mark left behind by a subscription that was removed is harmless: ids are hashes, and a new subscription has a new one.
 */
@Singleton
class CatchUpBackfillStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CatchUpBackfillMarks {

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun outcomeOf(sourceId: String): BackfillOutcome? =
        prefs.getString(key(sourceId), null)?.let { name -> BackfillOutcome.entries.firstOrNull { it.name == name } }

    override fun record(sourceId: String, outcome: BackfillOutcome) {
        prefs.edit().putString(key(sourceId), outcome.name).apply()
    }

    private fun key(sourceId: String) = "outcome.$sourceId"

    private companion object {
        const val FILE = "castivio_catchup_backfill"
    }
}
