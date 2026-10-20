package com.castivio.domain

/**
 * The copy of a playlist this device keeps, so a subscription's sections are read from
 * one download instead of one each.
 *
 * ## Why a playlist is kept at all
 *
 * A playlist is one file holding the channels, the films and the episodes together, and
 * no request asks it for the films alone. Loaded the way a panel's sections are, each
 * section would download the whole file again; loaded whole, the first section opened
 * waits for every row of the other three to be written. Kept, the first section waits
 * for the download and its own rows, and the others for their own rows only.
 *
 * ## Why only this, here
 *
 * The domain asks one thing of the copy: to be forgotten. The section loader never sees
 * it -- the importer reads it or downloads in its place -- and the two use cases that
 * must not leave one behind are a refresh, after which the next section has to come from
 * the provider rather than from the device, and a subscription's removal, after which
 * nothing of it may stay.
 */
interface PlaylistCopies {

    /** Forgets [sourceId]'s copy: the next section read downloads the playlist again. */
    suspend fun discard(sourceId: String)
}
