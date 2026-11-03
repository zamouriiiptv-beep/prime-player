package com.castivio.tv.shell

import androidx.lifecycle.ViewModel
import com.castivio.domain.PlaybackActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Hands the shell the app-wide [PlaybackActivity] it has to keep told. A view model only because that is how a composable
 * is given an injected object here; it holds nothing of its own.
 */
@HiltViewModel
class PlaybackActivityViewModel @Inject constructor(
    val activity: PlaybackActivity,
) : ViewModel()
