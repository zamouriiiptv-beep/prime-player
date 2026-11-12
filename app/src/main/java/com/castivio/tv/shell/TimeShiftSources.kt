package com.castivio.tv.shell

import androidx.lifecycle.ViewModel
import com.castivio.domain.ArchiveTimeShift
import com.castivio.domain.CatalogRepository
import com.castivio.domain.CatchUpChannel
import com.castivio.domain.CatchUpGateway
import com.castivio.domain.TimeShiftSource
import com.castivio.domain.time.TrustedTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * The means of rewinding a channel, made for each channel the shell opens in the player.
 *
 * The player knows neither the catalogue nor the provider, so the shell -- which knows both -- gives each live request a
 * [TimeShiftSource]: the provider's archive, read through the same gateway and the same memory the catch-up screen uses, against the
 * same trusted clock. Nothing is asked here: a source is made when a channel is pressed and reads the catalogue for the channel's
 * provider reference, and the archive for what it holds, only when the player asks -- a moment after the channel has a picture.
 */
class TimeShiftSources @Inject constructor(
    private val gateway: CatchUpGateway,
    private val catalog: CatalogRepository,
    private val clock: TrustedTime,
) {

    /**
     * The source for the channel [mediaId], named [title], whose provider says it keeps [archiveHours] hours.
     *
     * The channel's provider reference -- what its archive is addressed by -- is the one thing the press does not carry, so it is read
     * from the catalogue the first time the source is asked anything. A catalogue that cannot be read leaves the channel without one,
     * and a channel without one offers no rewind.
     */
    fun forChannel(mediaId: String, title: String, archiveHours: Int): TimeShiftSource = ArchiveTimeShift(
        gateway = gateway,
        channel = {
            val reference = try {
                catalog.channelRefs(listOf(mediaId)).firstOrNull()?.providerRef
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            }
            CatchUpChannel(mediaId = mediaId, providerRef = reference, title = title, archiveHours = archiveHours)
        },
        nowMs = clock::nowMs,
    )
}

/**
 * Hands the shell the [TimeShiftSources] it gives each channel's request. A view model only because that is how a composable is given
 * an injected object here; it holds nothing of its own.
 */
@HiltViewModel
class TimeShiftViewModel @Inject constructor(
    val sources: TimeShiftSources,
) : ViewModel()
