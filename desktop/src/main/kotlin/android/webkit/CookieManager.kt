package android.webkit

import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The WebView cookie store, backed by the desktop OkHttp cookie jar. */
class CookieManager private constructor() {
    fun getCookie(url: String?): String? {
        val httpUrl = url?.toHttpUrlOrNull() ?: return null
        return NetworkClient.cookieJar.loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null }
    }

    fun setCookie(url: String?, value: String?) {
        val httpUrl = url?.toHttpUrlOrNull() ?: return
        val cookie = value?.let { Cookie.parse(httpUrl, it) } ?: return
        NetworkClient.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
    }

    fun flush() = Unit

    companion object {
        private val instance = CookieManager()
        @JvmStatic fun getInstance(): CookieManager = instance
    }
}
