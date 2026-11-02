package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * VAVOO_DOMAIN_ROTATION_V1
 *
 * Ordered Vavoo mirror probing.
 *
 * "Check / update domain" starts at vavoo.to.
 * "Next" continues after the currently selected domain and wraps around.
 */
object VavooDomainSelector {

    val candidates: List<String> = listOf(
        "vavoo.to",
        "vavoo.ip",
        "vavoo.net",
        "vavoo.top",

        "huhu.bz",
        "huhu.to",

        "kool.ip",
        "kool.ninja",
        "kool.ws",
        "kool.to",

        "oha.online",
        "oha.cx",
        "oha.to",

        "voltop.net",

        "185.254.196.78",
        "178.83.5.50",
    )

    data class Selection(
        val requestedHost: String,
        val finalUrl: String,
    )

    private val client =
        NetworkClient.noCookies.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()

    suspend fun firstAvailable(): Selection =
        findAvailable(
            startIndex = 0,
            skipFinalHost = null,
        )

    suspend fun nextAvailable(
        currentUrl: String,
    ): Selection {
        val currentHost =
            hostOnly(currentUrl)

        val currentIndex =
            candidates.indexOfFirst {
                it.equals(
                    currentHost,
                    ignoreCase = true,
                )
            }

        val start =
            if (currentIndex >= 0) {
                (currentIndex + 1) % candidates.size
            } else {
                0
            }

        return findAvailable(
            startIndex = start,
            skipFinalHost = currentHost,
        )
    }

    private suspend fun findAvailable(
        startIndex: Int,
        skipFinalHost: String?,
    ): Selection =
        withContext(Dispatchers.IO) {

            var lastError: Throwable? = null

            for (offset in candidates.indices) {
                val index =
                    (startIndex + offset) %
                        candidates.size

                val host = candidates[index]

                val urls =
                    if (isIpAddress(host)) {
                        listOf(
                            "https://$host",
                            "http://$host",
                        )
                    } else {
                        listOf(
                            "https://$host",
                        )
                    }

                for (url in urls) {
                    val probe =
                        runCatching {
                            probe(url)
                        }

                    probe.onFailure {
                        lastError = it
                    }

                    val finalUrl =
                        probe.getOrNull()
                            ?: continue

                    val finalHost =
                        hostOnly(finalUrl)

                    if (
                        !skipFinalHost.isNullOrBlank() &&
                        finalHost.equals(
                            skipFinalHost,
                            ignoreCase = true,
                        )
                    ) {
                        continue
                    }

                    return@withContext Selection(
                        requestedHost = host,
                        finalUrl = finalUrl,
                    )
                }
            }

            throw IllegalStateException(
                lastError?.message
                    ?: "No reachable Vavoo domain found"
            )
        }

    private fun probe(
        url: String,
    ): String {
        val request =
            Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36"
                )
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,*/*;q=0.8"
                )
                .get()
                .build()

        client.newCall(request)
            .execute()
            .use { response ->

                /*
                 * 2xx-4xx means the host answered.
                 * A dead gateway / dead mirror normally results in
                 * 5xx, connection failure or timeout.
                 *
                 * Playback viability is deliberately left to the user:
                 * if the selected mirror does not play, "Next" moves on.
                 */
                if (response.code >= 500) {
                    throw IllegalStateException(
                        "HTTP ${response.code}"
                    )
                }

                return response.request.url
                    .newBuilder()
                    .encodedPath("/")
                    .query(null)
                    .fragment(null)
                    .build()
                    .toString()
                    .trimEnd('/')
            }
    }

    fun hostOnly(
        value: String,
    ): String {
        val normalized =
            if (
                value.startsWith("http://") ||
                value.startsWith("https://")
            ) {
                value
            } else {
                "https://$value"
            }

        return normalized
            .toHttpUrlOrNull()
            ?.host
            ?.removePrefix("www.")
            .orEmpty()
    }

    private fun isIpAddress(
        host: String,
    ): Boolean =
        host.matches(
            Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
        )
}
