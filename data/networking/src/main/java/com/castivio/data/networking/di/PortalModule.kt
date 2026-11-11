package com.castivio.data.networking.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.config.PortalConfig
import com.castivio.data.networking.HttpSubscriptionInbox
import com.castivio.data.networking.PortalDevice
import com.castivio.domain.activation.ActivateProvider
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.portal.ReceiveSentSubscriptions
import com.castivio.domain.portal.SentSubscriptionReceiver
import com.castivio.domain.portal.SubscriptionInbox
import com.castivio.domain.time.TrustedTime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * What the owner sent from the website, and what the Refresh button does with it.
 *
 * On the application's client and not on its own, for the reasons [RadioBrowserModule]
 * gives: the connection pool is shared, and the call counter of a catalogue's imports —
 * which this is not part of — is left alone by dropping the listener from the copy, as are
 * the long timeouts a subscription's panel needs. A mailbox answers in a moment or not at
 * all, and a press of a button is not made to wait a minute for it.
 *
 * Where the portal is comes from `PortalConfig`, which `:app` provides from its build.
 */
@Module
@InstallIn(SingletonComponent::class)
object PortalModule {

    @Provides
    @Singleton
    fun subscriptionInbox(
        client: OkHttpClient,
        config: PortalConfig,
        identity: DeviceIdentity,
        dispatchers: AppDispatchers,
        @ApplicationContext context: Context,
    ): SubscriptionInbox {
        val http = client.newBuilder()
            .eventListener(EventListener.NONE)
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
            .build()
        return HttpSubscriptionInbox(http, config, identity, dispatchers, reportedDevice(context))
    }

    /**
     * Lives here, and not in the activation feature, because it needs the activation's
     * contract and the sequence that adds a subscription: both are already on the graph, and
     * this is the module that holds the contract's implementation.
     */
    @Provides
    @Singleton
    fun receiveSentSubscriptions(
        inbox: SubscriptionInbox,
        activate: ActivateProvider,
        clock: TrustedTime,
    ): SentSubscriptionReceiver = ReceiveSentSubscriptions(inbox, activate, clock)

    /**
     * What the website shows beside the address, so the owner can tell their devices apart.
     *
     * The maker's name is not repeated when the model already begins with it, which
     * `Build.MODEL` often does: "NVIDIA NVIDIA SHIELD" is not a name.
     */
    private fun reportedDevice(context: Context): PortalDevice {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val name = when {
            model.isEmpty() -> maker
            maker.isEmpty() || model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model"
        }
        val television = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return PortalDevice(
            modelName = name.ifEmpty { null },
            platform = if (television) "Android TV" else "Android",
        )
    }

    private const val CONNECT_SECONDS = 6L
    private const val READ_SECONDS = 10L
    private const val CALL_SECONDS = 15L
}
