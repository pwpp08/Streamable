package com.streamflixreborn.streamflix.desktop.web

import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Follows a link the way the Android WebView did for StreamWish/Upzone: HTTP redirects, then a
 * `location.href` / `location.replace` / meta refresh in the page, a few hops deep.
 */
object Redirects {
    private val jsLocation = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']|location\.replace\(\s*["']([^"']+)["']""")
    private val metaRefresh = Regex("""<meta[^>]+http-equiv=["']?refresh["']?[^>]+url=([^"'>\s]+)""", RegexOption.IGNORE_CASE)

    suspend fun resolve(url: String): String = withContext(Dispatchers.IO) {
        var current = url
        repeat(4) {
            val next = runCatching {
                NetworkClient.default.newCall(Request.Builder().url(current).build()).execute().use { response ->
                    val landed = response.request.url
                    val body = response.body?.source()?.let { src -> src.request(256 * 1024); src.buffer.clone().readUtf8() }.orEmpty()
                    val hop = jsLocation.find(body)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
                        ?: metaRefresh.find(body)?.groupValues?.get(1)
                    hop?.let { landed.resolve(it)?.toString() } ?: landed.toString()
                }
            }.getOrNull() ?: return@withContext current
            if (next == current) return@withContext current
            current = next
        }
        current
    }
}
