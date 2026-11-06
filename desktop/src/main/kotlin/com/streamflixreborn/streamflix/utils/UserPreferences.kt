package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties

/**
 * Desktop settings, in a properties file next to the library. Same names as the Android
 * UserPreferences where the shared code reads them; the desktop defaults differ where the
 * hardware does (quality is capped at 2160p, not 1080p).
 */
object UserPreferences {

    private const val DEFAULT_PROVIDER_NAME = "TMDb (en)"
    private const val DEFAULT_STREAMINGCOMMUNITY_DOMAIN = "streamingunity.fun"

    /** Nothing below this is picked automatically. */
    const val MIN_RESOLUTION = 1080

    private val file = File(StreamFlixApp.instance.dataDir, "settings.properties")
    private val props = Properties().apply {
        runCatching { if (file.exists()) file.inputStream().use(::load) }
    }

    /** Bumps on every change so the UI can follow settings. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun get(key: String): String? = synchronized(props) { props.getProperty(key) }

    private fun set(key: String, value: String?) {
        synchronized(props) {
            if (value == null) props.remove(key) else props.setProperty(key, value)
            runCatching {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.outputStream().use { props.store(it, "StreamFlix desktop settings") }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
        _revision.value++
    }

    private fun bool(key: String, default: Boolean) = get(key)?.toBooleanStrictOrNull() ?: default

    var currentProvider: Provider?
        get() {
            val name = get("CURRENT_PROVIDER") ?: DEFAULT_PROVIDER_NAME
            if (name.startsWith("TMDb (") && name.endsWith(")")) return TmdbProvider(name.substringAfter("TMDb (").substringBefore(")"))
            return Provider.providers.keys.find { it.name == name } ?: TmdbProvider("en")
        }
        set(value) = set("CURRENT_PROVIDER", value?.name)

    var providerLanguage: String?
        get() = get("PROVIDER_LANGUAGE")
        set(value) = set("PROVIDER_LANGUAGE", value)

    /** Tallest stream the extractors may offer; the player itself starts at [START_RESOLUTION]. */
    var maxResolution: Int
        get() = 2160
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    /** Height playback starts at. 4K is only ever reached by the background upgrade. */
    const val START_RESOLUTION = 1080

    /** Whether to look for a working 4K server in the background and switch to it. */
    var upgrade4k: Boolean
        get() = bool("UPGRADE_4K", true)
        set(value) = set("UPGRADE_4K", value.toString())

    var serverAutoSubtitlesDisabled: Boolean
        get() = bool("SERVER_AUTO_SUBTITLES_DISABLED", true)
        set(value) = set("SERVER_AUTO_SUBTITLES_DISABLED", value.toString())

    var tmdbApiKey: String
        get() = get("TMDB_API_KEY").orEmpty()
        set(value) = set("TMDB_API_KEY", value.ifBlank { null })

    /** Desktop streams only: torrents start too slowly to be worth it here. */
    val torrentEnabled: Boolean get() = false

    var enableTmdb: Boolean
        get() = bool("ENABLE_TMDB", true)
        set(value) = set("ENABLE_TMDB", value.toString())

    var tmdbCategories: Set<String>
        get() = get("TMDB_CATEGORIES")?.split(',')?.filter { it.isNotBlank() }?.toSet()
            ?: setOf("featured", "trending", "popular_movies", "popular_tv", "popular_anime", "netflix", "amazon", "disney", "hulu", "apple", "hbo")
        set(value) = set("TMDB_CATEGORIES", value.joinToString(","))

    var streamingcommunityDomain: String
        get() = get("STREAMINGCOMMUNITY_DOMAIN")?.takeIf { it.isNotBlank() } ?: DEFAULT_STREAMINGCOMMUNITY_DOMAIN
        set(value) = set("STREAMINGCOMMUNITY_DOMAIN", value.ifBlank { null })

    var dohProviderUrl: String
        get() = get("DOH_PROVIDER_URL") ?: "https://cloudflare-dns.com/dns-query"
        set(value) {
            set("DOH_PROVIDER_URL", value)
            DnsResolver.setDnsUrl(value)
        }

    var uprotConfigJson: String?
        get() = get("UPROT_CONFIG_JSON")
        set(value) = set("UPROT_CONFIG_JSON", value)

    var maxstreamEmbedBase: String?
        get() = get("MAXSTREAM_EMBED_BASE")
        set(value) = set("MAXSTREAM_EMBED_BASE", value)

    fun clearProviderCache(@Suppress("UNUSED_PARAMETER") providerName: String) = Unit

    // ---- Desktop-only ----------------------------------------------------------------------

    /** Subtitle language to switch on automatically ("" = off). */
    var subtitleLanguage: String
        get() = get("SUBTITLE_LANGUAGE") ?: ""
        set(value) = set("SUBTITLE_LANGUAGE", value)

    var volume: Int
        get() = get("VOLUME")?.toIntOrNull() ?: 100
        set(value) = set("VOLUME", value.toString())

    var themeMode: String
        get() = get("THEME_MODE")?.takeIf { it in setOf("sage", "crimson", "amoled") } ?: "sage"
        set(value) = set("THEME_MODE", value)

    var autoplayNext: Boolean
        get() = bool("AUTOPLAY_NEXT", true)
        set(value) = set("AUTOPLAY_NEXT", value.toString())

    var vlcPath: String
        get() = get("VLC_PATH").orEmpty()
        set(value) = set("VLC_PATH", value.ifBlank { null })

    fun getString(key: String): String? = get(key)
    fun setString(key: String, value: String?) = set(key, value)
}
