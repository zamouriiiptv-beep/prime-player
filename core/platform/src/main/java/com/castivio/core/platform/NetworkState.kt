package com.castivio.core.platform

/**
 * Whether this device is on a network at all.
 *
 * Asked once a provider has failed to answer, to tell two causes apart that need opposite
 * advice: a server that does not answer is the provider's to put right, and a device with
 * no connection is the viewer's. Telling somebody to call their provider while their own
 * router is off sends them to the wrong person.
 */
interface NetworkState {

    /**
     * False only when the platform says there is no connection: no active network, or one
     * that cannot reach the internet. Anything it cannot tell reads as online, so the
     * viewer's own network is never blamed on a guess.
     */
    fun isOnline(): Boolean
}
