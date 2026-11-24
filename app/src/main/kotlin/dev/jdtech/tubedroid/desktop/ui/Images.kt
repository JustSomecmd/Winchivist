package dev.jdtech.tubedroid.desktop.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.tubearchivist.TaApi
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Sets up image loading: pictures from TubeArchivist and from the JRWI archiver get that server's
 * token, as each asks for one, and are kept on disk so pages open quickly the next time. A token
 * only ever goes to its own server. The pictures on disk may take up to [cacheMb] megabytes.
 */
fun installImageLoader(api: TaApi, jrwi: JrwiApi, cacheMb: Int = DEFAULT_IMAGE_CACHE_MB) {
    val client =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val base = api.baseUrl?.toHttpUrlOrNull()
                // The port counts too, as the servers are often on the same computer
                val tubeArchivist =
                    base != null && request.url.host == base.host && request.url.port == base.port
                val header =
                    when {
                        tubeArchivist -> api.authHeader
                        jrwi.isArchiverUrl(request.url) -> jrwi.authHeader
                        else -> null
                    }
                if (header != null) {
                    chain.proceed(request.newBuilder().header("Authorization", header).build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    val cacheFolder = imageCacheFolder()
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheFolder.toOkioPath())
                    .maxSizeBytes(cacheMb.coerceIn(64, 8192) * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}

const val DEFAULT_IMAGE_CACHE_MB = 512

private fun imageCacheFolder(): File =
    File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "Winchivist/images")

/** How much room the pictures kept on disk take now, in bytes. */
fun imageCacheSize(): Long =
    SingletonImageLoader.get(PlatformContext.INSTANCE).diskCache?.size ?: 0L

/** Throws away the pictures kept on disk and in memory; they're fetched again when next shown. */
fun clearImageCache() {
    val loader = SingletonImageLoader.get(PlatformContext.INSTANCE)
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
}

/** Lets go of the pictures held in memory, for when no window shows them. */
fun releaseImageMemory() {
    SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
    System.gc()
}
