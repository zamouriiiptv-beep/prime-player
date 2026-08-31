package com.castivio.data.subtitles.di

import com.castivio.data.subtitles.BuildConfig
import com.castivio.data.subtitles.OpenSubtitlesApi
import com.castivio.data.subtitles.OpenSubtitlesCredentials
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The one place `BuildConfig` is read, and therefore the one place a credential exists.
 *
 * Everything downstream takes an [OpenSubtitlesCredentials] value, which a test can build
 * from literals. That is the point of the indirection: a class that read `BuildConfig`
 * itself could only be tested by a build that had credentials in it, which would mean either
 * committing them or having no test.
 */
@Module
@InstallIn(SingletonComponent::class)
object SubtitlesModule {

    @Provides
    @Singleton
    fun credentials(): OpenSubtitlesCredentials = OpenSubtitlesCredentials(
        apiKey = BuildConfig.OPENSUBTITLES_API_KEY,
        username = BuildConfig.OPENSUBTITLES_USERNAME,
        password = BuildConfig.OPENSUBTITLES_PASSWORD,
    )

    /**
     * Its own client, not the one the playlists use, and [Subtitles]-qualified so that the
     * two can coexist — an unqualified second `OkHttpClient` is a duplicate binding and
     * Dagger will not build the graph at all.
     *
     * A subtitle search is a handful of small JSON calls to one host, and it is the least
     * important thing in the application: it must not be able to occupy a connection in the
     * pool that a stream is waiting for, and its timeouts should be short enough that a
     * viewer gives up on the search rather than on the film. Ten seconds is generous for a
     * 60 KB file and far shorter than the ninety a video read is given.
     */
    @Provides
    @Singleton
    @Subtitles
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun api(
        @Subtitles client: OkHttpClient,
        credentials: OpenSubtitlesCredentials,
    ): OpenSubtitlesApi = OpenSubtitlesApi(client, credentials)

    private const val TIMEOUT_SECONDS = 10L
}
