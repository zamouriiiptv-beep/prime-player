package com.castivio.data.database

import com.castivio.data.database.dao.HiddenDao
import com.castivio.data.database.entity.HiddenEntity
import com.castivio.domain.HiddenItems
import com.castivio.domain.HiddenKind
import kotlinx.coroutines.flow.Flow

class RoomHiddenItems(
    private val dao: HiddenDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : HiddenItems {

    override fun count(kind: HiddenKind): Flow<Int> = dao.count(kind.name)

    override suspend fun hide(kind: HiddenKind, ids: Collection<String>) {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return
        val now = clock()
        distinct.chunked(MAX_IDS_PER_STATEMENT).forEach { chunk ->
            dao.addAll(chunk.map { HiddenEntity(itemId = it, type = kind.name, hiddenAt = now) })
        }
    }

    override suspend fun restoreAll(kind: HiddenKind) = dao.clear(kind.name)

    private companion object {
        /** SQLite's bind-variable limit is 999 on older Android; stay clear of it. */
        const val MAX_IDS_PER_STATEMENT = 300
    }
}
