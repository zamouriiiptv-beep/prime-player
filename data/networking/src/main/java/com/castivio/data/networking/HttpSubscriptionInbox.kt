package com.castivio.data.networking

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.core.common.config.PortalConfig
import com.castivio.data.parsing.PortalInboxCodec
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.portal.SentSubscription
import com.castivio.domain.portal.SubscriptionInbox
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.io.Reader

/** What the device reports about itself when it announces. Either may be absent: nothing is guessed. */
internal data class PortalDevice(val modelName: String? = null, val platform: String? = null)

/**
 * The portal's device mailbox, over HTTPS.
 *
 * Every call is a POST with the device's address and key **in the body**. A GET would put
 * the key in the address of the request, and addresses are what servers, proxies and
 * logs keep; a body is not. `HttpSubscriptionInboxTest` asserts the key never appears in
 * a path.
 *
 * What a refusal means is settled here once, in the vocabulary the rest of the application
 * already speaks ([AppError]), so nothing above this class knows a status code:
 *
 *  - **403** — the portal knows this address under another key. [AppError.UNAUTHORIZED].
 *    Repeating it cannot help, and the portal counts wrong keys.
 *  - **429** — too many wrong keys were tried for this address. [AppError.SERVER_ERROR]:
 *    worth another press later, and not something a user can act on now.
 *  - **5xx**, or a 200 that is not the portal's JSON (a gateway's error page) —
 *    [AppError.SERVER_ERROR].
 *  - A connection that was not made, or was cut — [AppError.NETWORK_UNAVAILABLE]; one that
 *    did not answer in time — [AppError.TIMEOUT].
 *
 * Blocking, and moved off the caller's thread by this class: the caller is a view model.
 */
internal class HttpSubscriptionInbox(
    private val http: OkHttpClient,
    private val config: PortalConfig,
    private val identity: DeviceIdentity,
    private val dispatchers: AppDispatchers,
    private val device: PortalDevice = PortalDevice(),
) : SubscriptionInbox {

    override suspend fun announce(): Outcome<Unit> = withContext(dispatchers.io) {
        val (mac, key) = credentials()
        post("device/register", PortalInboxCodec.registerBody(mac, key, device.modelName, device.platform)) { Unit }
    }

    override suspend fun waiting(): Outcome<List<SentSubscription>> = withContext(dispatchers.io) {
        val (mac, key) = credentials()
        post("device/inbox", PortalInboxCodec.inboxBody(mac, key), PortalInboxCodec::parseWaiting)
    }

    override suspend fun acknowledge(taken: List<SentSubscription>): Outcome<Unit> = withContext(dispatchers.io) {
        val (mac, key) = credentials()
        post("device/inbox/ack", PortalInboxCodec.acknowledgeBody(mac, key, taken)) { Unit }
    }

    /** The address and the key, read where they are derived. Both are cached after the first read. */
    private fun credentials(): Pair<String, String> =
        identity.current().macAddress.value to identity.key().value

    private fun <T> post(path: String, body: String, read: (Reader) -> T): Outcome<T> = try {
        val request = Request.Builder()
            .url(config.api(path))
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response -> answer(response, read) }
    } catch (timeout: InterruptedIOException) {
        // Includes the socket timeouts, which subclass it.
        Outcome.Failure(AppError.TIMEOUT, timeout)
    } catch (unreachable: IOException) {
        Outcome.Failure(AppError.NETWORK_UNAVAILABLE, unreachable)
    }

    private fun <T> answer(response: Response, read: (Reader) -> T): Outcome<T> {
        val code = response.code
        return when {
            response.isSuccessful -> {
                val stream = response.body?.charStream() ?: return Outcome.Failure(AppError.SERVER_ERROR)
                try {
                    Outcome.Success(read(stream))
                } catch (unreadable: RuntimeException) {
                    // Not the portal's JSON: a gateway's page, or an answer from a version this build does not know.
                    Outcome.Failure(AppError.SERVER_ERROR, unreadable)
                }
            }

            code == 403 -> Outcome.Failure(AppError.UNAUTHORIZED)
            code == 429 || code in 500..599 -> Outcome.Failure(AppError.SERVER_ERROR)
            else -> Outcome.Failure(AppError.UNKNOWN)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
