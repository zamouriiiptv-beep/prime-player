package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.PlayerPulse
import com.castivio.domain.ProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * **What was watched, as it is watched** -- the record the rail's Recently watched entry lists, on the
 * channels' board and on the films'.
 *
 * The list was there and nothing ever wrote to it: `playback_progress` had no writer on any device, so
 * the entry had nothing to show and was left without an action. The shell, which is the one place that
 * knows both which channel a picture is of and when the picture shows, tells this; it decides nothing.
 */
@HiltViewModel
class RecentChannelsViewModel @Inject constructor(
    private val progress: ProgressRepository,
) : ViewModel() {

    /**
     * [channelId]'s picture showed -- or the film of that id was started: it is what was watched last.
     * Written off the main thread; a write that fails costs that one place in the list, and never the
     * picture.
     */
    fun watched(channelId: String) {
        viewModelScope.launch {
            try {
                progress.watched(channelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not recorded this time: the next time the channel is watched records it.
            }
        }
    }
}

/**
 * The live channel being watched now: [channelId], once the player has a picture of [url] -- the stream the
 * channel was started with -- and null while that picture is opening, after it stopped, or when the picture
 * is of another stream. A press is not a watch; a picture is.
 */
fun watchedChannel(channelId: String?, url: String?, pulse: PlayerPulse?): String? =
    channelId?.takeIf { url != null && pulse?.playingUrl == url }
