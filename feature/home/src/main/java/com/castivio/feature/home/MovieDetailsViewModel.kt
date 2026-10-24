package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.castivio.domain.Movie
import com.castivio.domain.MovieDetails
import com.castivio.domain.MovieDetailsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the provider says about the film on a page, asked for when the page opens.
 *
 * Nothing here blocks the film: Play is enabled from the moment the page is drawn, and the details arrive
 * beside it. A film whose details cannot be had keeps null, and the page draws the facts it already has.
 */
@HiltViewModel
class MovieDetailsViewModel @Inject constructor(
    private val repository: MovieDetailsRepository,
) : ViewModel() {

    private val _details = MutableStateFlow<MovieDetails?>(null)

    /** The film's details; null while they are on their way, and for good where the provider has none. */
    val details: StateFlow<MovieDetails?> = _details.asStateFlow()

    private var shown: String? = null

    /** Asks for [movie]'s details, once for each film: a film already asked for keeps its answer. */
    fun load(movie: Movie) {
        if (shown == movie.id) return
        shown = movie.id
        _details.value = null
        viewModelScope.launch {
            _details.value = try {
                repository.details(movie)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
}
