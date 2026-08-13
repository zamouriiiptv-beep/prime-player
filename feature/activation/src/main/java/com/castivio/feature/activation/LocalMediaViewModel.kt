package com.castivio.feature.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.LocalFolder
import com.castivio.domain.LocalMediaKind
import com.castivio.domain.LocalMediaLibrary
import com.castivio.domain.LocalTrack
import com.castivio.domain.LocalVideo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the four media screens show, read from the device.
 *
 * ## Four screens, one state holder
 *
 * They are four views of two queries. Splitting them into four view models would mean four
 * copies of the permission check, the paging and the empty-state logic, and the first time
 * one of them was fixed the other three would not be.
 *
 * ## Paging, because a phone is not small any more
 *
 * A device with eight thousand clips is ordinary. The rule the rest of this product keeps —
 * memory is O(page) and never O(library) — applies here exactly as it does to a provider's
 * catalogue, so a page is sixty and the next one is fetched when the list nears its end.
 *
 * ## The permission is a state, not an error
 *
 * A user who has declined is not a failure condition. [LocalMediaState.granted] is false,
 * the screen says what it needs and offers to ask again, and nothing throws. The library
 * itself returns an empty page rather than a `SecurityException` for the same reason.
 */
@HiltViewModel
class LocalMediaViewModel @Inject constructor(
    private val library: LocalMediaLibrary,
) : ViewModel() {

    private val _state = MutableStateFlow(LocalMediaState(granted = library.hasPermission()))
    val state: StateFlow<LocalMediaState> = _state.asStateFlow()

    private var loading: Job? = null

    /** What to ask the user for. The implementation knows; the screen only launches it. */
    fun requiredPermissions(): Array<String> = library.requiredPermissions().toTypedArray()

    /**
     * Read the permission again and reload if it changed.
     *
     * Called when the screen resumes, because the user can grant the permission in system
     * settings and come back — and a library that stayed empty until the app was restarted
     * would look broken at exactly the moment the user had just fixed it.
     */
    fun refreshPermission() {
        val granted = library.hasPermission()
        if (granted == _state.value.granted) return
        _state.value = _state.value.copy(granted = granted)
        if (granted) reload()
    }

    /** Start again from the first page. What a screen calls when it opens. */
    fun open(kind: LocalMediaKind, folder: String? = null) {
        val current = _state.value
        if (current.kind == kind && current.folder == folder && current.loadedOnce) return
        _state.value = LocalMediaState(
            granted = library.hasPermission(),
            kind = kind,
            folder = folder,
            loading = true,
        )
        reload()
    }

    /** Walk into a folder in a picker, or back out of one when [folder] is null. */
    fun openFolder(folder: String?) = open(_state.value.kind, folder)

    private fun reload() {
        loading?.cancel()
        val snapshot = _state.value
        if (!snapshot.granted) {
            _state.value = snapshot.copy(loading = false, loadedOnce = true)
            return
        }
        loading = viewModelScope.launch {
            val folders = library.folders(snapshot.kind)
            val page = fetch(snapshot.kind, snapshot.folder, offset = 0)
            _state.value = _state.value.copy(
                videos = page.videos,
                tracks = page.tracks,
                folders = folders,
                loading = false,
                loadedOnce = true,
                exhausted = page.size < LocalMediaLibrary.PAGE,
            )
        }
    }

    /**
     * The next page, when the list is running out.
     *
     * Guarded three ways — already loading, already exhausted, no permission — because the
     * caller is a scroll position and a scroll position fires repeatedly.
     */
    fun loadMore() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.exhausted || !snapshot.granted) return
        _state.value = snapshot.copy(loading = true)
        loading = viewModelScope.launch {
            val offset = snapshot.videos.size + snapshot.tracks.size
            val page = fetch(snapshot.kind, snapshot.folder, offset)
            _state.value = _state.value.copy(
                videos = _state.value.videos + page.videos,
                tracks = _state.value.tracks + page.tracks,
                loading = false,
                exhausted = page.size < LocalMediaLibrary.PAGE,
            )
        }
    }

    private suspend fun fetch(kind: LocalMediaKind, folder: String?, offset: Int): Page =
        when (kind) {
            LocalMediaKind.VIDEO -> Page(videos = library.videos(folder, offset))
            LocalMediaKind.AUDIO -> Page(tracks = library.audio(folder, offset))
        }

    private data class Page(
        val videos: List<LocalVideo> = emptyList(),
        val tracks: List<LocalTrack> = emptyList(),
    ) {
        val size: Int get() = videos.size + tracks.size
    }
}

/**
 * One value for all four screens.
 *
 * [loadedOnce] separates "there is nothing on this device" from "nothing has been read
 * yet", which are the same empty list and two completely different things to draw. Without
 * it every library flashes its empty state for the length of the first query.
 */
data class LocalMediaState(
    val granted: Boolean = false,
    val kind: LocalMediaKind = LocalMediaKind.VIDEO,
    val folder: String? = null,
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val exhausted: Boolean = false,
    val videos: List<LocalVideo> = emptyList(),
    val tracks: List<LocalTrack> = emptyList(),
    val folders: List<LocalFolder> = emptyList(),
) {
    /** Nothing to show, and we have actually looked. */
    val isEmpty: Boolean
        get() = loadedOnce && granted && videos.isEmpty() && tracks.isEmpty() && folders.isEmpty()
}
