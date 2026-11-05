package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video

// On Android these two load the embed in a hidden WebView and catch the playlist request it
// makes. Desktop has no browser engine, so their servers are left out of the list
// (see [BROWSER_ONLY]) and extract() reports them unavailable.

val BROWSER_ONLY = setOf("VidLink", "Vidsrc.Ru")

private fun embedServer(name: String, mainUrl: String, videoType: Video.Type) = Video.Server(
    id = name,
    name = name,
    src = when (videoType) {
        is Video.Type.Movie -> "$mainUrl/movie/${videoType.id}"
        is Video.Type.Episode -> "$mainUrl/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
    },
)

class VidLinkExtractor : Extractor() {
    override val name = "VidLink"
    override val mainUrl = "https://vidlink.pro"
    fun server(videoType: Video.Type) = embedServer(name, mainUrl, videoType)
    override suspend fun extract(link: String): Video = throw UnsupportedOperationException("$name needs a browser engine")
}

class VidsrcRuExtractor : Extractor() {
    override val name = "Vidsrc.Ru"
    override val mainUrl = "https://vidsrc.ru"
    fun server(videoType: Video.Type) = embedServer(name, mainUrl, videoType)
    override suspend fun extract(link: String): Video = throw UnsupportedOperationException("$name needs a browser engine")
}
