package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.StreamFlixApp
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Desktop version of the app's OkHttp clients: the same interceptors, cache rules and DoH, with
 * an in-memory cookie jar standing in for Android's WebView CookieManager.
 */
object NetworkClient {

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val ACCEPT_LANGUAGE = "en-US,en;q=0.9,hu;q=0.8"

    private const val HTTP_CACHE_SIZE = 20L * 1024 * 1024
    private const val MAX_CACHEABLE_BYTES = 1L * 1024 * 1024
    private val NON_CACHEABLE_EXTENSIONS = listOf(
        ".ts", ".m4s", ".mp4", ".m4v", ".mkv", ".webm", ".m3u8", ".mpd", ".aac", ".mp3", ".vtt", ".srt",
    )

    private val jar = ConcurrentHashMap<String, MutableList<Cookie>>()

    val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val list = jar.getOrPut(url.host) { mutableListOf() }
            synchronized(list) {
                cookies.forEach { c -> list.removeAll { it.name == c.name && it.path == c.path } }
                list += cookies
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return jar.values.flatMap { list -> synchronized(list) { list.toList() } }
                .filter { it.expiresAt > now && it.matches(url) }
        }
    }

    private val connectionPool = ConnectionPool(16, 5, TimeUnit.MINUTES)
    private val dispatcher = Dispatcher().apply { maxRequestsPerHost = 12 }

    private val defaultHeaders = Interceptor { chain ->
        val original = chain.request()
        val requestBuilder = original.newBuilder()
        val ua = original.header("User-Agent")
        if (ua == null || ua.startsWith("okhttp/")) requestBuilder.header("User-Agent", USER_AGENT)
        if (original.header("Accept") == null)
            requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        if (original.header("Accept-Language") == null) requestBuilder.header("Accept-Language", ACCEPT_LANGUAGE)
        val hasFetchMetadata = original.headers.names().any { it.startsWith("sec-fetch-", ignoreCase = true) }
        if (!hasFetchMetadata) {
            requestBuilder.header("Sec-Fetch-Dest", "document")
            requestBuilder.header("Sec-Fetch-Mode", "navigate")
            requestBuilder.header("Sec-Fetch-Site", "none")
            if (original.header("Upgrade-Insecure-Requests") == null) requestBuilder.header("Upgrade-Insecure-Requests", "1")
        }
        chain.proceed(requestBuilder.build())
    }

    private val cacheGuard = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (isStorable(response)) response
        else response.newBuilder().header("Cache-Control", "no-store").removeHeader("Pragma").build()
    }

    private fun isStorable(response: Response): Boolean {
        if (response.request.method != "GET" || !response.isSuccessful) return false
        val path = response.request.url.encodedPath.lowercase()
        if (NON_CACHEABLE_EXTENSIONS.any { path.endsWith(it) }) return false
        val type = response.header("Content-Type")?.lowercase() ?: return false
        if (!type.contains("json")) return false
        val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
        return length <= MAX_CACHEABLE_BYTES
    }

    val bare: OkHttpClient by lazy {
        OkHttpClient.Builder().connectionPool(connectionPool).dispatcher(dispatcher).build()
    }

    val default: OkHttpClient by lazy { buildClient(DnsResolver.doh) }

    val noCookies: OkHttpClient by lazy { default.newBuilder().cookieJar(CookieJar.NO_COOKIES).build() }

    val systemDns: OkHttpClient by lazy { default.newBuilder().dns(Dns.SYSTEM).build() }

    val noRedirects: OkHttpClient by lazy { default.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    val trustAll: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
        default.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun buildClient(dns: Dns): OkHttpClient {
        val builder = bare.newBuilder()
            .addNetworkInterceptor(defaultHeaders)
            .addNetworkInterceptor(cacheGuard)
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .dns(dns)
        runCatching { Cache(File(StreamFlixApp.instance.cacheDir, "http"), HTTP_CACHE_SIZE) }.onSuccess { builder.cache(it) }
        val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()
        builder.connectionSpecs(listOf(spec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        return builder.build()
    }
}
