package com.castivio.feature.home

import com.castivio.domain.MediaKind
import com.castivio.domain.SectionRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The section Home opens by itself: Live TV, pressed with nothing of it on the device, as soon
 * as its first category is listed.
 *
 * A wait ends one of four ways, and only the first of them opens anything:
 *  - **a category is listed**, or the load ends with none at all -- see [awaitOf] -- and the
 *    section opens, once;
 *  - **the viewer moves on**: another card is pressed, or Home is left -- [stop];
 *  - **the load fails**, which its card says. A later load of the section answers a press
 *    of its own, so it opens nothing on this one's behalf;
 *  - **the subscription changes** under it, and the queue forgets every answer.
 *
 * Out of `HomeViewModel` so that all four are proved without a device. [scope] is the
 * holder's own, and [runs] is the queue's answers, `SectionLoads.runs`.
 */
internal class SectionOpener(scope: CoroutineScope, runs: Flow<Map<MediaKind, SectionRun>>) {

    private val awaited = MutableStateFlow<MediaKind?>(null)

    private val _opens = MutableSharedFlow<CatalogSection>(extraBufferCapacity = 1)

    /**
     * A section to open now, without a press. Told only to a Home that is collecting it --
     * nothing is kept for later, so a viewer who has left Home is not taken anywhere on the
     * way back.
     */
    val opens: SharedFlow<CatalogSection> = _opens.asSharedFlow()

    init {
        scope.launch {
            combine(awaited, runs) { kind, answers -> kind?.let { it to awaitOf(it, answers) } }
                .collect { waiting ->
                    val (kind, next) = waiting ?: return@collect
                    // Only if this is still the wait: an answer can be read a moment after
                    // the viewer moved on, and that answer must not open anything.
                    when (next) {
                        Await.Keep -> Unit
                        Await.Open -> if (awaited.compareAndSet(kind, null)) {
                            _opens.tryEmit(CatalogSection.entries.first { it.kind == kind })
                        }
                        Await.Drop -> awaited.compareAndSet(kind, null)
                    }
                }
        }
    }

    /** Open [kind] by itself when its first category is listed, in place of any earlier wait. */
    fun await(kind: MediaKind) {
        awaited.value = kind
    }

    /** Nothing opens by itself any more. */
    fun stop() {
        awaited.value = null
    }
}
