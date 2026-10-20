package com.castivio.tv

import android.app.Application
import android.os.StrictMode
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.castivio.core.platform.DeviceCapabilities
import com.castivio.core.platform.MemoryClass
import com.castivio.tv.debug.CrashReport
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient

/**
 * Deliberately does no work.
 *
 * Cold start on a 2 GB stick is dominated by what Application touches, so
 * capability detection, database opening, playlist import and cache warming
 * are all lazy and off the main thread. Adding eager initialisation here is
 * the easiest way to make Castivio slow — don't.
 *
 * [newImageLoader] does not break that rule, and the interface is the reason: Coil
 * calls it the first time something asks for a picture, which on Castivio is a viewer
 * arriving at a list of channels — never during startup. Nothing here runs in
 * [onCreate].
 */
@HiltAndroidApp
class CastivioApp : Application(), ImageLoaderFactory {

    /**
     * The image loader, built once, the first time a picture is asked for.
     *
     * ## Why it is configured at all rather than left on its defaults
     *
     * Two of the defaults are wrong for this product.
     *
     * **The memory cache.** Coil takes a quarter of the app's heap. On a 1 GB stick
     * running a catalogue of 53,000 channels that is both too much to give away and
     * more than a list of twelve visible rows can use; the budget here follows
     * [DeviceCapabilities.memoryClass], like every other budget in the app.
     *
     * **The HTTP client.** Coil would build its own — a second connection pool, a
     * second DNS cache, and crucially a different `User-Agent`. Providers gate on that
     * header and some reject the default outright, which would show as logos that load
     * on one subscription and silently 403 on another. Sharing the app's client means
     * the logo request looks exactly like every other request Castivio makes, over a
     * connection that is usually already warm because the channel list came down it.
     *
     * The client is fetched through an entry point rather than injected into a field:
     * field injection happens in `super.onCreate()` and would drag the whole networking
     * graph, and with it device-capability detection, onto the startup path.
     */
    override fun newImageLoader(): ImageLoader {
        val graph = EntryPointAccessors.fromApplication(this, ImageGraph::class.java)
        val memoryClass = graph.capabilities().memoryClass
        return ImageLoader.Builder(this)
            .callFactory { graph.httpClient() }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(
                        when (memoryClass) {
                            MemoryClass.LOW -> LOW_HEAP_SHARE
                            MemoryClass.MEDIUM -> MEDIUM_HEAP_SHARE
                            MemoryClass.HIGH -> HIGH_HEAP_SHARE
                        },
                    )
                    .build()
            }
            .diskCache {
                // Its own directory, beside OkHttp's. They are two caches of two
                // different things -- one holds playlists and guide data, the other
                // holds pictures -- and a single budget for both would let a scroll
                // through 53,000 logos evict the catalogue that was just imported.
                DiskCache.Builder()
                    .directory(cacheDir.resolve(ARTWORK_CACHE_DIRECTORY))
                    .maxSizeBytes(
                        when (memoryClass) {
                            MemoryClass.LOW -> LOW_DISK_BYTES
                            MemoryClass.MEDIUM -> MEDIUM_DISK_BYTES
                            MemoryClass.HIGH -> HIGH_DISK_BYTES
                        },
                    )
                    .build()
            }
            .build()
    }

    /** What [newImageLoader] needs off the graph, fetched when it is needed and not before. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ImageGraph {
        fun httpClient(): OkHttpClient
        fun capabilities(): DeviceCapabilities
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            enableStrictMode()
            // Installed before anything else can throw. It writes one file and hands the
            // throwable straight on, so it changes nothing about how a crash behaves --
            // it only makes the trace reachable from the device it happened on. See
            // `CrashReport` for why that is worth a class.
            CrashReport.install(this)
        }
    }

    /**
     * Fails loudly on main-thread disk or network access during development,
     * so a stall is caught here rather than shipped and reported as "laggy".
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
    }

    private companion object {
        /**
         * Shares of the app heap the decoded-bitmap cache may hold.
         *
         * A channel logo decodes to a few tens of kilobytes at the size it is drawn, so
         * even the low figure holds far more than the dozen rows on screen and the
         * dozen either side of them — which is the whole job. The rest of the heap
         * belongs to the catalogue and to the decoder.
         */
        const val LOW_HEAP_SHARE = 0.10
        const val MEDIUM_HEAP_SHARE = 0.15
        const val HIGH_HEAP_SHARE = 0.20

        /** And what may be kept on the device between runs. */
        const val LOW_DISK_BYTES = 48L * 1024 * 1024
        const val MEDIUM_DISK_BYTES = 128L * 1024 * 1024
        const val HIGH_DISK_BYTES = 256L * 1024 * 1024

        const val ARTWORK_CACHE_DIRECTORY = "artwork"
    }
}
