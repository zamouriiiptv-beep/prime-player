package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Whose subscription this is, and nothing more.
 *
 * The only thing Home reads, and it reads it from the row the sign-in wrote — not from
 * the provider and not from the catalogue. A name in the corner should not be able to
 * cost a request, which is exactly what it would cost if Home derived it from content.
 */
@HiltViewModel
class ProviderViewModel @Inject constructor(
    sources: SourceRepository,
) : ViewModel() {

    /** Null before a provider is added, and while the first read is in flight. */
    val label: StateFlow<String?> = sources.active()
        .map { it?.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    private companion object {
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
