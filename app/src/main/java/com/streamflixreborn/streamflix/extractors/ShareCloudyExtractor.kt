package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.models.Video
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

class ShareCloudyExtractor : Extractor() {

    override val name = "ShareCloudy"
    override val mainUrl = "https://sharecloudy.com"

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)

        val doc = service.get(link).body() ?: ""
        val regex = Regex("""file:\s*"([^"]+)"""")
        val match = regex.find(doc)
        val url = match?.groupValues?.get(1)
        if (url == null) throw Exception("Cannot find video")

        return Video(url, headers = mapOf("Referer" to mainUrl))
    }

    private interface Service {
        companion object {

            fun build(baseUrl: String): Service {
                val client = NetworkClient.noCookies.newBuilder()
                    .addInterceptor { chain ->
                        val newRequest = chain.request().newBuilder()
                            .addHeader("Referer", baseUrl)
                            .build()
                        chain.proceed(newRequest)
                    }
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET

        suspend fun get(@Url url: String): Response<String>
    }
}