package com.streamflixreborn.streamflix.torrent

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.FileNotFoundException
import java.io.IOException

/** Reads a `torrent://` URI through [TorrentEngine]; blocks (player buffers) until pieces arrive. */
@OptIn(UnstableApi::class)
class TorrentDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var reader: TorrentEngine.Reader? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val spec = TorrentSpec.parse(dataSpec.uri.toString()) ?: throw FileNotFoundException("bad torrent uri")
        val r = TorrentEngine.open(spec)
        reader = r
        position = dataSpec.position
        if (position > r.fileSize) {
            close()
            throw FileNotFoundException("position out of range")
        }
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.fileSize - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: throw IOException("not open")
        val n = r.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        reader?.close()
        reader = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** Sends `torrent://` URIs to [TorrentDataSource] and everything else to [network]. */
@OptIn(UnstableApi::class)
class TorrentAwareDataSource(
    private val network: DataSource,
    private val torrent: DataSource = TorrentDataSource(),
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        network.addTransferListener(transferListener)
        torrent.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (TorrentSpec.isTorrentUri(dataSpec.uri.toString())) torrent else network
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (current ?: throw IOException("not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = current ?: return
        current = null
        source.close()
    }
}
