package com.streamflixreborn.streamflix.sync.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One-shot pairing endpoint on the TV.
 *
 * Listens on a random port of the Wi-Fi/Ethernet IPv4 address, advertises
 * itself over NSD as [PairingProtocol.SERVICE_TYPE], and accepts a single
 * successful `POST /pair`. It closes after one success, [EXPIRY_MS], or
 * [MAX_FAILURES] rejected attempts.
 *
 * @param authenticate signs in with the received credentials and returns the
 *   account e-mail; throws with a user-facing message on failure. Runs while
 *   the phone waits for the HTTP response, so it must be quick.
 * @param onPaired called after the phone got its success response.
 */
class PairingServer(
    context: Context,
    private val deviceName: String,
    private val authenticate: suspend (PairingProtocol.Credentials) -> String,
    private val onPaired: (email: String) -> Unit,
) {
    sealed class State {
        data object Starting : State()
        data class Waiting(val info: Info) : State()
        data class Pairing(val info: Info) : State()
        data class Paired(val email: String) : State()
        data class Failed(val reason: Reason, val detail: String? = null) : State()
        data object Expired : State()
    }

    enum class Reason { NO_NETWORK, SOCKET, TOO_MANY_ATTEMPTS }

    data class Info(val host: String, val port: Int, val code: String, val uri: String)

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var acceptJob: Job? = null
    private var expiryJob: Job? = null
    private var failures = 0
    private var token: ByteArray = ByteArray(0)
    private var code: String = ""

    fun start(scope: CoroutineScope) {
        stop()
        _state.value = State.Starting
        failures = 0
        val address = findLocalIpv4(appContext) ?: run {
            _state.value = State.Failed(Reason.NO_NETWORK)
            return
        }
        val socket = try {
            ServerSocket(0, 4, address)
        } catch (error: Exception) {
            _state.value = State.Failed(Reason.SOCKET, error.message)
            return
        }
        serverSocket = socket
        token = PairingCrypto.newToken()
        code = PairingCrypto.newCode()
        val host = address.hostAddress ?: return
        val info = Info(
            host = host,
            port = socket.localPort,
            code = code,
            uri = PairingProtocol.PairingUri(host, socket.localPort, token).format(),
        )
        _state.value = State.Waiting(info)
        registerNsd(socket.localPort)

        expiryJob = scope.launch {
            delay(EXPIRY_MS)
            if (_state.value is State.Waiting || _state.value is State.Pairing) {
                _state.value = State.Expired
                close()
            }
        }
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                client.use { handle(it, info) }
                if (_state.value is State.Paired || _state.value is State.Failed) break
            }
            close()
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        expiryJob?.cancel()
        expiryJob = null
        close()
    }

    private fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        unregisterNsd()
        token.fill(0)
    }

    private suspend fun handle(socket: Socket, info: Info) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val response: Pair<Int, PairingProtocol.Response> = try {
            val request = readRequest(socket.getInputStream())
            when {
                request == null -> 400 to PairingProtocol.Response(ok = false, error = "Bad request")
                request.method != "POST" || request.path != PairingProtocol.PATH ->
                    404 to PairingProtocol.Response(ok = false, error = "Not found")
                else -> processBody(request.body, info)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Pairing request failed", error)
            400 to PairingProtocol.Response(ok = false, error = "Bad request")
        }
        runCatching { writeResponse(socket, response.first, response.second) }
        if (response.first == 200 && response.second.ok) {
            (state.value as? State.Paired)?.let { onPaired(it.email) }
        }
    }

    private suspend fun processBody(body: ByteArray, info: Info): Pair<Int, PairingProtocol.Response> {
        val request = PairingProtocol.json.decodeFromString(
            PairingProtocol.Request.serializer(),
            String(body, Charsets.UTF_8),
        )
        val nonce = PairingCrypto.fromBase64Url(request.nonce)
        val ciphertext = PairingCrypto.fromBase64Url(request.ciphertext)
        val plaintext = decrypt(request.mode, nonce, ciphertext) ?: run {
            failures++
            if (failures >= MAX_FAILURES) {
                _state.value = State.Failed(Reason.TOO_MANY_ATTEMPTS)
            }
            return 403 to PairingProtocol.Response(ok = false, error = "Pairing code did not match")
        }
        val credentials = try {
            PairingProtocol.Credentials.fromJsonBytes(plaintext)
        } finally {
            plaintext.fill(0)
        }
        _state.value = State.Pairing(info)
        return try {
            val email = withTimeout(AUTH_TIMEOUT_MS) { authenticate(credentials) }
            _state.value = State.Paired(email)
            200 to PairingProtocol.Response(ok = true, deviceName = deviceName)
        } catch (error: Exception) {
            failures++
            _state.value = if (failures >= MAX_FAILURES) State.Failed(Reason.TOO_MANY_ATTEMPTS) else State.Waiting(info)
            200 to PairingProtocol.Response(ok = false, error = error.message ?: "Sign in failed")
        } finally {
            credentials.wipe()
        }
    }

    private fun decrypt(mode: String?, nonce: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (nonce.size != PairingCrypto.NONCE_BYTES) return null
        val candidates = when (mode) {
            PairingProtocol.MODE_TOKEN -> listOf(PairingProtocol.MODE_TOKEN)
            PairingProtocol.MODE_CODE -> listOf(PairingProtocol.MODE_CODE)
            else -> listOf(PairingProtocol.MODE_TOKEN, PairingProtocol.MODE_CODE)
        }
        for (candidate in candidates) {
            val key = if (candidate == PairingProtocol.MODE_TOKEN) {
                PairingCrypto.keyFromToken(token)
            } else {
                PairingCrypto.keyFromCode(code, nonce)
            }
            try {
                return PairingCrypto.open(key, nonce, ciphertext)
            } catch (_: AEADBadTagException) {
                // try the next key
            } finally {
                key.fill(0)
            }
        }
        return null
    }

    private class HttpRequest(val method: String, val path: String, val body: ByteArray)

    /** Minimal HTTP/1.1 request parser: headers up to 8 KB, body up to 64 KB via Content-Length. */
    private fun readRequest(input: InputStream): HttpRequest? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            if (head.size() > MAX_HEADER_BYTES) return null
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 3) return null
        val contentLength = lines.drop(1)
            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: return null
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return HttpRequest(requestLine[0], requestLine[1].substringBefore('?'), body)
    }

    private fun writeResponse(socket: Socket, status: Int, response: PairingProtocol.Response) {
        val body = PairingProtocol.json.encodeToString(PairingProtocol.Response.serializer(), response)
            .toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Bad Request"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    private fun registerNsd(port: Int) {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "StreamFlix $deviceName".take(60)
            serviceType = PairingProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute(PairingProtocol.ATTR_CODE_HASH, PairingCrypto.codeHash(code))
            setAttribute(PairingProtocol.ATTR_VERSION, PairingProtocol.VERSION.toString())
            setAttribute(PairingProtocol.ATTR_NAME, deviceName.take(40))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { nsdListener = listener }
            .onFailure { Log.w(TAG, "NSD registration failed", it) }
    }

    private fun unregisterNsd() {
        val listener = nsdListener ?: return
        nsdListener = null
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        runCatching { nsd.unregisterService(listener) }
    }

    companion object {
        private const val TAG = "PairingServer"
        const val EXPIRY_MS = 5 * 60_000L
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val AUTH_TIMEOUT_MS = 8_500L
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_FAILURES = 10

        /** Site-local IPv4 of the active network, falling back to any up interface (wlan first). */
        fun findLocalIpv4(context: Context): Inet4Address? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.activeNetwork?.let(cm::getLinkProperties)?.linkAddresses
                        ?.map { it.address }
                        ?.filterIsInstance<Inet4Address>()
                        ?.firstOrNull { it.isSiteLocalAddress }
                }.getOrNull()?.let { return it }
            }
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            return interfaces
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .sortedBy {
                    when {
                        it.name.startsWith("wlan") -> 0
                        it.name.startsWith("eth") -> 1
                        else -> 2
                    }
                }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        }
    }
}
