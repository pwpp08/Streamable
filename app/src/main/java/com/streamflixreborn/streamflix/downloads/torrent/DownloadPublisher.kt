package com.streamflixreborn.streamflix.downloads.torrent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Puts a finished download where other apps can see it: the phone's Movies folder (Movies/Streamable),
 * through MediaStore, so VLC, MX Player, the gallery and file managers list it. Needs Android 10+;
 * on older phones the file stays inside the app.
 */
object DownloadPublisher {
    private const val TAG = "DownloadPublisher"
    const val FOLDER = "Streamable"

    private val unsafe = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /** "Inception (2010)" or "Breaking Bad - S01E02 - Cat's in the Bag", without the extension. */
    fun baseName(record: TorrentDownloadRecord): String {
        val t = record.videoType
        val name = if (t.kind == "episode") {
            val season = t.seasonNumber.toString().padStart(2, '0')
            val episode = t.number.toString().padStart(2, '0')
            listOfNotNull(t.showTitle?.takeIf { it.isNotBlank() }, "S${season}E$episode", t.title?.takeIf { it.isNotBlank() })
                .joinToString(" - ")
        } else {
            val year = t.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            listOfNotNull(record.title.ifBlank { t.title }, year?.let { "($it)" }).joinToString(" ")
        }
        return name.replace(unsafe, " ").replace(Regex("\\s+"), " ").trim().take(120).ifBlank { "Download" }
    }

    fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        else -> "video/*"
    }

    val canPublish: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Copies [file] into Movies/Streamable. Returns the content:// URI, or null when this phone cannot. */
    fun publishVideo(context: Context, file: File, displayName: String, mime: String): Uri? {
        if (!canPublish) return null
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 1 shl 20) }
            } ?: error("could not open the new file")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        } catch (e: Exception) {
            Log.w(TAG, "publishing $displayName failed", e)
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** A subtitle next to nothing: the Downloads folder, for files that can't take a subtitle track. */
    fun publishSubtitle(context: Context, bytes: ByteArray, displayName: String): Uri? {
        if (!canPublish) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("could not open the new file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Deletes a published (content://) or in-app (file://) result. */
    fun delete(context: Context, uriString: String?) {
        uriString ?: return
        runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
            else context.contentResolver.delete(uri, null, null)
        }.onFailure { Log.w(TAG, "could not delete $uriString", it) }
    }

    /** True when the published file is still there (the viewer may have deleted it in a file manager). */
    fun exists(context: Context, uriString: String?): Boolean {
        uriString ?: return false
        return runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") File(requireNotNull(uri.path)).isFile
            else context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
