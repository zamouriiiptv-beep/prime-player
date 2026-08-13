package com.castivio.playback.api

/**
 * Where an engine comes from.
 *
 * In the pure module, and that placement is the point: the player's view model runs the
 * whole fallback sequence — open on one, deadline expires, open on the other, remember
 * which worked — and every step of it is logic worth testing. Testing it against this
 * interface needs no decoder, no surface and no device, so the tests run on the JVM in
 * milliseconds and actually get run.
 *
 * It also keeps `:feature:player` honest. The player depends on this module and not on
 * `:playback:engine-media3`, so there is no route by which a screen could reach an
 * ExoPlayer type — which is the whole reason [PlaybackEngine] exists.
 */
interface EngineFactory {

    /**
     * A fresh engine for [id], tuned for [kind].
     *
     * Fresh rather than pooled, deliberately. An engine that has failed is an engine in an
     * unknown state, and reusing one to save a few milliseconds of construction is how a
     * channel change inherits the previous channel's error.
     */
    fun create(id: EngineId, kind: MediaKind): PlaybackEngine
}
