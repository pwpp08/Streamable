package com.streamflixreborn.streamflix.torrent

import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * Addon answers kept in app-private storage (not backed up), so a film in Continue Watching can start
 * from the last answer instead of waiting for the addon again. Files are named by a hash of the
 * request and never contain the addon link itself.
 */
class AddonAnswerFiles(private val dir: File) : StremioAddons.AnswerStore {
    private class Saved(val at: Long, val streams: List<AddonStream>)

    private val gson = Gson()

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, hash.take(32) + ".json")
    }

    override fun load(url: String): Pair<Long, List<AddonStream>>? = runCatching {
        val file = fileFor(url)
        if (!file.exists()) return null
        gson.fromJson(file.readText(), Saved::class.java)?.let { it.at to it.streams }
    }.getOrNull()

    override fun save(url: String, at: Long, streams: List<AddonStream>) {
        runCatching {
            dir.mkdirs()
            fileFor(url).writeText(gson.toJson(Saved(at, streams)))
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
        }
    }

    override fun delete(url: String) {
        runCatching { fileFor(url).delete() }
    }

    private companion object {
        const val MAX_FILES = 80
    }
}
