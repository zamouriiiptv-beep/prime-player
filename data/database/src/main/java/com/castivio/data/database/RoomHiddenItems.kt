package com.castivio.data.database

import com.castivio.data.database.dao.HiddenDao
import com.castivio.data.database.entity.HiddenEntity
import com.castivio.domain.HiddenItems
import com.castivio.domain.HiddenKind
import com.castivio.domain.HiddenNow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

class RoomHiddenItems(
    private val dao: HiddenDao,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Told after every write, so the lists, the rail and the counts read again -- see
     * `RoomCatalogRepository.hiddenChanged`. Room's own notice of a change to `hidden_item` did not
     * reach them on a device.
     */
    private val onChanged: () -> Unit = {},
) : HiddenItems {

    /** Every write, counted, so [count] reads again after each -- for the reason [onChanged] is told. */
    private val writes = MutableStateFlow(0L)

    private val _recent = MutableStateFlow(HiddenNow())

    /** Set as [hide] is called, before the write -- see [HiddenItems.recent]. */
    override val recent: StateFlow<HiddenNow> = _recent

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun count(kind: HiddenKind): Flow<Int> = writes.flatMapLatest { dao.count(kind.name) }

    override suspend fun hide(kind: HiddenKind, ids: Collection<String>) {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return
        // Shown gone at once; the write confirms it, and a write that fails gives them back.
        _recent.update { it.with(kind, distinct) }
        val now = clock()
        try {
            distinct.chunked(MAX_IDS_PER_STATEMENT).forEach { chunk ->
                dao.addAll(chunk.map { HiddenEntity(itemId = it, type = kind.name, hiddenAt = now) })
            }
        } catch (e: Throwable) {
            _recent.update { it.without(kind, distinct) }
            throw e
        }
        changed()
    }

    override suspend fun restoreAll(kind: HiddenKind) {
        dao.clear(kind.name)
        _recent.update { it.cleared(kind) }
        changed()
    }

    private fun changed() {
        writes.update { it + 1 }
        onChanged()
    }

    private companion object {
        /** SQLite's bind-variable limit is 999 on older Android; stay clear of it. */
        const val MAX_IDS_PER_STATEMENT = 300
    }
}
