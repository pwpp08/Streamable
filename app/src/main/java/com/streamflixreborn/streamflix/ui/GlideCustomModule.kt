package com.streamflixreborn.streamflix.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.PictureDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.caverock.androidsvg.SVG
import com.streamflixreborn.streamflix.utils.ArtworkRequestHeaders
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.OkHttpClient
import java.io.InputStream

@GlideModule
class GlideCustomModule : AppGlideModule() {

    /**
     * Derived from [NetworkClient.trustAll] (artwork hosts often have broken certificates), so it
     * shares the app's connection pool, dispatcher, DoH and cookie jar. No OkHttp cache: Glide keeps
     * its own disk cache, and images must not evict API JSON from the shared HTTP cache.
     */
    private fun getOkHttpClient(): OkHttpClient {
        return NetworkClient.trustAll.newBuilder()
            .cache(null)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                if (original.header("Accept") == null) {
                    requestBuilder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                }
                // Image fetch metadata, so NetworkClient doesn't add document-navigation headers.
                if (original.header("Sec-Fetch-Dest") == null) {
                    requestBuilder.header("Sec-Fetch-Dest", "image")
                    requestBuilder.header("Sec-Fetch-Mode", "no-cors")
                    requestBuilder.header("Sec-Fetch-Site", "cross-site")
                }
                chain.proceed(requestBuilder.build())
            }
            .addInterceptor { chain ->
                val request = chain.request()
                val headers = ArtworkRequestHeaders.headersFor(request.url)
                val strippedUrl = ArtworkRequestHeaders.stripHeaders(request.url)
                val fixedRequest = if (headers.isNotEmpty() || strippedUrl != request.url) {
                    request.newBuilder()
                        .url(strippedUrl)
                        .apply {
                            headers.forEach { (name, value) -> header(name, value) }
                        }
                        .build()
                } else {
                    request
                }
                chain.proceed(fixedRequest)
            }
            .build()
    }

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        if (isLowMemoryDevice(context)) {
            // Posters/backdrops have no alpha; RGB_565 halves bitmap memory. Glide still uses
            // ARGB_8888 for images that do have transparency.
            builder.setDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
            builder.setMemorySizeCalculator(
                MemorySizeCalculator.Builder(context)
                    .setMemoryCacheScreens(1f)
                    .setBitmapPoolScreens(1f)
            )
        }
    }

    private fun isLowMemoryDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isLowRamDevice || am.memoryClass <= 192
    }

    override fun registerComponents(
        context: Context, glide: Glide, registry: com.bumptech.glide.Registry
    ) {
        val okHttpClient = getOkHttpClient()
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader.Factory(okHttpClient),
        )
        registry.append(
            InputStream::class.java,
            SVG::class.java,
            SvgDecoder(),
        )
        registry.register(
            SVG::class.java,
            PictureDrawable::class.java,
            SvgDrawableTranscoder(),
        )
    }

}
