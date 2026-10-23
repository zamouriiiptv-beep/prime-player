package com.castivio.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.castivio.data.parsing.SourceIds
import com.castivio.domain.PlaylistSource
import com.castivio.domain.SourceKind

/**
 * Schema migrations, and the policy behind them.
 *
 * The two halves of this database are not equally precious, and pretending
 * otherwise is what makes migrations painful:
 *
 *  - **The catalogue** (`media`, `media_fts`, `media_group`, `programme`) is a
 *    cache. Every row can be rebuilt from the provider, so a schema change is
 *    allowed to discard it — the next refresh fills it back in.
 *  - **User data** (`favorite`, `playback_progress`, `source`) cannot be rebuilt
 *    from anywhere. Favourites, watch positions and credentials are the only
 *    things in here a user would notice losing, so no migration may drop those
 *    tables.
 *
 * That is why they are separate tables with no foreign keys between them, and why
 * [recreateCatalogue] exists: a catalogue schema change becomes one entry in
 * [ALL], costs the user a re-import, and provably keeps everything else.
 *
 * `fallbackToDestructiveMigration()` is deliberately **not** used for upgrades.
 * It would wipe favourites silently on a version bump someone forgot to migrate —
 * exactly the failure this policy exists to prevent. Downgrades *are* destructive,
 * because a sideloaded older APK cannot know a newer schema, and an older APK
 * landing on a TV box is common enough to plan for.
 */
object CastivioMigrations {

    /**
     * Every migration, in order.
     *
     * A schema change adds either a hand-written migration or [recreateCatalogue]
     * here; if it adds neither, the app fails to open on upgrade during development
     * rather than silently discarding user data in production.
     */
    // Declared below rather than here, and read through a getter rather than held in a
    // property: an `object`'s properties initialise top to bottom, so a list that
    // named a migration declared under it would be a list holding null.
    val ALL: Array<Migration> get() = arrayOf(REKEY_SOURCES, SCOPE_TO_SOURCE, KEY_SEARCH_BY_ROW, ADD_CUSTOM_ORDER)

    /**
     * 1 → 2: the stored sources take the ids the current rule gives them.
     *
     * **No table changes.** This is here because the *derivation* of a source id
     * changed, not its column. `SourceIds` used to drop an M3U URL's whole query
     * string to be rid of the password, which on the `get.php?username=…` shape every
     * panel hands out left `host/get.php` and nothing else — so two subscriptions on
     * one panel hashed to one id, and the row is written with
     * `OnConflictStrategy.REPLACE`. The second did not appear beside the first; it
     * took its place.
     *
     * ## Why the rows cannot simply be left alone
     *
     * Because the id is derived, not stored-and-trusted: `DefaultCatalogImporter`
     * computes `SourceIds.of(source)` on every import and writes the catalogue under
     * it. A row left on its old id would keep its name and its credentials and lose
     * its catalogue the next time it refreshed — the subscription would be there and
     * empty, which is a worse failure than the one being fixed and a silent one.
     *
     * ## What it costs
     *
     * The catalogue, which is a cache and re-imports on the next refresh — that is the
     * trade this file's whole policy is built around.
     *
     * And, honestly: favourites and watch positions on a re-keyed source are orphaned.
     * They key on a media id, a media id keys on the source id, and the catalogue they
     * point into is being rebuilt under a new one. The rows are **not deleted** — this
     * migration touches neither table, and `FavoriteEntity`'s own note explains why
     * orphans are filtered by the join rather than cascaded away — so nothing is
     * destroyed. They are simply no longer attached to anything.
     *
     * Rebuilding that attachment is possible and is not done here: it would mean
     * recomputing every media id in the database from the key it was made from, to
     * remap two tables, in a migration that cannot be run anywhere but on a device.
     * That is a lot of machinery, carrying real risk of its own, to preserve data that
     * exists on one developer's handset in an application that has not shipped.
     */
    private val REKEY_SOURCES = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rekeySources(db)

            // The catalogue is keyed on the old ids, and it is a cache. Deleted rather
            // than dropped and recreated: the tables themselves are unchanged, so there
            // is no DDL to restate and no chance of restating it wrongly.
            //
            // `media_fts` is not an external-content table — see `MediaFtsEntity`, which
            // says why — so no trigger empties it when `media` goes. It is emptied here.
            for (table in CATALOGUE_TABLES) db.execSQL("DELETE FROM $table")
            clearSyncState(db)
        }
    }

    /**
     * Gives every reconstructable source the id the current rule produces.
     *
     * Only M3U and Xtream rows can change: a local file keys on its URI and a portal
     * on its MAC, and neither rule moved. A row that cannot be rebuilt into a
     * [PlaylistSource] is left exactly as it is, because the alternative is guessing.
     *
     * ## Two rows that are now one subscription
     *
     * The new rule is more discriminating than the old one in the case that matters and
     * less in another: `host/get.php?username=sam` and `host/player_api.php?username=sam`
     * were two ids and are one account, which they always were. The older row by
     * `created_at` keeps the id and the younger is deleted, because they describe the
     * same subscription and the first is the one whose catalogue and name the user has
     * been living with.
     *
     * ## Renamed in two passes
     *
     * `id` is the primary key, so renaming A to B while B still exists fails. Every
     * affected row goes to a temporary id first — prefixed with a byte no hash can
     * contain — and comes back in a second pass. It costs one extra statement per row
     * and removes the ordering question entirely.
     */
    private fun rekeySources(db: SupportSQLiteDatabase) {
        val claimed = mutableSetOf<String>()
        val renames = mutableListOf<Pair<String, String>>()
        val superseded = mutableListOf<String>()

        db.query(
            "SELECT id, kind, url, username, password, user_agent FROM source ORDER BY created_at",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val oldId = cursor.getString(0)
                val kind = cursor.getString(1)
                val url = if (cursor.isNull(2)) null else cursor.getString(2)
                val username = if (cursor.isNull(3)) null else cursor.getString(3)
                val password = if (cursor.isNull(4)) null else cursor.getString(4)
                val userAgent = if (cursor.isNull(5)) null else cursor.getString(5)

                val source = when (kind) {
                    SourceKind.M3U_URL.name -> url?.let { PlaylistSource.M3u(it, userAgent) }
                    SourceKind.XTREAM.name -> url?.let {
                        PlaylistSource.Xtream(it, username.orEmpty(), password.orEmpty())
                    }
                    else -> null
                }
                if (source == null) {
                    claimed += oldId
                    continue
                }

                val newId = SourceIds.of(source)
                when {
                    !claimed.add(newId) -> superseded += oldId
                    newId != oldId -> renames += oldId to newId
                }
            }
        }

        for (id in superseded) db.execSQL("DELETE FROM source WHERE id = ?", arrayOf<Any>(id))
        for ((old, new) in renames) {
            db.execSQL("UPDATE source SET id = ? WHERE id = ?", arrayOf<Any>(RENAMING + new, old))
        }
        for ((_, new) in renames) {
            db.execSQL("UPDATE source SET id = ? WHERE id = ?", arrayOf<Any>(new, RENAMING + new))
        }

        // Deleting a superseded row can be deleting the active one, and a device with
        // subscriptions and none of them active is a device that opens on nothing.
        db.execSQL(
            "UPDATE source SET is_active = 1 " +
                "WHERE id = (SELECT id FROM source ORDER BY created_at LIMIT 1) " +
                "AND NOT EXISTS (SELECT 1 FROM source WHERE is_active = 1)",
        )
    }

    /** A prefix no identity can carry, so the two-pass rename cannot collide. */
    private const val RENAMING = "\u0000renaming:"

    /**
     * 2 → 3: the catalogue is read one subscription at a time.
     *
     * **No table changes; three indices on `media` and one on `media_group` do.** Every
     * browse, count and search read now filters on the active subscription, so the
     * indices it uses lead with `source_id`. They replace the ones keyed on `kind` alone
     * rather than joining them, so an import maintains the same number of indices per
     * row as before.
     *
     * The statements are Room's own DDL for the entities' indices, character for
     * character: Room compares what it finds against what it expects when the database
     * opens, and an index that differs in name, column or order is a failed open.
     *
     * ## And the rows nothing owns
     *
     * Deleting a subscription removed its `source` row and nothing else, so its channels
     * stayed in the catalogue — and, because the reads were not scoped, stayed on screen
     * under whichever subscription came next. [removeOrphans] takes them out. It cannot
     * reach a saved subscription's rows: since migration 2 every row is written under the
     * id its subscription is stored with, so a row whose `source_id` matches no `source`
     * belongs to one that was deleted. User data is not touched, as everywhere in this
     * file: a favourite of a deleted channel stays stored and comes back if the same
     * subscription is added again.
     */
    private val SCOPE_TO_SOURCE = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (index in RETIRED_INDICES) db.execSQL("DROP INDEX IF EXISTS `$index`")
            for (statement in SCOPED_INDICES) db.execSQL(statement)
            removeOrphans(db)
        }
    }

    /**
     * 3 → 4: every search entry keyed by its row's rowid, and one entry per row.
     *
     * **No table changes.** The rule for `media_fts`'s `docid` did: an import now indexes
     * the rows it wrote once, at its end, and finds the entries it made stale by number --
     * a docid that is no longer a row -- which holds only if every docid is its row's
     * rowid. The index this replaces broke that twice: a replacing import rebuilt it with
     * docids of SQLite's choosing, and an appending one added an entry per row written
     * without removing the row's old one, so each reload of a section added another copy
     * and search returned a channel once per load.
     *
     * Rebuilt, rather than repaired entry by entry: the rows are all there and the text
     * is theirs, so two statements produce exactly the index an import maintains, with
     * nothing to reconcile. It costs one pass over the catalogue, once, on the first open
     * after the update; no row, no subscription and no user data is touched.
     */
    private val KEY_SEARCH_BY_ROW = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM media_fts")
            db.execSQL("INSERT INTO media_fts (docid, media_id, search_text) SELECT rowid, id, search_text FROM media")
        }
    }

    /**
     * Adds `custom_order`: the order a viewer gives a list of bouquets or channels.
     *
     * A table and its index, written as Room writes them for `CustomOrderEntity` -- Room checks
     * the table it finds against the entity on open, so the columns, their types and the key
     * are exactly the entity's. Nothing is read or changed: no row of any table exists yet that
     * this could have an opinion about, and it is user data from its first row, so
     * [USER_TABLES] names it and no migration may drop it.
     */
    private val ADD_CUSTOM_ORDER = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `custom_order` (`scope` TEXT NOT NULL, " +
                    "`item_id` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`scope`, `item_id`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_custom_order_position` ON `custom_order` (`scope`, `position`)",
            )
        }
    }

    /**
     * Removes catalogue rows whose subscription no longer exists.
     *
     * `media_fts` first, while `media` can still say which rows were whose: the search
     * table is standalone — see `MediaFtsEntity` — so nothing empties it on its own.
     */
    internal fun removeOrphans(db: SupportSQLiteDatabase) {
        db.execSQL(
            "DELETE FROM media_fts WHERE media_id IN " +
                "(SELECT id FROM media WHERE source_id NOT IN (SELECT id FROM source))",
        )
        db.execSQL("DELETE FROM media WHERE source_id NOT IN (SELECT id FROM source)")
        db.execSQL("DELETE FROM media_group WHERE source_id NOT IN (SELECT id FROM source)")
    }

    private val RETIRED_INDICES = listOf(
        "idx_media_kind_order",
        "idx_media_kind_group_order",
        "idx_media_kind_sort_title",
        "idx_group_kind_order",
    )

    private val SCOPED_INDICES = listOf(
        "CREATE INDEX IF NOT EXISTS `idx_media_source_kind_order` " +
            "ON `media` (`source_id`, `kind`, `provider_order`)",
        "CREATE INDEX IF NOT EXISTS `idx_media_source_kind_group_order` " +
            "ON `media` (`source_id`, `kind`, `group_id`, `provider_order`)",
        "CREATE INDEX IF NOT EXISTS `idx_media_source_kind_sort_title` " +
            "ON `media` (`source_id`, `kind`, `sort_title`)",
        "CREATE INDEX IF NOT EXISTS `idx_group_source_kind_order` " +
            "ON `media_group` (`source_id`, `kind`, `provider_order`)",
    )

    /**
     * Replaces catalogue tables, leaving user data untouched.
     *
     * @param createStatements the new tables' DDL, copied from the exported schema
     *   under `data/database/schemas/` at the target version. It is passed in
     *   rather than written from memory for the reason migrations usually rot:
     *   hand-typed DDL drifts from the entity definitions, and Room validates the
     *   real thing on open. The exported schema is checked in, so the statements
     *   are reviewable in the diff that adds them.
     * @param dropTables which tables this migration replaces. Only catalogue
     *   tables belong here; the check below enforces that.
     */
    fun recreateCatalogue(
        from: Int,
        to: Int,
        createStatements: List<String>,
        dropTables: List<String> = CATALOGUE_TABLES,
    ): Migration {
        val protected = dropTables.filter { it in USER_TABLES }
        require(protected.isEmpty()) {
            "refusing to drop user data: $protected — those tables need a real migration"
        }
        return object : Migration(from, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in dropTables) db.execSQL("DROP TABLE IF EXISTS $table")
                for (statement in createStatements) db.execSQL(statement)
                // The catalogue is gone, so the app must not believe it is current.
                clearSyncState(db)
            }
        }
    }

    /**
     * Clears every source's sync state without touching the credentials.
     *
     * Needed by any migration that changes how content is parsed or classified:
     * otherwise the app considers the stored catalogue up to date and never
     * re-imports it under the new rules.
     */
    fun forgetSyncState(from: Int, to: Int): Migration = object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) = clearSyncState(db)
    }

    internal fun clearSyncState(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE source SET etag = NULL, last_modified = NULL, content_hash = NULL, " +
                "last_import_at = NULL, last_epg_import_at = NULL, item_count = 0",
        )
    }

    /** Rebuildable from the provider. */
    val CATALOGUE_TABLES = listOf("media_fts", "media", "media_group", "programme")

    /** Not rebuildable from anywhere. */
    val USER_TABLES = listOf("favorite", "playback_progress", "source", "custom_order")
}
