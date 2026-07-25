package com.castivio.data.networking

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderStatus
import com.castivio.domain.ProviderValidator
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
 * Blocking underneath; the caller is a ViewModel on an IO dispatcher.
 */
class HttpProviderValidator(
    private val client: OkHttpClient,
    private val streams: HttpStreamSource,
    private val userAgent: String? = null,
) : ProviderValidator {

    override suspend fun validate(source: PlaylistSource): Outcome<ProviderStatus> = when (source) {
        is PlaylistSource.Xtream -> validateXtream(source)
        is PlaylistSource.M3u -> validateUrl(source.url, source.userAgent ?: userAgent)
        // A local file is either readable or not, and the importer says which; there
        // is nothing to ask a server.
        is PlaylistSource.LocalFile -> Outcome.Success(ProviderStatus(usable = true))
        is PlaylistSource.Portal -> Outcome.Failure(AppError.NOT_FOUND)
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
