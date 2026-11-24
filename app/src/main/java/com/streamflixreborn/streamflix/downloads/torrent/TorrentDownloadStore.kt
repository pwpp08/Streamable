package com.streamflixreborn.streamflix.downloads.torrent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** The torrent downloads, on disk (app-private, not backed up) and observable. */
object TorrentDownloadStore {
    private const val TAG = "TorrentDownloadStore"
    private const val FILE = "torrent-downloads.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var file: File? = null
    private var lastWrite = 0L

    private val _records = MutableStateFlow<Map<String, TorrentDownloadRecord>>(emptyMap())
    val records: StateFlow<Map<String, TorrentDownloadRecord>>
        get() {
            ensure()
            return _records
        }

    private fun ensure() {
        if (file == null) init(com.streamflixreborn.streamflix.StreamFlixApp.instance)
    }

    fun init(context: Context) = synchronized(lock) {
        if (file != null) return
        val f = File(context.applicationContext.noBackupFilesDir, FILE)
        file = f
        val loaded = runCatching {
            if (f.exists()) json.decodeFromString(ListSerializer(TorrentDownloadRecord.serializer()), f.readText()) else emptyList()
        }.onFailure { Log.w(TAG, "could not read the torrent downloads", it) }.getOrDefault(emptyList())
        // Nothing runs yet in this process: a download that was active was stopped with the app (killed, or
        // crashed), and is not restarted by itself - a crashing download would then crash every launch.
        _records.value = loaded.map {
            if (it.isActive) it.copy(state = TorrentDownloadState.PAUSED, message = null) else it
        }.associateBy { it.id }
    }

    fun get(id: String): TorrentDownloadRecord? {
        ensure()
        return _records.value[id]
    }

    fun put(record: TorrentDownloadRecord, persist: Boolean = true) {
        ensure()
        putLocked(record, persist)
    }

    private fun putLocked(record: TorrentDownloadRecord, persist: Boolean) = synchronized(lock) {
        _records.value = _records.value + (record.id to record)
        if (persist) write(force = true)
    }

    /** Applies [change] to the record [id]; progress-only changes are written to disk at most every few seconds. */
    fun update(id: String, persist: Boolean = true, change: (TorrentDownloadRecord) -> TorrentDownloadRecord): TorrentDownloadRecord? =
        synchronized(lock) {
            ensure()
            val current = _records.value[id] ?: return null
            val next = change(current)
            _records.value = _records.value + (id to next)
            write(force = persist)
            next
        }

    fun remove(id: String) = synchronized(lock) {
        _records.value = _records.value - id
        write(force = true)
    }

    private fun write(force: Boolean) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastWrite < 5_000L) return
        lastWrite = now
        runCatching {
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(TorrentDownloadRecord.serializer()), _records.value.values.toList()))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not save the torrent downloads", it) }
    }
}
