package com.castivio.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.castivio.data.database.entity.VodDetailEntity

@Dao
interface VodDetailDao {

    @Query("SELECT * FROM vod_detail WHERE source_id = :sourceId AND provider_ref = :providerRef")
    suspend fun get(sourceId: String, providerRef: String): VodDetailEntity?

    /** `REPLACE`: a film asked for again takes the fresh reply, and its time of fetching with it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: VodDetailEntity)
}
