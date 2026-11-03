package com.streamflixreborn.streamflix.torrent

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Not retried by the player's load policy (like a missing file), so playback fails over to the
 * next server straight away.
 */
class TorrentUnavailableException(message: String) : FileNotFoundException(message)

/**
 * Streams one file of one torrent at a time. The session starts lazily, downloads only a window
 * of pieces around the playback position (urgent ones with deadlines) and is torn down, files
 * deleted, shortly after the last reader closes. Memory is kept low for 1 GB devices: capped
 * connections and peer list, no UPnP/LSD, small upload rate, no seeding.
 */
object TorrentEngine {
    private const val TAG = "TorrentEngine"
    private const val METADATA_TIMEOUT_S = 50
    private const val STALL_TIMEOUT_MS = 75_000L
    private const val IDLE_STOP_MS = 30_000L
    private const val WINDOW_AHEAD_BYTES = 96L shl 20
    private const val URGENT_BYTES = 8L shl 20
    private const val TAIL_BYTES = 3L shl 20
    private const val STATS_INTERVAL_MS = 4_000L
    private const val MAX_CONNECTIONS = 60
    private const val UPLOAD_LIMIT_BPS = 48 * 1024
    private val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts")

    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.dler.org:6969/announce",
    )

    private val lock = Any()
    private var session: SessionManager? = null
    private var active: Active? = null
    private var idleStop: ScheduledFuture<*>? = null
    private var statsTask: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "torrent-scheduler").apply { isDaemon = true }
    }

    private fun baseDir() = File(StreamFlixApp.instance.cacheDir, "torrent")

    /** Removes leftovers of a previous process (call at app start). */
    fun cleanStaleFiles() {
        runCatching { baseDir().deleteRecursively() }
    }

    /** Opens the chosen file of [spec]; blocks while the metadata is fetched. */
    @Throws(IOException::class)
    fun open(spec: TorrentSpec): Reader {
        synchronized(lock) {
            idleStop?.cancel(false)
            idleStop = null
            val current = active
            if (current != null && current.hash == spec.infoHash) {
                return current.newReader()
            }
            if (current != null) stopActive("switching torrent")
        }
        // Metadata fetch happens outside the lock so a cancelled open does not block others.
        val prepared = prepare(spec)
        synchronized(lock) {
            val existing = active
            if (existing != null && existing.hash == spec.infoHash) {
                prepared.discard()
                return existing.newReader()
            }
            if (existing != null) stopActive("replaced")
            active = prepared
            startStats()
            return prepared.newReader()
        }
    }

    /**
     * Fetches the metadata and waits for the first piece so a later [open] starts at once.
     * True when the swarm delivered data within [timeoutMs] after the metadata.
     */
    fun prewarm(spec: TorrentSpec, timeoutMs: Long = 25_000L): Boolean {
        val reader = try { open(spec) } catch (e: IOException) { return false }
        return try { reader.awaitStart(timeoutMs) } finally { reader.close() }
    }

    private fun ensureSession(): SessionManager = synchronized(lock) {
        session?.let { return it }
        val pack = SettingsPack().apply {
            connectionsLimit(MAX_CONNECTIONS)
            uploadRateLimit(UPLOAD_LIMIT_BPS)
            activeDownloads(1)
            activeSeeds(0)
            alertQueueSize(256)
            setEnableLsd(false)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
            setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), 400)
            setInteger(settings_pack.int_types.aio_threads.swigValue(), 2)
            setEnableDht(true)
            setDhtBootstrapNodes(
                "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.libtorrent.org:25401",
            )
        }
        val sm = SessionManager(false)
        sm.start(SessionParams(pack))
        Log.i(TAG, "session started (connections<=$MAX_CONNECTIONS, upload<=${UPLOAD_LIMIT_BPS / 1024} KB/s)")
        session = sm
        sm
    }

    private fun prepare(spec: TorrentSpec): Active {
        val sm = ensureSession()
        val dir = File(baseDir(), spec.infoHash).apply { deleteRecursively(); mkdirs() }
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(spec.infoHash)
            (spec.trackers + DEFAULT_TRACKERS).distinct().forEach {
                append("&tr=").append(java.net.URLEncoder.encode(it, "UTF-8"))
            }
        }
        val started = System.currentTimeMillis()
        Log.i(TAG, "fetching metadata for ${spec.infoHash} (dht nodes=${sm.dhtNodes()})")
        val data = try {
            sm.fetchMagnet(magnet, METADATA_TIMEOUT_S, dir)
        } catch (e: Exception) {
            null
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("torrent open interrupted")
        if (data == null) {
            Log.w(TAG, "no metadata after ${(System.currentTimeMillis() - started) / 1000}s, dht nodes=${sm.dhtNodes()}")
            scheduleIdleStopIfUnused()
            throw TorrentUnavailableException("No peers found for this torrent")
        }
        val info = TorrentInfo.bdecode(data)
        val fileIdx = chooseFile(info, spec.fileIdx)
        Log.i(
            TAG,
            "metadata in ${System.currentTimeMillis() - started} ms: '${info.name()}' ${info.numFiles()} files, " +
                "piece ${info.pieceLength() / 1024} KB, playing #$fileIdx ${info.files().filePath(fileIdx)} " +
                "(${info.files().fileSize(fileIdx) shr 20} MB)",
        )
        val priorities = Array(info.numFiles()) { if (it == fileIdx) Priority.DEFAULT else Priority.IGNORE }
        sm.download(info, dir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val handle = waitForHandle(sm, info.infoHash()) ?: throw TorrentUnavailableException("Torrent could not be added")
        return Active(spec.infoHash, handle, info, fileIdx, dir)
    }

    private fun waitForHandle(sm: SessionManager, hash: Sha1Hash): TorrentHandle? {
        repeat(50) {
            sm.find(hash)?.takeIf { it.isValid }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun chooseFile(info: TorrentInfo, requested: Int?): Int {
        if (requested != null && requested in 0 until info.numFiles()) return requested
        val fs = info.files()
        val videos = (0 until info.numFiles()).filter { VIDEO_EXT.contains(fs.filePath(it).substringAfterLast('.', "").lowercase()) }
        return (videos.ifEmpty { (0 until info.numFiles()).toList() }).maxByOrNull { fs.fileSize(it) } ?: 0
    }

    private fun scheduleIdleStopIfUnused() {
        synchronized(lock) {
            if (active != null) return
            idleStop?.cancel(false)
            idleStop = scheduler.schedule({ stopIfIdle() }, IDLE_STOP_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun stopIfIdle() {
        synchronized(lock) {
            val a = active
            if (a != null && a.readers > 0) return
            stopActive("idle")
            stopSession()
        }
    }

    /** Stops everything now and deletes the downloaded data. */
    fun shutdown() {
        synchronized(lock) {
            idleStop?.cancel(false)
            stopActive("shutdown")
            stopSession()
        }
    }

    private fun stopActive(reason: String) {
        val a = active ?: return
        active = null
        statsTask?.cancel(false)
        statsTask = null
        Log.i(TAG, "stopping ${a.hash} ($reason)")
        a.close(session)
    }

    private fun stopSession() {
        val sm = session ?: return
        session = null
        runCatching { sm.stop() }
        runCatching { baseDir().deleteRecursively() }
        Log.i(TAG, "session stopped, files removed")
    }

    private fun startStats() {
        statsTask?.cancel(false)
        statsTask = scheduler.scheduleWithFixedDelay({
            synchronized(lock) { active?.logStats() }
        }, STATS_INTERVAL_MS, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun readerClosed() {
        synchronized(lock) {
            val a = active ?: return
            if (a.readers > 0) return
            idleStop?.cancel(false)
            idleStop = scheduler.schedule({ stopIfIdle() }, IDLE_STOP_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** One torrent file being streamed. Shared by readers; piece scheduling follows the latest read. */
    internal class Active(
        val hash: String,
        val handle: TorrentHandle,
        val info: TorrentInfo,
        val fileIdx: Int,
        val dir: File,
    ) {
        @Volatile var readers = 0
        private val fs = info.files()
        private val pieceLen = info.pieceLength()
        val fileOffset = fs.fileOffset(fileIdx)
        val fileSize = fs.fileSize(fileIdx)
        val file = File(dir, fs.filePath(fileIdx))
        private val firstPiece = (fileOffset / pieceLen).toInt()
        private val lastPiece = ((fileOffset + fileSize - 1) / pieceLen).toInt()
        private val aheadPieces = maxOf(8, (WINDOW_AHEAD_BYTES / pieceLen).toInt())
        private val urgentPieces = maxOf(3, (URGENT_BYTES / pieceLen).toInt())
        private var winStart = -1
        private var winEnd = -2
        private var lastUrgentPiece = -1
        private var lastUrgentAt = 0L
        @Volatile var lastReadPos = 0L

        init {
            // Nothing downloads except what the player asks for.
            handle.prioritizePieces(Array(info.numPieces()) { Priority.IGNORE })
            val tailStart = maxOf(firstPiece, ((fileOffset + fileSize - TAIL_BYTES) / pieceLen).toInt())
            for (p in tailStart..lastPiece) handle.piecePriority(p, Priority.DEFAULT)
            for (k in 0 until urgentPieces) {
                // Headers (and mkv cues / mp4 moov at the tail) are needed before the first frame.
                if (firstPiece + k <= lastPiece) handle.setPieceDeadline(firstPiece + k, 300 * (k + 1))
            }
            for (p in tailStart..lastPiece) handle.setPieceDeadline(p, 3_000 + 300 * (p - tailStart))
            Log.i(TAG, "pieces ${info.numPieces()}, file pieces $firstPiece..$lastPiece, window ${aheadPieces} pieces")
        }

        fun newReader(): Reader {
            readers++
            return Reader(this)
        }

        fun pieceAt(position: Long): Int = ((fileOffset + position) / pieceLen).toInt()

        fun pieceEndPosition(piece: Int): Long = (piece + 1).toLong() * pieceLen - fileOffset

        /** Keep the download window and urgent deadlines around [piece]. */
        @Synchronized
        fun schedule(piece: Int, waiting: Boolean) {
            val desiredEnd = minOf(lastPiece, piece + aheadPieces)
            if (piece < winStart || piece > winEnd + 1) {
                // Seek: drop the old window and restart sequential download from here.
                for (p in maxOf(winStart, firstPiece)..winEnd) {
                    if (!handle.havePiece(p) && p < lastPiece - (TAIL_BYTES / pieceLen).toInt()) handle.piecePriority(p, Priority.IGNORE)
                }
                handle.clearPieceDeadlines()
                winStart = piece
                winEnd = piece - 1
                lastUrgentPiece = -1
                runCatching { handle.setSequentialRange(piece) }
            }
            if (desiredEnd > winEnd) {
                for (p in (winEnd + 1)..desiredEnd) if (!handle.havePiece(p)) handle.piecePriority(p, Priority.DEFAULT)
                winEnd = desiredEnd
            }
            val now = System.currentTimeMillis()
            if (piece != lastUrgentPiece || (waiting && now - lastUrgentAt > 2_500)) {
                lastUrgentPiece = piece
                lastUrgentAt = now
                for (k in 0 until urgentPieces) {
                    val p = piece + k
                    if (p <= lastPiece && !handle.havePiece(p)) handle.setPieceDeadline(p, 250 * (k + 1))
                }
            }
        }

        fun logStats() {
            val st = runCatching { handle.status() }.getOrNull() ?: return
            val have = (firstPiece..lastPiece).count { handle.havePiece(it) }
            Log.i(
                TAG,
                "state=${st.state()} peers=${st.numPeers()} seeds=${st.numSeeds()} down=${st.downloadRate() / 1024} KB/s " +
                    "pieces=$have/${lastPiece - firstPiece + 1} readPos=${lastReadPos shr 20} MB dht=${session?.dhtNodes()}",
            )
        }

        fun status() = runCatching { handle.status() }.getOrNull()

        fun discard() = close(session)

        fun close(sm: SessionManager?) {
            runCatching { sm?.remove(handle, SessionHandle.DELETE_FILES) }
            runCatching { dir.deleteRecursively() }
        }
    }

    /** Sequential reader for one open of the data source. */
    class Reader internal constructor(private val a: Active) {
        private var raf: RandomAccessFile? = null
        @Volatile private var closed = false
        val fileSize: Long get() = a.fileSize
        val displayName: String get() = a.info.name()

        /** Blocks until the piece holding [position] is downloaded. Returns bytes read or -1 at the end. */
        @Throws(IOException::class)
        fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            if (position >= a.fileSize) return -1
            val piece = a.pieceAt(position)
            waitForPiece(piece)
            val n = minOf(length.toLong(), a.pieceEndPosition(piece) - position, a.fileSize - position).toInt()
            val f = raf ?: openFile().also { raf = it }
            f.seek(position)
            val read = f.read(target, offset, n)
            a.lastReadPos = position
            return read
        }

        /** Waits until the head of the file is on disk. */
        fun awaitStart(timeoutMs: Long): Boolean {
            val piece = a.pieceAt(0)
            val end = System.currentTimeMillis() + timeoutMs
            a.schedule(piece, waiting = false)
            while (!a.handle.havePiece(piece)) {
                if (closed || System.currentTimeMillis() > end) return false
                try { Thread.sleep(150) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return false }
                a.schedule(piece, waiting = true)
            }
            return true
        }

        private fun openFile(): RandomAccessFile {
            // libtorrent creates the file on the first write; the first piece is already here.
            repeat(50) {
                if (a.file.exists()) return RandomAccessFile(a.file, "r")
                Thread.sleep(100)
            }
            throw TorrentUnavailableException("Torrent file missing")
        }

        private fun waitForPiece(piece: Int) {
            a.schedule(piece, waiting = false)
            if (a.handle.havePiece(piece)) return
            val started = System.currentTimeMillis()
            var lastProgressAt = started
            var lastDone = -1L
            while (!a.handle.havePiece(piece)) {
                if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("torrent read cancelled")
                try {
                    Thread.sleep(120)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("torrent read cancelled")
                }
                a.schedule(piece, waiting = true)
                val st = a.status()
                val done = st?.totalDone() ?: -1L
                val now = System.currentTimeMillis()
                if (done != lastDone) {
                    lastDone = done
                    lastProgressAt = now
                }
                if (now - lastProgressAt > STALL_TIMEOUT_MS) {
                    Log.w(TAG, "stalled waiting for piece $piece (peers=${st?.numPeers()})")
                    throw TorrentUnavailableException("Torrent stalled: no data from peers")
                }
            }
            val waited = System.currentTimeMillis() - started
            if (waited > 1_500) Log.i(TAG, "waited ${waited} ms for piece $piece")
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { raf?.close() }
            raf = null
            synchronized(lock) { a.readers-- }
            readerClosed()
        }
    }
}
