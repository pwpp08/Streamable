package com.streamflixreborn.streamflix.desktop.player

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import java.io.File

/**
 * Makes the torrent engine's native library loadable. It ships inside a jar for the system the
 * app was built on; libtorrent4j loads it from the path in `libtorrent4j.jni.path`, so it is
 * copied out once. Where no library exists (Intel Macs), torrents are simply not offered.
 */
object TorrentSupport {
    private const val TAG = "TorrentSupport"

    val available: Boolean by lazy { prepare() }

    private fun prepare(): Boolean = runCatching {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val archProp = System.getProperty("os.arch").orEmpty().lowercase()
        val arch = if (archProp.contains("aarch64") || archProp.contains("arm")) "arm64" else "x86_64"
        val name = when {
            os.contains("win") -> "libtorrent4j.dll"
            os.contains("mac") -> "libtorrent4j.dylib"
            else -> "libtorrent4j.so"
        }
        val resource = "lib/$arch/$name"
        val stream = TorrentSupport::class.java.classLoader.getResourceAsStream(resource)
        if (stream == null) {
            Log.i(TAG, "no torrent library for $os/$arch; torrents are off")
            return@runCatching false
        }
        val dir = File(StreamFlixApp.instance.cacheDir, "native").apply { mkdirs() }
        val target = File(dir, name)
        stream.use { input ->
            val bytes = input.readBytes()
            if (!target.exists() || target.length() != bytes.size.toLong()) {
                val tmp = File(dir, "$name.tmp")
                tmp.writeBytes(bytes)
                target.delete()
                if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
            }
        }
        System.setProperty("libtorrent4j.jni.path", target.absolutePath)
        Log.i(TAG, "torrent library ready: ${target.absolutePath}")
        true
    }.onFailure { Log.w(TAG, "torrent library unavailable: ${it.message}") }.getOrDefault(false)
}
