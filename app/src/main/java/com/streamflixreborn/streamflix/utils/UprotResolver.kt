package com.streamflixreborn.streamflix.utils

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object UprotResolver {

    private const val TAG = "UprotResolver"
    private const val UPROT_CONFIG_URL =
        "https://git.gay/streamflix-reborn2/streamflix/raw/branch/main/uprot.enc"
    const val MAXSTREAM_EMBED_BASE = "https://maxstream.video/emisvjoe/"

    data class Config(
        val version: Int,
        val signKey: String,
        val endpoint: String,
        val paramId: String,
        val paramType: String,
        val paramTs: String,
        val paramVer: String,
        val paramSig: String,
        val skewPrefix: String,
    )

    @Volatile
    private var cachedConfig: Config? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Resolves a uprot.net URL or raw ID into a final MaxStream embed link.
     * Supports full URLs (e.g. /msf/..., /mse/..., /msfi/...) and raw IDs.
     * Set [isStayOnline] to true if the Uprot link originated from StayOnline, which uses apiType "msi".
     */
    fun resolve(urlOrId: String, isStayOnline: Boolean = false): String? {
        Log.d(TAG, "resolve() called with urlOrId='$urlOrId', isStayOnline=$isStayOnline")

        if (urlOrId.contains("/msd/", ignoreCase = true)) {
            Log.d(TAG, "Ignoring MaxStream download link (/msd/): $urlOrId")
            return null
        }

        if (urlOrId.contains("maxstream.video", ignoreCase = true)) {
            Log.d(TAG, "Input is already a MaxStream URL: $urlOrId")
            return urlOrId
        }

        val apiType = if (isStayOnline || urlOrId.contains("stayonline.pro", ignoreCase = true) || urlOrId.contains("/msfi/", ignoreCase = true)) {
            "msi"
        } else {
            "ms"
        }

        val uprotId = Regex("""/ms[a-zA-Z]+/([A-Za-z0-9+/=]+)""").find(urlOrId)?.groupValues?.getOrNull(1)
            ?: urlOrId.takeIf { !it.contains("/") && it.isNotBlank() }
            ?: run {
                Log.w(TAG, "Failed to extract uprotId from input: '$urlOrId'")
                return null
            }

        Log.d(TAG, "Extracted uprotId='$uprotId', apiType='$apiType'")
        val result = callUprotApi(uprotId, apiType)
        if (result != null) {
            Log.d(TAG, "resolve() succeeded -> $result")
        } else {
            Log.w(TAG, "resolve() failed to produce a valid video link for uprotId='$uprotId'")
        }
        return result
    }

    /**
     * Executes Uprot API resolution with HMAC-SHA256, clock drift handling (MSDEC_TS),
     * and automatic remote payload refresh if needed.
     */
    fun callUprotApi(uprotId: String, apiType: String = "ms"): String? = try {
        Log.d(TAG, "callUprotApi() started: uprotId='$uprotId', apiType='$apiType'")
        var videoId: String? = null

        val apiBase = Keys.getUprotApiBase()
        var uprotConfig = getConfig()

        if (apiBase.isBlank()) {
            Log.w(TAG, "Uprot API base URL from native Keys is blank")
        }
        if (uprotConfig == null || uprotConfig.signKey.isBlank()) {
            Log.w(TAG, "Uprot config or signKey is missing/blank")
        }

        if (apiBase.isNotBlank()) {
            for (refreshAttempt in 0..1) {
                val currentConfig = uprotConfig ?: break
                if (currentConfig.signKey.isBlank()) break

                var tsSkew = 0L
                var isTemporaryError = false

                for (attempt in 0..4) {
                    val ts = System.currentTimeMillis() / 1000 + tsSkew
                    val message = "$uprotId|$apiType|$ts"
                    val sig = hmacSHA256(message, currentConfig.signKey)

                    val base = if (apiBase.contains("?")) {
                        apiBase.substringBeforeLast("/") + "/"
                    } else {
                        apiBase.trimEnd('/') + "/"
                    }
                    val encodedId = java.net.URLEncoder.encode(uprotId, "UTF-8")
                    val apiUrl = "$base${currentConfig.endpoint}?${currentConfig.paramId}=$encodedId&${currentConfig.paramType}=$apiType&${currentConfig.paramTs}=$ts&${currentConfig.paramVer}=${currentConfig.version}&${currentConfig.paramSig}=$sig"

                    Log.d(TAG, "Sending dec API request (refreshAttempt=$refreshAttempt, attempt=$attempt, skew=${tsSkew}s)")

                    val req = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.101 Safari/537.36")
                        .header("X-Requested-With", "")
                        .header("Accept-Language", "it")
                        .build()

                    val resp = try {
                        httpClient.newCall(req).execute()
                    } catch (e: Exception) {
                        Log.w(TAG, "Dec API network exception on attempt $attempt: ${e.message}")
                        break
                    }

                    Log.d(TAG, "Dec API response code: ${resp.code}")

                    if (!resp.isSuccessful) {
                        val errorSnippet = resp.body?.string()?.take(200)?.trim().orEmpty()
                        Log.w(TAG, "Dec API returned HTTP ${resp.code}: $errorSnippet")
                        break
                    }

                    val responseText = resp.body?.string()?.trim().orEmpty()
                    val preview = if (responseText.length > 200) responseText.take(200) + "..." else responseText
                    Log.d(TAG, "Dec API response received: length=${responseText.length}, body='$preview'")

                    val isClockSkew = attempt < 4 && (
                        (currentConfig.skewPrefix.isNotBlank() && responseText.startsWith(currentConfig.skewPrefix)) ||
                        responseText.matches(Regex("""^[A-Z0-9_]+\s+\d+$"""))
                    )
                    if (isClockSkew) {
                        val serverTs = responseText.split(" ").getOrNull(1)?.toLongOrNull()
                        if (serverTs != null) {
                            tsSkew = serverTs - System.currentTimeMillis() / 1000
                            Log.d(TAG, "Clock skew detected. Server timestamp: $serverTs, adjusting skew: ${tsSkew}s. Retrying...")
                        } else {
                            Log.w(TAG, "Received clock skew response but could not parse timestamp: '$responseText'")
                        }
                        continue
                    }

                    if (responseText.contains("error code: 1005", ignoreCase = true)) {
                        isTemporaryError = true
                        if (attempt < 4) {
                            val delayMs = 700L + attempt * 300L
                            Log.d(TAG, "Temporary API error (error code: 1005) detected on attempt $attempt. Retrying in ${delayMs}ms...")
                            try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
                            continue
                        } else {
                            Log.w(TAG, "Temporary API error (error code: 1005) persisted after $attempt attempts")
                            break
                        }
                    }

                    if (responseText.isNotBlank() && !responseText.contains("<") && !responseText.contains(" ")) {
                        videoId = responseText
                        Log.d(TAG, "Extracted valid videoId: $videoId")
                    } else {
                        Log.w(TAG, "Response text is not a valid videoId: '$preview'")
                    }
                    break
                }

                if (videoId != null) break

                if (isTemporaryError) {
                    Log.d(TAG, "Temporary error (error code: 1005) occurred; skipping remote config refresh")
                    break
                }

                if (refreshAttempt == 0) {
                    Log.d(TAG, "Initial dec attempt failed; triggering remote config refresh...")
                    val refreshed = refreshConfig()
                    if (refreshed != null && (refreshed.version != currentConfig.version || refreshed.signKey != currentConfig.signKey)) {
                        Log.d(TAG, "Remote config updated with new parameters, retrying dec API...")
                        uprotConfig = refreshed
                    } else {
                        Log.w(TAG, "Remote config was not updated or identical to current, aborting retry")
                        break
                    }
                }
            }
        }

        videoId?.let { "$MAXSTREAM_EMBED_BASE$it" }
    } catch (e: Exception) {
        Log.e(TAG, "Error resolving Uprot API: ${e.message}", e)
        null
    }

    private fun hmacSHA256(message: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the Uprot configuration (version and signKey).
     * 1. Uses in-memory cachedConfig if present.
     * 2. Uses local persistent storage (UserPreferences) if present.
     * 3. If absent, performs a refresh by downloading and decrypting the remote payload.
     */
    fun getConfig(): Config? {
        cachedConfig?.let {
            Log.d(TAG, "getConfig(): Using in-memory cached config")
            return it
        }

        val jsonStr = runCatching { UserPreferences.uprotConfigJson }.getOrNull()
        if (!jsonStr.isNullOrBlank()) {
            val parsed = parseConfigJson(jsonStr)
            if (parsed != null) {
                Log.d(TAG, "getConfig(): Loaded valid config from UserPreferences")
                cachedConfig = parsed
                return parsed
            }
        }

        Log.d(TAG, "getConfig(): No valid local config available, initiating remote refresh...")
        return refreshConfig()
    }

    /**
     * Downloads and decrypts the remote configuration using the AES key protected in C++/NDK.
     * Saves decrypted parameters in memory and in UserPreferences.
     */
    @Synchronized
    fun refreshConfig(): Config? {
        Log.d(TAG, "refreshConfig(): Fetching payload from $UPROT_CONFIG_URL")
        return try {
            val req = Request.Builder()
                .url("$UPROT_CONFIG_URL?t=${System.currentTimeMillis()}")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36")
                .header("Accept", "text/plain, */*")
                .build()

            val responseBody = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Remote Uprot config download failed: HTTP ${resp.code}")
                    return null
                }
                resp.body?.string().orEmpty().trim()
            }

            if (responseBody.isBlank()) {
                Log.w(TAG, "Remote Uprot config response body is blank")
                return null
            }
            Log.d(TAG, "refreshConfig(): Remote payload downloaded (length=${responseBody.length})")

            val (decrypted, rawJson) = decryptConfig(responseBody) ?: run {
                Log.w(TAG, "refreshConfig(): Failed to decrypt remote payload")
                return null
            }

            cachedConfig = decrypted
            runCatching {
                UserPreferences.uprotConfigJson = rawJson
            }

            Log.d(TAG, "Remote config successfully refreshed and stored")
            decrypted
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing remote config: ${e.message}", e)
            null
        }
    }

    /**
     * Decrypts a Base64 string containing [IV (16 bytes) + AES-256-CBC ciphertext].
     * The 256-bit AES key is securely retrieved from the native C++ NDK layer.
     */
    fun decryptConfig(base64Payload: String): Pair<Config, String>? {
        val hexKey = Keys.getUprotAesKey().takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "decryptConfig(): Uprot AES key from Keys is blank")
            return null
        }
        return try {
            val raw = Base64.decode(base64Payload.trim(), Base64.DEFAULT)
            if (raw.size <= 16) {
                Log.w(TAG, "decryptConfig(): Encrypted payload too short (size=${raw.size})")
                return null
            }

            val iv = raw.copyOfRange(0, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)

            val keyBytes = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            val decryptedJson = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            val config = parseConfigJson(decryptedJson)
            if (config != null) {
                Log.d(TAG, "decryptConfig(): Payload successfully decrypted")
                Pair(config, decryptedJson)
            } else {
                Log.w(TAG, "decryptConfig(): Decrypted payload invalid or missing required parameters")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting AES payload: ${e.message}", e)
            null
        }
    }

    private fun parseConfigJson(jsonStr: String): Config? {
        return try {
            val json = JSONObject(jsonStr)
            val version = json.optInt("version", 0)
            val signKey = json.optString("signKey", "")
            val endpoint = json.optString("endpoint", "")
            val paramId = json.optString("paramId", "")
            val paramType = json.optString("paramType", "")
            val paramTs = json.optString("paramTs", "")
            val paramVer = json.optString("paramVer", "")
            val paramSig = json.optString("paramSig", "")
            val skewPrefix = json.optString("skewPrefix", "")

            if (version > 0 &&
                signKey.isNotBlank() &&
                endpoint.isNotBlank() &&
                paramId.isNotBlank() &&
                paramType.isNotBlank() &&
                paramTs.isNotBlank() &&
                paramVer.isNotBlank() &&
                paramSig.isNotBlank()
            ) {
                Config(
                    version = version,
                    signKey = signKey,
                    endpoint = endpoint,
                    paramId = paramId,
                    paramType = paramType,
                    paramTs = paramTs,
                    paramVer = paramVer,
                    paramSig = paramSig,
                    skewPrefix = skewPrefix,
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
