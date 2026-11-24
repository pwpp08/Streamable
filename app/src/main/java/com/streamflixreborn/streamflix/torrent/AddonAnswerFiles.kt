package com.streamflixreborn.streamflix.torrent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/**
 * Addon answers kept in app-private storage (not backed up), so a film in Continue Watching can start
 * from the last answer instead of waiting for the addon again. Files are named by a hash of the
 * request and never contain the addon link itself.
 *
 * The JSON is written and read field by field on purpose: reflection-based (Gson) reading of a class
 * holding a list gave back untyped maps in the minified release build, and the cast that followed
 * crashed the app on every launch once an answer had been saved.
 */
class AddonAnswerFiles(private val dir: File) : StremioAddons.AnswerStore {

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, hash.take(32) + SUFFIX)
    }

    override fun load(url: String): Pair<Long, List<AddonStream>>? = runCatching {
        val file = fileFor(url)
        if (!file.exists()) return null
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val at = root.get("at").asLong
        val streams = root.getAsJsonArray("streams").map { streamFrom(it.asJsonObject) }
        at to streams
    }.getOrNull()

    override fun save(url: String, at: Long, streams: List<AddonStream>) {
        runCatching {
            dir.mkdirs()
            val root = JsonObject().apply {
                addProperty("at", at)
                add("streams", JsonArray().also { array -> streams.forEach { array.add(streamTo(it)) } })
            }
            fileFor(url).writeText(root.toString())
            // Files from the earlier format are never read again.
            dir.listFiles()?.filter { !it.name.endsWith(SUFFIX) }?.forEach { it.delete() }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
        }
    }

    override fun delete(url: String) {
        runCatching { fileFor(url).delete() }
    }

    private fun streamTo(s: AddonStream): JsonObject = JsonObject().apply {
        s.torrent?.let { t ->
            add("torrent", JsonObject().apply {
                addProperty("hash", t.infoHash)
                t.fileIdx?.let { addProperty("idx", it) }
                addProperty("release", t.release)
                t.fileName?.let { addProperty("file", it) }
                addProperty("seeders", t.seeders)
                addProperty("size", t.sizeBytes)
                addProperty("site", t.site)
                addProperty("languages", t.languages)
                addProperty("height", t.height)
                add("trackers", JsonArray().also { a -> t.trackers.forEach { a.add(it) } })
            })
        }
        s.direct?.let { d ->
            add("direct", JsonObject().apply {
                addProperty("url", d.url)
                addProperty("height", d.height)
                addProperty("label", d.label)
                add("headers", JsonObject().also { h -> d.headers.forEach { (k, v) -> h.addProperty(k, v) } })
                addProperty("measured", d.measured)
            })
        }
    }

    private fun streamFrom(o: JsonObject): AddonStream {
        val torrent = o.getAsJsonObject("torrent")?.let { t ->
            TorrentStream(
                infoHash = t.get("hash").asString,
                fileIdx = t.get("idx")?.takeIf { !it.isJsonNull }?.asInt,
                release = t.get("release").asString,
                fileName = t.get("file")?.takeIf { !it.isJsonNull }?.asString,
                seeders = t.get("seeders").asInt,
                sizeBytes = t.get("size").asLong,
                site = t.get("site").asString,
                languages = t.get("languages").asString,
                height = t.get("height").asInt,
                trackers = t.getAsJsonArray("trackers").map { it.asString },
            )
        }
        val direct = o.getAsJsonObject("direct")?.let { d ->
            AddonStream.Direct(
                url = d.get("url").asString,
                height = d.get("height").asInt,
                label = d.get("label").asString,
                headers = d.getAsJsonObject("headers").entrySet().associate { it.key to it.value.asString },
                measured = d.get("measured").asBoolean,
            )
        }
        return AddonStream(torrent, direct)
    }

    private companion object {
        const val SUFFIX = ".v2.json"
        const val MAX_FILES = 80
    }
}
