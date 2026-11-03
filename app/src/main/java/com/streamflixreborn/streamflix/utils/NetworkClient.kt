package com.streamflixreborn.streamflix.utils

import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
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
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Single source of OkHttp clients. Site-specific clients must be derived with
 * `NetworkClient.default.newBuilder()` (or [noCookies] / [systemDns] / [trustAll]) so they
 * share one connection pool, one dispatcher thread pool, the HTTP cache and DoH.
 */
object NetworkClient {

    private const val TAG = "NetworkClient"

    // User-Agent Mobile standard per massima compatibilità con Cloudflare
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    const val ACCEPT_LANGUAGE = "en-US,en;q=0.9,hu;q=0.8"

    private const val HTTP_CACHE_SIZE = 10L * 1024 * 1024
    private const val MAX_CACHEABLE_BYTES = 1L * 1024 * 1024
    private val NON_CACHEABLE_EXTENSIONS = listOf(
        ".ts", ".m4s", ".mp4", ".m4v", ".mkv", ".webm", ".m3u8", ".mpd", ".aac", ".mp3", ".vtt", ".srt",
    )

    private val cookieManager by lazy { CookieManager.getInstance() }

    val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            cookies.forEach { cookie ->
                cookieManager.setCookie(url.toString(), cookie.toString())
            }
            cookieManager.flush()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookieString = cookieManager.getCookie(url.toString()) ?: return emptyList()
            return cookieString.split(";").mapNotNull {
                Cookie.parse(url, it.trim())
            }
        }
    }

    private val loggingInterceptor by lazy {
        HttpLoggingInterceptor { message ->
            Log.d(TAG, "[OkHttp] $message")
        }.apply {
            // BASIC only: HEADERS leaks tokens/cookies into logcat and is slow.
            level = HttpLoggingInterceptor.Level.BASIC
        }
    }

    // Shared by every derived client (newBuilder() keeps them).
    private val connectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)
    private val dispatcher = Dispatcher().apply { maxRequestsPerHost = 8 }

    /**
     * Fills browser-like defaults for headers the caller did not set. It is a network
     * interceptor so it runs after every application interceptor a derived client adds:
     * whatever a site sets always wins.
     */
    private val defaultHeaders = Interceptor { chain ->
        val original = chain.request()
        val requestBuilder = original.newBuilder()
        // BridgeInterceptor has already inserted "okhttp/x.y.z" when nobody set a UA.
        val ua = original.header("User-Agent")
        if (ua == null || ua.startsWith("okhttp/"))
            requestBuilder.header("User-Agent", USER_AGENT)
        if (original.header("Accept") == null)
            requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        if (original.header("Accept-Language") == null)
            requestBuilder.header("Accept-Language", ACCEPT_LANGUAGE)
        // A caller that set any Sec-Fetch-* header (cors, image...) gets no navigation headers mixed in.
        val hasFetchMetadata = original.headers.names().any { it.startsWith("sec-fetch-", ignoreCase = true) }
        if (!hasFetchMetadata) {
            requestBuilder.header("Sec-Fetch-Dest", "document")
            requestBuilder.header("Sec-Fetch-Mode", "navigate")
            requestBuilder.header("Sec-Fetch-Site", "none")
            if (original.header("Upgrade-Insecure-Requests") == null)
                requestBuilder.header("Upgrade-Insecure-Requests", "1")
        }
        chain.proceed(requestBuilder.build())
    }

    /**
     * Only small JSON API responses (TMDB etc.) may be stored in the HTTP cache. Everything else
     * (HTML carrying one-time tokens, playlists, video segments, large bodies) is rewritten to
     * no-store; the CacheInterceptor sees the rewritten response because it runs after this.
     */
    private val cacheGuard = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (isStorable(response)) {
            response
        } else {
            response.newBuilder()
                .header("Cache-Control", "no-store")
                .removeHeader("Pragma")
                .build()
        }
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

    /** Root: shared pool + dispatcher only. No headers, cookies or cache (used by the DoH client). */
    internal val bare: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .build()
    }

    val default: OkHttpClient by lazy { buildClient(DnsResolver.doh) }

    /** [default] without the WebView cookie jar, for scrapers/APIs that never sent cookies. */
    val noCookies: OkHttpClient by lazy { default.newBuilder().cookieJar(CookieJar.NO_COOKIES).build() }

    val systemDns: OkHttpClient by lazy { default.newBuilder().dns(Dns.SYSTEM).build() }

    val noRedirects: OkHttpClient by lazy {
        default.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

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

        runCatching { Cache(File(StreamFlixApp.instance.cacheDir, "http"), HTTP_CACHE_SIZE) }
            .onSuccess { builder.cache(it) }
            .onFailure { Log.w(TAG, "HTTP cache disabled: ${it.message}") }

        // Modern and compatible TLS configuration
        val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
            .build()
        builder.connectionSpecs(listOf(spec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))

        // SSL compatibility for Android < 9.0 (API 28) and ISRG Root X1 for Let's Encrypt
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            try {
                // On older Android we manually inject the Let's Encrypt ISRG Root X1 certificate
                // and enable older TLS versions just in case.

                val cf = CertificateFactory.getInstance("X.509")
                val certInput = StreamFlixApp.instance.resources.openRawResource(R.raw.isrg_root_x1)
                val isrgCert = certInput.use { cf.generateCertificate(it) }

                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("isrg_root_x1", isrgCert)
                }

                // Initialize TMF with our certificate
                val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
                val tmf = TrustManagerFactory.getInstance(tmfAlgorithm).apply {
                    init(keyStore)
                }

                // Get system TMF for regular certificates
                val systemTmf = TrustManagerFactory.getInstance(tmfAlgorithm).apply {
                    init(null as KeyStore?)
                }

                val systemTrustManager = systemTmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
                val customTrustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

                // Custom trust manager that trusts both system and our bundled certificate
                val combinedTrustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                        systemTrustManager.checkClientTrusted(chain, authType)
                    }

                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                        try {
                            systemTrustManager.checkServerTrusted(chain, authType)
                        } catch (e: Exception) {
                            try {
                                customTrustManager.checkServerTrusted(chain, authType)
                            } catch (e2: Exception) {
                                // Fallback to system check as a last resort, throwing if it fails
                                systemTrustManager.checkServerTrusted(chain, authType)
                            }
                        }
                    }

                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                        return systemTrustManager.acceptedIssuers + customTrustManager.acceptedIssuers
                    }
                }

                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(combinedTrustManager), SecureRandom())
                }

                builder.sslSocketFactory(sslContext.socketFactory, combinedTrustManager)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up SSL compatibility: ${e.message}")
            }
        }

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(loggingInterceptor)
        }
        return builder.build()
    }
}
