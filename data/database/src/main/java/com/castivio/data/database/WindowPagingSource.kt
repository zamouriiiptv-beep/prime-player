package com.castivio.data.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import com.castivio.data.database.entity.MediaEntity
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The channel list's pages, read with a plain `LIMIT/OFFSET` query -- outside any transaction.
 *
 * ## Why not the source Room generates
 *
 * Room's own paging source reads its first page inside a transaction. A transaction needs
 * SQLite's one write connection, so while a section is being written that first page waits
 * for the instant between two of the writer's commits -- and Room's check for changes waits
 * for the same instant, and is queued first, because every Room transaction ends by asking
 * for one. The check finds the rows the writer just committed and invalidates the source
 * before its page is read, and Paging drops a page from an invalidated source. So no first
 * page landed until the writer stopped: the board opened on a spinner, and a category chosen
 * while the section arrived kept the previous one's channels on screen until the end.
 *
 * A read outside a transaction takes a reader connection of its own and waits for nobody,
 * so its page is back in milliseconds, before any check can invalidate it.
 *
 * It is invalidated as Room's is, when `media` or `source` change, and reads the same rows
 * in the same order: [window] is one of the queries the generated source wrapped.
 *
 * @param window the rows from `offset`, at most `limit` of them.
 * @param background where the observer is added and removed -- Room's query executor: both
 *   can touch the database.
 */
internal class WindowPagingSource(
    private val tracker: InvalidationTracker,
    private val background: Executor,
    private val window: suspend (offset: Int, limit: Int) -> List<MediaEntity>,
) : PagingSource<Int, MediaEntity>() {

    private val observer = object : InvalidationTracker.Observer(arrayOf("media", "source")) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }
    private val registered = AtomicBoolean(false)

    init {
        registerInvalidatedCallback {
            if (registered.get()) background.execute { tracker.removeObserver(observer) }
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaEntity> {
        // Registered on the first load, off the main thread that builds this source and calls
        // `load`: adding an observer can touch the database.
        if (registered.compareAndSet(false, true)) {
            withContext(background.asCoroutineDispatcher()) {
                tracker.addObserver(observer)
                // Invalidated while it was being added: the callback may have run first.
                if (invalid) tracker.removeObserver(observer)
            }
        }
        val key = params.key ?: 0
        val offset = if (params is LoadParams.Prepend) max(0, key - params.loadSize) else key
        val limit = if (params is LoadParams.Prepend) min(params.loadSize, key) else params.loadSize
        return try {
            val rows = window(offset, limit)
            if (invalid) {
                LoadResult.Invalid()
            } else {
                LoadResult.Page(
                    data = rows,
                    prevKey = if (offset <= 0 || rows.isEmpty()) null else offset,
                    nextKey = if (rows.size < limit) null else offset + rows.size,
                    itemsBefore = offset,
                )
            }
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /** Back near where the viewer was, as Room's source does. */
    override fun getRefreshKey(state: PagingState<Int, MediaEntity>): Int? =
        state.anchorPosition?.let { max(0, it - state.config.initialLoadSize / 2) }
}
