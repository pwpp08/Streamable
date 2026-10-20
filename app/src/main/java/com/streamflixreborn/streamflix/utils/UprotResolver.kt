package com.streamflixreborn.streamflix.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object UprotResolver {

    data class Config(
        val version: Int,
        val signKey: String,
    )

    @Volatile
    private var cachedConfig: Config? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun decode(b: ByteArray): String =
        String(ByteArray(b.size) { (b[it].toInt() xor 0x5D).toByte() }, Charsets.UTF_8)

    private val P_VER by lazy {
        Pattern.compile(decode(byteArrayOf(
            0x7b, 0x2b, 0x60, 0x75, 0x01, 0x39, 0x76, 0x74, 0x7b, 0x2e, 0x34, 0x3a, 0x60
        )))
    }

    private val P_S1 by lazy {
        Pattern.compile(decode(byteArrayOf(
            0x2b, 0x3c, 0x2f, 0x01, 0x2e, 0x76, 0x39, 0x05, 0x11, 0x10, 0x3a, 0x01, 0x2e, 0x77, 0x60, 0x01, 0x2e, 0x77, 0x06, 0x7f, 0x01, 0x7a, 0x00, 0x75, 0x06, 0x03, 0x7f, 0x01, 0x7a, 0x00, 0x76, 0x74, 0x06, 0x7f, 0x01, 0x7a, 0x00
        )))
    }

    private val P_S2 by lazy {
        Pattern.compile(decode(byteArrayOf(
            0x2b, 0x3c, 0x2f, 0x01, 0x2e, 0x76, 0x30, 0x6c, 0x30, 0x3b, 0x1a, 0x0f, 0x01, 0x2e, 0x77, 0x60, 0x01, 0x2e, 0x77, 0x06, 0x7f, 0x01, 0x7a, 0x00, 0x75, 0x06, 0x03, 0x7f, 0x01, 0x7a, 0x00, 0x76, 0x74, 0x06, 0x7f, 0x01, 0x7a, 0x00
        )))
    }

    private val P_O1 by lazy {
        Pattern.compile(decode(byteArrayOf(
            0x39, 0x05, 0x11, 0x10, 0x3a, 0x01, 0x73, 0x2e, 0x2d, 0x31, 0x34, 0x29, 0x01, 0x75, 0x73, 0x76, 0x62, 0x29, 0x01, 0x06, 0x2f, 0x01, 0x00, 0x01, 0x2e, 0x77, 0x70, 0x01, 0x2e, 0x77, 0x75, 0x01, 0x39, 0x76, 0x74
        )))
    }

    private val P_HEX by lazy {
        Pattern.compile(decode(byteArrayOf(
            0x06, 0x7f, 0x01, 0x7a, 0x00, 0x75, 0x06, 0x6d, 0x70, 0x64, 0x3c, 0x70, 0x3b, 0x00, 0x26, 0x6c, 0x6d, 0x71, 0x20, 0x74, 0x06, 0x7f, 0x01, 0x7a, 0x00
        )))
    }

    private val P_XOR by lazy {
        Pattern.compile(decode(byteArrayOf(
            0x0e, 0x29, 0x2f, 0x34, 0x33, 0x3a, 0x01, 0x73, 0x3b, 0x2f, 0x32, 0x30, 0x1e, 0x35, 0x3c, 0x2f, 0x1e, 0x32, 0x39, 0x38, 0x01, 0x75, 0x75, 0x01, 0x39, 0x76, 0x74, 0x01, 0x03, 0x2d, 0x3c, 0x2f, 0x2e, 0x38, 0x14, 0x33, 0x29
        )))
    }

    private val P_O2 by lazy {
        Pattern.compile(decode(byteArrayOf(
            0x30, 0x6c, 0x30, 0x3b, 0x1a, 0x0f, 0x01, 0x73, 0x2e, 0x2d, 0x31, 0x34, 0x29, 0x01, 0x75, 0x73, 0x76, 0x62, 0x29, 0x01, 0x06, 0x2f, 0x01, 0x00, 0x01, 0x2e, 0x77, 0x70, 0x01, 0x2e, 0x77, 0x75, 0x01, 0x39, 0x76, 0x74
        )))
    }

    private val DEF_HEX by lazy {
        decode(byteArrayOf(
            0x68, 0x38, 0x6d, 0x3f, 0x6d, 0x38, 0x6b, 0x38, 0x6e, 0x3f, 0x6b, 0x38, 0x6f, 0x3f, 0x6a, 0x3f, 0x68, 0x38, 0x6f, 0x3f, 0x64, 0x38
        ))
    }

    fun getConfig(): Config? {
        cachedConfig?.let { return it }

        val savedVersion = runCatching { UserPreferences.uprotResolvedVersion }.getOrDefault(0)
        val savedKey = runCatching { UserPreferences.uprotResolvedSignKey }.getOrNull()

        if (savedVersion > 0 && !savedKey.isNullOrBlank()) {
            val cfg = Config(savedVersion, savedKey)
            cachedConfig = cfg
            return cfg
        }

        return refreshConfig()
    }

    private fun resolveA(encoded: String, offset: Int): String {
        return encoded.split(":")
            .mapNotNull { it.trim().toIntOrNull() }
            .map { (it - offset).toChar() }
            .joinToString("")
    }

    private fun resolveB(encoded: String, offset: Int): String {
        return encoded.split("-")
            .mapNotNull { it.trim().toIntOrNull() }
            .map { (it - offset).toChar() }
            .joinToString("")
    }

    private fun resolveC(hexStr: String, xorVal: Int): String {
        val reversed = hexStr.reversed()
        val sb = StringBuilder()
        var i = 0
        while (i < reversed.length - 1) {
            val chunk = reversed.substring(i, i + 2)
            val byteVal = chunk.toIntOrNull(16) ?: break
            sb.append((xorVal xor byteVal).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun extractKey(payload: String): String? {
        val m1 = P_S1.matcher(payload)
        val m2 = P_S2.matcher(payload)

        if (!m1.find() || !m2.find()) return null

        val v1 = m1.group(1).orEmpty()
        val v2 = m2.group(1).orEmpty()
        if (v1.isBlank() || v2.isBlank()) return null

        val mo1 = P_O1.matcher(payload)
        val o1 = if (mo1.find()) mo1.group(1)?.toIntOrNull() ?: 78 else 78
        val s1 = resolveA(v1, o1)

        val mh = P_HEX.matcher(payload)
        val hStr = if (mh.find()) mh.group(1).orEmpty() else DEF_HEX

        val mx = P_XOR.matcher(payload)
        val xVal = if (mx.find()) mx.group(1)?.toIntOrNull() ?: 209 else 209
        val s2 = resolveC(hStr, xVal)

        val mo2 = P_O2.matcher(payload)
        val o2 = if (mo2.find()) mo2.group(1)?.toIntOrNull() ?: 43 else 43
        val s3 = resolveB(v2, o2)

        val key = s1 + s2 + s3
        return key.ifBlank { null }
    }

    @Synchronized
    fun refreshConfig(): Config? {
        val entryUrl = Keys.getUprotEntryUrl()
        val jsName = Keys.getUprotJsName()
        val searchToken = Keys.getUprotSearchToken()
        if (entryUrl.isBlank() || jsName.isBlank() || searchToken.isBlank()) return null

        return try {
            val req1 = Request.Builder()
                .url(entryUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()

            val html = httpClient.newCall(req1).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }

            val baseRegex = Pattern.compile("${Pattern.quote(searchToken)}\\s*=\\s*[\"']([^\"']+)[\"']")
            val mBase = baseRegex.matcher(html)
            if (!mBase.find()) return null
            val baseUrl = mBase.group(1)?.trim().orEmpty()
            if (baseUrl.isBlank()) return null

            val jsUrl = if (baseUrl.endsWith("/")) "$baseUrl$jsName" else "$baseUrl/$jsName"
            val req2 = Request.Builder()
                .url("$jsUrl?p=${System.currentTimeMillis()}")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Referer", entryUrl)
                .build()

            val js = httpClient.newCall(req2).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }

            val mV = P_VER.matcher(js)
            val version = if (mV.find()) mV.group(1)?.toIntOrNull() ?: 6 else 6

            val decodedKey = extractKey(js) ?: return null

            val newConfig = Config(version, decodedKey)
            cachedConfig = newConfig

            runCatching {
                UserPreferences.uprotResolvedVersion = version
                UserPreferences.uprotResolvedSignKey = decodedKey
            }

            newConfig
        } catch (_: Throwable) {
            null
        }
    }
}
