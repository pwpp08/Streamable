package com.streamflixreborn.streamflix

import java.io.File

/** Desktop stand-in for the Android Application: where the app keeps its data and caches. */
class StreamFlixApp private constructor() {

    val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Settings, the library and watch history. */
    val dataDir: File = when {
        isWindows -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "StreamFlix")
        isMac -> File(System.getProperty("user.home"), "Library/Application Support/StreamFlix")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share", "streamflix")
    }.apply { mkdirs() }

    /** HTTP cache and torrent pieces; safe to delete. */
    val cacheDir: File = when {
        isWindows -> File(System.getenv("LOCALAPPDATA") ?: dataDir.path, "StreamFlix/cache")
        isMac -> File(System.getProperty("user.home"), "Library/Caches/StreamFlix")
        else -> File(System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache", "streamflix")
    }.apply { mkdirs() }

    val applicationContext = android.content.Context()

    companion object {
        val instance: StreamFlixApp by lazy { StreamFlixApp() }
    }
}
