package com.streamflixreborn.streamflix.sync.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Phone side of TV pairing: encrypts the account credentials and posts them to the TV. */
object PairingClient {
    private const val TAG = "PairingClient"
    private const val DISCOVERY_TIMEOUT_MS = 10_000L

    sealed class Result {
        data class Success(val tvName: String) : Result()
        data class Failure(val reason: Reason, val message: String? = null) : Result()
    }

    enum class Reason {
        /** No TV advertising this code was found on the network. */
        NOT_FOUND,
        /** The TV could not be reached (different network, firewall, expired). */
        UNREACHABLE,
        /** The TV could not decrypt the request: wrong or expired code/QR. */
        CODE_MISMATCH,
        /** The TV decrypted the request but sign-in failed (message from Supabase). */
        REJECTED,
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Pairs using a scanned QR code. Wipes [credentials] when done. */
    suspend fun pair(uri: PairingProtocol.PairingUri, credentials: PairingProtocol.Credentials): Result {
        val nonce = PairingCrypto.randomBytes(PairingCrypto.NONCE_BYTES)
        val key = PairingCrypto.keyFromToken(uri.token)
        return post(uri.host, uri.port, PairingProtocol.MODE_TOKEN, key, nonce, credentials)
    }

    /** Pairs using the 6-digit code shown on the TV; finds the TV via NSD. Wipes [credentials]. */
    suspend fun pairWithCode(context: Context, code: String, credentials: PairingProtocol.Credentials): Result {
        val endpoint = discover(context.applicationContext, PairingCrypto.codeHash(code))
        if (endpoint == null) {
            credentials.wipe()
            return Result.Failure(Reason.NOT_FOUND)
        }
        val nonce = PairingCrypto.randomBytes(PairingCrypto.NONCE_BYTES)
        val key = withContext(Dispatchers.Default) { PairingCrypto.keyFromCode(code, nonce) }
        return post(endpoint.first, endpoint.second, PairingProtocol.MODE_CODE, key, nonce, credentials)
    }

    private suspend fun post(
        host: String,
        port: Int,
        mode: String,
        key: ByteArray,
        nonce: ByteArray,
        credentials: PairingProtocol.Credentials,
    ): Result = withContext(Dispatchers.IO) {
        val plaintext = credentials.toJsonBytes()
        credentials.wipe()
        val sealed = try {
            PairingCrypto.seal(key, plaintext, nonce)
        } finally {
            plaintext.fill(0)
            key.fill(0)
        }
        val body = PairingProtocol.json.encodeToString(
            PairingProtocol.Request.serializer(),
            PairingProtocol.Request(
                mode = mode,
                nonce = PairingCrypto.base64Url(sealed.nonce),
                ciphertext = PairingCrypto.base64Url(sealed.ciphertext),
            ),
        )
        val request = Request.Builder()
            .url("http://$host:$port${PairingProtocol.PATH}")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 403) return@withContext Result.Failure(Reason.CODE_MISMATCH)
                val text = response.body?.string().orEmpty()
                val parsed = runCatching {
                    PairingProtocol.json.decodeFromString(PairingProtocol.Response.serializer(), text)
                }.getOrNull() ?: return@withContext Result.Failure(Reason.UNREACHABLE, "HTTP ${response.code}")
                if (parsed.ok) {
                    Result.Success(parsed.deviceName.orEmpty())
                } else {
                    Result.Failure(Reason.REJECTED, parsed.error)
                }
            }
        } catch (error: SocketTimeoutException) {
            Result.Failure(Reason.UNREACHABLE, error.message)
        } catch (error: IOException) {
            Log.w(TAG, "Pairing request failed", error)
            Result.Failure(Reason.UNREACHABLE, error.message)
        }
    }

    /** Finds a TV advertising [codeHash]; returns its IPv4 host and port. */
    private suspend fun discover(context: Context, codeHash: String): Pair<String, Int>? =
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                if (nsd == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val lock = Any()
                val queue = ArrayDeque<NsdServiceInfo>()
                var resolving = false
                var finished = false
                lateinit var discovery: NsdManager.DiscoveryListener

                fun finish(result: Pair<String, Int>?) {
                    synchronized(lock) {
                        if (finished) return
                        finished = true
                    }
                    runCatching { nsd.stopServiceDiscovery(discovery) }
                    if (cont.isActive) cont.resume(result)
                }

                fun resolveNext() {
                    val next = synchronized(lock) {
                        if (resolving || finished) return
                        queue.removeFirstOrNull()?.also { resolving = true } ?: return
                    }
                    @Suppress("DEPRECATION")
                    nsd.resolveService(next, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            synchronized(lock) { resolving = false }
                            resolveNext()
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            synchronized(lock) { resolving = false }
                            val hash = serviceInfo.attributes[PairingProtocol.ATTR_CODE_HASH]
                                ?.toString(Charsets.UTF_8)
                            @Suppress("DEPRECATION")
                            val host = serviceInfo.host?.hostAddress
                            if (hash == codeHash && host != null && !host.contains(':')) {
                                finish(host to serviceInfo.port)
                            } else {
                                resolveNext()
                            }
                        }
                    })
                }

                discovery = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        synchronized(lock) { queue.addLast(serviceInfo) }
                        resolveNext()
                    }
                }
                cont.invokeOnCancellation {
                    synchronized(lock) { finished = true }
                    runCatching { nsd.stopServiceDiscovery(discovery) }
                }
                runCatching {
                    nsd.discoverServices(PairingProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
                }.onFailure { finish(null) }
            }
        }
}
