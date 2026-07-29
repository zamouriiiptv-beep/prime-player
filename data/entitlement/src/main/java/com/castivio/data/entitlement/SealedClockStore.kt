package com.castivio.data.entitlement

import com.castivio.domain.time.ClockState
import com.castivio.domain.time.ClockStore

/**
 * The clock's memory, sealed on disk and cached in front of it.
 *
 * [ClockStore] is synchronous because a clock that has to be awaited is not a clock —
 * the entitlement gate reads one before the first frame. That rules out doing a
 * keystore fetch and an AES pass on every reading, so the state is held in memory after
 * the first load and only written when it changes, which the clock itself already
 * arranges.
 *
 * Writes go all the way to disk rather than being queued. The high-water mark is the
 * single value standing between a user and an endless trial, and losing the last write
 * to a power cut would hand back exactly the time they had just spent.
 *
 * Safe from any thread: the cached state is guarded, and the underlying preferences
 * file is its own lock.
 */
internal class SealedClockStore(
    private val store: SealedStore,
) : ClockStore {

    private var cached: ClockState? = null

    @Synchronized
    override fun load(): ClockState {
        cached?.let { return it }

        // An unreadable blob is a fresh clock rather than a refusal to start. The
        // consequence is bounded and self-healing: the mark begins again from the device
        // clock and the first trusted anchor corrects it, whereas an app that would not
        // open because its clock file was edited would be a much larger problem than the
        // one being defended against.
        val loaded = when (val raw = store.read(SealedStore.KEY_CLOCK)) {
            is SealedRead.Opened -> EntitlementCodec.decodeClock(raw.bytes) ?: ClockState()
            is SealedRead.Absent, is SealedRead.Unsealable -> ClockState()
        }
        cached = loaded
        return loaded
    }

    @Synchronized
    override fun save(state: ClockState) {
        cached = state
        store.writeNow(SealedStore.KEY_CLOCK, EntitlementCodec.encode(state))
    }
}
