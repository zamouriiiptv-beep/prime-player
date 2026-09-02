package com.castivio.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    val ALL: Array<Migration> = arrayOf(ADD_GROUP_PROVIDER_REF)

    /**
     * 1 → 2: categories learn their provider id and the two times they were fetched.
     *
     * Three added columns and nothing dropped, so it is a hand-written migration rather
     * than [recreateCatalogue] — there is no reason to make a user re-import a working
     * catalogue to add a column that is allowed to be null.
     *
     * The sync state is cleared even so. A catalogue imported by the previous version
     * has categories with no `provider_ref`, and on-demand loading cannot ask for a
     * category it has no id for; forgetting the timestamps is what makes the next visit
     * to a section re-list its categories and fill the column in.
     */
    private val ADD_GROUP_PROVIDER_REF: Migration
        get() = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_group ADD COLUMN provider_ref TEXT")
                db.execSQL("ALTER TABLE media_group ADD COLUMN items_loaded_at INTEGER")
                db.execSQL("ALTER TABLE media_group ADD COLUMN listed_at INTEGER NOT NULL DEFAULT 0")
                clearSyncState(db)
            }
        }

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
    val USER_TABLES = listOf("favorite", "playback_progress", "source")
}
