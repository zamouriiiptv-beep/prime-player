package com.castivio.data.database

import androidx.paging.PagingSource
import com.castivio.data.parsing.CatalogImportEngine
import com.castivio.domain.ImportSummary

/**
 * Shared test scaffolding.
 *
 * Imports go through the real [CatalogImportEngine] rather than inserting rows
 * directly: the engine and the writer are two halves of one path, and a test
 * that hand-inserts entities would pass while the actual import was broken.
 */
internal object Fixtures {

    fun import(
        database: CastivioDatabase,
        lines: List<String>,
        sourceId: String = "src",
        now: Long = 1_000L,
        batchSize: Int = 50,
        isCancelled: () -> Boolean = { false },
    ): ImportSummary {
        register(database, sourceId, now)
        return CatalogImportEngine(
            writer = RoomCatalogWriter(database) { now },
            batchSize = batchSize,
            clock = { now },
        ).importM3u(sourceId, lines.asSequence(), isCancelled = isCancelled)
    }

    /**
     * Saves [sourceId] as a subscription, and makes it the active one if none is.
     *
     * The catalogue reads are scoped to the active subscription, so rows written under
     * an id that no `source` row carries are invisible to them — which is the point of
     * the scoping, and would leave every read in these tests reading nothing.
     */
    fun register(database: CastivioDatabase, sourceId: String, now: Long = 1_000L) {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT OR IGNORE INTO source (id, kind, label, url, item_count, created_at, is_active) " +
                "VALUES (?, 'M3U_URL', ?, ?, 0, ?, 0)",
            arrayOf<Any>(sourceId, sourceId, "http://host/$sourceId.m3u", now),
        )
        db.execSQL(
            "UPDATE source SET is_active = 1 WHERE id = ? " +
                "AND NOT EXISTS (SELECT 1 FROM source WHERE is_active = 1)",
            arrayOf<Any>(sourceId),
        )
    }

    /** Makes [sourceId] the active subscription, the way switching playlists does. */
    fun activate(database: CastivioDatabase, sourceId: String) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE source SET is_active = CASE WHEN id = ? THEN 1 ELSE 0 END",
            arrayOf<Any>(sourceId),
        )
    }

    /** A small playlist covering all four kinds. */
    fun mixedPlaylist(): List<String> = listOf(
        "#EXTM3U",
        """#EXTINF:-1 tvg-id="nova.1" tvg-logo="http://cdn/nova.png" group-title="Sports",Nova Sports 1""",
        "http://host/live/u/p/1.ts",
        """#EXTINF:-1 tvg-id="atlas.1" group-title="Sports",Atlas Sport HD""",
        "http://host/live/u/p/2.ts",
        """#EXTINF:-1 group-title="News",قناة الرياضة""",
        "http://host/live/u/p/3.ts",
        """#EXTINF:7200 group-title="VOD Movies",[4K] The Matrix""",
        "http://host/movie/u/p/10.mp4",
        """#EXTINF:6000 group-title="VOD Movies",Alpha""",
        "http://host/movie/u/p/11.mp4",
        """#EXTINF:-1 group-title="Radio MA",Radio Mars""",
        "http://host/live/u/p/20.ts",
        """#EXTINF:-1 group-title="Series",Breaking Bad S01E01 - Pilot""",
        "http://host/series/u/p/30.mkv",
        """#EXTINF:-1 group-title="Series",Breaking Bad S01E02 - Cat in the Bag""",
        "http://host/series/u/p/31.mkv",
        """#EXTINF:-1 group-title="Series",Breaking Bad S02E01 - Seven Thirty-Seven""",
        "http://host/series/u/p/32.mkv",
        """#EXTINF:-1 group-title="Series",Chernobyl S01E01""",
        "http://host/series/u/p/40.mkv",
    )

    fun livePlaylist(entries: Int, group: (Int) -> String = { "Sports" }): List<String> =
        buildList {
            add("#EXTM3U")
            for (i in 0 until entries) {
                add("""#EXTINF:-1 tvg-id="ch$i" group-title="${group(i)}",Channel $i""")
                add("http://host/live/u/p/$i.ts")
            }
        }
}

/** First page of a [PagingSource], the way Paging itself would ask for it. */
internal suspend fun <T : Any> PagingSource<Int, T>.firstPage(size: Int = 30): List<T> {
    val result = load(PagingSource.LoadParams.Refresh(key = null, loadSize = size, placeholdersEnabled = false))
    return (result as PagingSource.LoadResult.Page).data
}
