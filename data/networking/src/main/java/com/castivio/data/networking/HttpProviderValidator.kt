package com.castivio.data.networking

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.core.common.DefaultDispatchers
import com.castivio.core.common.Outcome
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderStatus
import com.castivio.domain.ProviderValidator
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Checks a provider before importing anything from it.
 *
 * This exists because of what a login failure looks like otherwise. Import first
 * and every problem arrives as "no channels": wrong password, expired
 * subscription, all connections in use, unreachable host. Xtream answers all four
 * questions in one small request, and an M3U URL answers the reachable/authorised
 * pair with a range request that costs a few kilobytes.
 *
 * ## Blocking underneath, and it moves itself off the caller's thread
 *
 * Everything below is synchronous OkHttp: `execute()`, not `enqueue()`. This used to
 * document that as an obligation on the caller — "the caller is a ViewModel on an IO
 * dispatcher" — and nothing enforced it. Nothing had to: `suspend` says a function may
 * be *suspended*, not that it runs anywhere in particular, so a `suspend` wrapper
 * around a blocking call runs the blocking call on whatever thread called it.
 *
 * The caller that mattered was not on an IO dispatcher. `ActivationViewModel.submit`
 * collects on `viewModelScope`, which is `Dispatchers.Main.immediate`, and the flow it
 * collects is a cold `flow { }` with no `flowOn` — so a cold flow's body runs in the
 * collector's context, and a DNS lookup, a TCP handshake and an HTTP round trip all
 * happened on the main thread. With a 12-second connect timeout that is an ANR at five
 * seconds, which is exactly what pressing Connect produced.
 *
 * So the switch is here rather than at any of the three call sites above it. This class
 * is the one that knows it blocks; a rule kept by the thing that needs it protects
 * every caller, including the ones nobody has written yet, and cannot be forgotten by
 * one of them.
 */
class HttpProviderValidator(
    private val client: OkHttpClient,
    private val streams: HttpStreamSource,
    private val userAgent: String? = null,
    /**
     * Where the blocking work goes. Injected so a test can prove it moved.
     *
     * Defaulted, because a validator constructed without one must still be safe — an
     * unsafe default is how this defect would come back.
     */
    private val dispatchers: AppDispatchers = DefaultDispatchers,
) : ProviderValidator {

    override suspend fun validate(source: PlaylistSource): Outcome<ProviderStatus> =
        withContext(dispatchers.io) {
            when (source) {
                is PlaylistSource.Xtream -> validateXtream(source)
                is PlaylistSource.M3u -> validateUrl(source.url, source.userAgent ?: userAgent)
                // A local file is either readable or not, and the importer says which;
                // there is nothing to ask a server.
                is PlaylistSource.LocalFile -> Outcome.Success(ProviderStatus(usable = true))
                is PlaylistSource.Portal -> Outcome.Failure(AppError.NOT_FOUND)
            }
        }

    private fun validateXtream(source: PlaylistSource.Xtream): Outcome<ProviderStatus> {
        val api = XtreamHttpApi(client, source.host, source.username, source.password, userAgent)
        return when (val result = api.account()) {
            is Outcome.Failure -> result
            is Outcome.Success -> {
                val account = result.value
                Outcome.Success(
                    ProviderStatus(
                        // Authenticated but expired is still not usable, and saying so
                        // is the difference between a user renewing and a user
                        // reinstalling the app.
                        usable = account.isUsable && !account.isExpiredAt(System.currentTimeMillis()),
                        expiresAtMs = account.expiresAtMs,
                        isTrial = account.isTrial,
                        activeConnections = account.activeConnections,
                        maxConnections = account.maxConnections,
                        statusLabel = account.status,
                    ),
                )
            }
        }
    }

    /**
     * For a playlist URL there is no account API, so reachability and authorisation
     * are all that can be checked — with a range request, because the point is to
     * validate credentials without pulling 100 MB.
     */
    private fun validateUrl(url: String, agent: String?): Outcome<ProviderStatus> =
        when (val result = streams.hasChanged(RemoteRequest(url = url, userAgent = agent, noCache = true))) {
            is RemoteResult.Success, is RemoteResult.NotModified ->
                Outcome.Success(ProviderStatus(usable = true))
            is RemoteResult.Failure -> Outcome.Failure(result.error, result.cause)
        }
}
