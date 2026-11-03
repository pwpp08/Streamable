package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.utils.NetworkClient
import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UprotResolver
import org.jsoup.nodes.Document
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.util.regex.Pattern

class MaxstreamExpiredException(message: String) : Exception(message)

class MaxstreamExtractor : Extractor() {
    override val name = "Maxstream"
    override val mainUrl = "https://maxstream.video"

    override suspend fun extract(link: String): Video {
        return try {
            extractInternal(link)
        } catch (e: Exception) {
            val is404 = (e as? HttpException)?.code() == 404 || e.message?.contains("404") == true
            if (is404) {
                Log.w("MaxstreamExtractor", "Maxstream returned 404 with link '$link'. Attempting embed refresh...")
                val videoId = link.substringBefore("?").trimEnd('/').substringAfterLast('/')
                val refreshedEmbedBase = UprotResolver.refreshMaxstreamEmbedBase()
                if (!refreshedEmbedBase.isNullOrBlank()) {
                    val newLink = "${refreshedEmbedBase.trimEnd('/')}/$videoId"
                    if (newLink != link) {
                        Log.i("MaxstreamExtractor", "Retrying Maxstream extraction with refreshed link: $newLink")
                        return extractInternal(newLink)
                    }
                }
            } else if (e is MaxstreamExpiredException || e.message?.contains("File is not longer available", ignoreCase = true) == true) {
                Log.w("MaxstreamExtractor", "Maxstream returned 'expired or deleted' for link '$link'. Attempting Uprot re-resolve...")
                val videoId = link.substringBefore("?").trimEnd('/').substringAfterLast('/')
                val newLink = UprotResolver.reResolveOnExpired(videoId)
                if (!newLink.isNullOrBlank() && newLink != link) {
                    Log.i("MaxstreamExtractor", "Retrying Maxstream extraction with newly resolved link: $newLink")
                    return extractInternal(newLink)
                }
            }
            throw e
        }
    }

    private suspend fun extractInternal(link: String): Video {
        val baseUri = try {
            val uri = java.net.URI(link)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            mainUrl
        }
        val service = Service.build(baseUri)
        val document = service.get(link)
        val html = document.toString()

        if (html.contains("File is not longer available as it expired or has been deleted", ignoreCase = true)) {
            throw MaxstreamExpiredException("File is not longer available as it expired or has been deleted.")
        }

        val srcRegex = Pattern.compile("sources\\s*:\\s*\\[\\s*\\{\\s*[sS]rc\\s*:\\s*[\"']([^\"']+)[\"']")
        val matcher = srcRegex.matcher(html)

        if (matcher.find()) {
            val src = matcher.group(1) ?: throw Exception("Could not extract src from Maxstream player")
            return Video(
                source = src
            )
        }

        throw Exception("Maxstream source not found in page")
    }

    private interface Service {
        @GET
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        suspend fun get(@Url url: String): Document

        companion object {
            fun build(baseUrl: String): Service {
                val client = NetworkClient.noCookies

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }
    }
}
