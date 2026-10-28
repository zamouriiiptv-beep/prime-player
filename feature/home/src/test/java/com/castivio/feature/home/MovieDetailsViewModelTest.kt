package com.castivio.feature.home

import com.castivio.domain.Movie
import com.castivio.domain.MovieDetails
import com.castivio.domain.MovieDetailsRepository
import com.castivio.domain.RequestTimes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * What a film's page is told while its details are asked for: nothing until they come, never the details of the film
 * it was opened after, and what the asking cost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun film(id: String) = Movie(id = id, title = id, artworkUrl = null, streamUrl = "http://x/$id.mkv", sourceId = "src", providerRef = id)

    /** Answers what the test says for each film, when the test lets it. */
    private class Panel : MovieDetailsRepository {
        val answers = mutableMapOf<String, CompletableDeferred<MovieDetails?>>()
        val costs = mutableMapOf<String, RequestTimes>()
        override suspend fun details(movie: Movie, onTimes: (RequestTimes) -> Unit): MovieDetails? {
            costs[movie.id]?.let(onTimes)
            return answers.getOrPut(movie.id) { CompletableDeferred() }.await()
        }
    }

    @Test
    fun `nothing is known until the provider answers, and then the details are`() = runTest(dispatcher) {
        val panel = Panel()
        val model = MovieDetailsViewModel(panel)

        model.load(film("a"))
        advanceUntilIdle()
        assertNull(model.details.value)

        panel.answers.getValue("a").complete(MovieDetails(plot = "A storm."))
        advanceUntilIdle()
        assertEquals("A storm.", model.details.value?.plot)
    }

    @Test
    fun `a late answer for the film opened before is not taken for this one's`() = runTest(dispatcher) {
        val panel = Panel()
        val model = MovieDetailsViewModel(panel)

        model.load(film("a"))
        advanceUntilIdle()
        model.load(film("b"))
        advanceUntilIdle()
        panel.answers.getValue("a").complete(MovieDetails(plot = "The first film's."))
        advanceUntilIdle()

        assertNull(model.details.value)
        panel.answers.getValue("b").complete(MovieDetails(plot = "The second film's."))
        advanceUntilIdle()
        assertEquals("The second film's.", model.details.value?.plot)
    }

    @Test
    fun `what the asking cost is kept for the page, and cleared when another film is opened`() = runTest(dispatcher) {
        val panel = Panel().apply { costs["a"] = RequestTimes(askMs = 5_000, readMs = 200, tries = 2) }
        val model = MovieDetailsViewModel(panel)

        model.load(film("a"))
        advanceUntilIdle()
        assertEquals(2, model.times.value?.tries)

        model.load(film("b"))
        advanceUntilIdle()
        assertNull(model.times.value)
    }
}
