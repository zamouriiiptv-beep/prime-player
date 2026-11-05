package com.castivio.data.worldradio.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.castivio.data.worldradio.entity.DirectoryCacheEntity
import com.castivio.data.worldradio.entity.SavedStationEntity
import com.castivio.data.worldradio.entity.StationHealthEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedStationDao {

    /** The viewer's own handful: held whole, the one added last first. */
    @Query("SELECT * FROM saved_station WHERE favorite_at IS NOT NULL ORDER BY favorite_at DESC")
    fun favorites(): Flow<List<SavedStationEntity>>

    @Query("SELECT uuid FROM saved_station WHERE favorite_at IS NOT NULL")
    fun favoriteIds(): Flow<List<String>>

    @Query("SELECT * FROM saved_station WHERE played_at IS NOT NULL ORDER BY played_at DESC LIMIT :limit")
    fun recents(limit: Int): Flow<List<SavedStationEntity>>

    @Query("SELECT * FROM saved_station WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): SavedStationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(station: SavedStationEntity)

    @Query("UPDATE saved_station SET favorite_at = NULL WHERE uuid = :uuid")
    suspend fun clearFavorite(uuid: String)

    /** A row that is neither a favourite nor a listen is nothing to keep. */
    @Query("DELETE FROM saved_station WHERE favorite_at IS NULL AND played_at IS NULL")
    suspend fun dropUnused()

    /** Keeps the [keep] latest listens, and every favourite whatever its listens. */
    @Query(
        """
        DELETE FROM saved_station
        WHERE favorite_at IS NULL
          AND uuid NOT IN (SELECT uuid FROM saved_station WHERE played_at IS NOT NULL ORDER BY played_at DESC LIMIT :keep)
        """,
    )
    suspend fun trimListens(keep: Int)

    /** A favourite that is no longer listened to loses its listen but not its place among the favourites. */
    @Query(
        """
        UPDATE saved_station SET played_at = NULL
        WHERE favorite_at IS NOT NULL
          AND uuid NOT IN (SELECT uuid FROM saved_station WHERE played_at IS NOT NULL ORDER BY played_at DESC LIMIT :keep)
        """,
    )
    suspend fun forgetOldListensOfFavorites(keep: Int)
}

@Dao
interface StationHealthDao {

    /** Callers chunk below SQLite's bind-variable limit, as every `IN` query does. */
    @Query("SELECT * FROM station_health WHERE uuid IN (:uuids)")
    suspend fun of(uuids: List<String>): List<StationHealthEntity>

    @Query("SELECT * FROM station_health WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): StationHealthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(health: StationHealthEntity)
}

@Dao
interface DirectoryCacheDao {

    @Query("SELECT * FROM directory_cache WHERE cache_key = :key")
    suspend fun byKey(key: String): DirectoryCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: DirectoryCacheEntity)
}
