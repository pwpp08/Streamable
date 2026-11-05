package com.streamflixreborn.streamflix.desktop.player

import android.util.Log
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Finds libvlc (bundled with the app, a folder set in Settings, or an installed VLC) and owns
 * the one MediaPlayerFactory.
 */
object VlcEngine {
    private const val TAG = "VlcEngine"

    /** The VLC copied into the app by the release build, if any. */
    val bundledDir: File?
        get() = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }?.takeIf { it.isDirectory }

    @Volatile var error: String? = null
        private set

    val factory: MediaPlayerFactory? by lazy {
        runCatching {
            val found = NativeDiscovery().discover()
            Log.i(TAG, "libvlc discovered=$found")
            if (!found) error("VLC not found")
            val extra = System.getProperty("streamflix.vlcargs")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
            MediaPlayerFactory(
                *extra.toTypedArray(),
                "--no-video-title-show",
                "--no-snapshot-preview",
                "--no-osd",
                "--quiet",
                "--avcodec-hw=$hwDecoder",
                "--http-reconnect",
                "--network-caching=2500",
                "--sub-text-scale=110",
                "--freetype-rel-fontsize=16",
                "--freetype-outline-thickness=4",
            )
        }.onFailure { error = it.message ?: it.toString(); Log.e(TAG, "VLC unavailable", it) }.getOrNull()
    }

    val available: Boolean get() = factory != null

    /** Hardware decoding with copy-back into memory (4K in software is too slow). */
    val hwDecoder: String = System.getProperty("streamflix.hw") ?: when {
        System.getProperty("os.name").orEmpty().lowercase().contains("win") -> "dxva2"
        System.getProperty("os.name").orEmpty().lowercase().contains("mac") -> "videotoolbox"
        else -> "any"
    }
}

/** Lets vlcj look in the bundled VLC and the folder chosen in Settings before the usual places. */
class StreamFlixVlcDirectories : DiscoveryDirectoryProvider {
    override fun priority(): Int = 100

    override fun directories(): Array<String> = listOfNotNull(
        // Windows bundle: vlc/libvlc.dll with vlc/plugins next to it.
        VlcEngine.bundledDir?.path,
        // macOS bundle: vlc/lib/libvlc.dylib, plugins in vlc/plugins (vlcj looks in ../plugins).
        VlcEngine.bundledDir?.let { File(it, "lib").path },
        UserPreferences.vlcPath.takeIf { it.isNotBlank() },
    ).filter { File(it).isDirectory }.toTypedArray()

    override fun supported(): Boolean = true
}

/** One decoded picture: the Y, U and V planes as greyscale images (see [YuvRenderer]). */
class YuvFrame(val y: Image, val u: Image, val v: Image, val width: Int, val height: Int)

/**
 * VLC decodes into memory and Compose draws it. Frames stay in YUV 4:2:0 (a third of the bytes
 * of RGB, and no per-pixel conversion on the CPU); the GPU turns them into colour while drawing.
 * Three frame slots, so VLC never writes into the one on screen. [version] bumps per frame.
 */
class VideoFrames : BufferFormatCallback, RenderCallback {
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    val version = AtomicLong()

    private val lock = Any()
    private var pitches = IntArray(3)
    private var lines = IntArray(3)
    private val slots = Array(3) { arrayOfNulls<ByteBuffer>(3) }
    private var ready = -1
    private var showing = -1
    private var current: YuvFrame? = null
    private val pixmaps = mutableListOf<Pixmap>()
    private var currentIndex = -1

    private fun align(v: Int, a: Int) = (v + a - 1) / a * a

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        synchronized(lock) {
            width = sourceWidth
            height = sourceHeight
            val cw = (sourceWidth + 1) / 2
            val ch = (sourceHeight + 1) / 2
            pitches = intArrayOf(align(sourceWidth, 32), align(cw, 32), align(cw, 32))
            lines = intArrayOf(align(sourceHeight, 16), align(ch, 16), align(ch, 16))
            for (slot in slots) for (p in 0..2) slot[p] = ByteBuffer.allocateDirect(pitches[p] * lines[p])
            releaseCurrent()
            ready = -1
            showing = -1
        }
        return BufferFormat("I420", sourceWidth, sourceHeight, pitches.copyOf(), lines.copyOf())
    }

    override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit

    override fun lock(mediaPlayer: MediaPlayer?) = Unit

    override fun unlock(mediaPlayer: MediaPlayer?) = Unit

    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
        if (nativeBuffers.size < 3) return
        val target: Int
        synchronized(lock) { target = slots.indices.first { it != ready && it != showing } }
        val slot = slots[target]
        for (p in 0..2) {
            val src = nativeBuffers[p]
            val dst = slot[p] ?: return
            src.rewind()
            dst.clear()
            if (src.remaining() > dst.remaining()) return
            dst.put(src)
        }
        synchronized(lock) { ready = target }
        version.incrementAndGet()
    }

    private fun releaseCurrent() {
        current?.let { it.y.close(); it.u.close(); it.v.close() }
        pixmaps.forEach { it.close() }
        pixmaps.clear()
        current = null
        currentIndex = -1
    }

    /** The newest frame (wrapping the buffers, no copy). Call from the UI thread. */
    fun latest(): YuvFrame? {
        synchronized(lock) {
            if (ready >= 0) {
                showing = ready
                ready = -1
            }
            val idx = showing
            if (idx < 0 || (idx == currentIndex && current != null)) return current
            releaseCurrent()
            val slot = slots[idx]
            val cw = (width + 1) / 2
            val ch = (height + 1) / 2
            fun plane(p: Int, w: Int, h: Int): Image {
                val address = Pointer.nativeValue(Native.getDirectBufferPointer(slot[p]!!))
                val pixmap = Pixmap.make(ImageInfo(w, h, ColorType.GRAY_8, ColorAlphaType.OPAQUE), address, pitches[p])
                pixmaps += pixmap
                return Image.makeFromPixmap(pixmap)
            }
            current = YuvFrame(plane(0, width, height), plane(1, cw, ch), plane(2, cw, ch), width, height)
            currentIndex = idx
            return current
        }
    }

    fun clear() {
        synchronized(lock) {
            releaseCurrent()
            ready = -1
            showing = -1
        }
    }
}

/** Draws a [YuvFrame] with a small SkSL shader (BT.709 for HD and up, BT.601 below). */
object YuvRenderer {
    private val effect by lazy {
        RuntimeEffect.makeForShader(
            """
            uniform shader yTex;
            uniform shader uTex;
            uniform shader vTex;
            uniform float bt709;
            half4 main(float2 p) {
                float y = (yTex.eval(p).r - 0.0627) * 1.1644;
                float u = uTex.eval(p * 0.5).r - 0.5;
                float v = vTex.eval(p * 0.5).r - 0.5;
                float3 rgb = bt709 > 0.5
                    ? float3(y + 1.7927 * v, y - 0.2132 * u - 0.5329 * v, y + 2.1124 * u)
                    : float3(y + 1.5960 * v, y - 0.3918 * u - 0.8130 * v, y + 2.0172 * u);
                return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);
            }
            """.trimIndent(),
        )
    }
    private val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

    fun draw(canvas: Canvas, frame: YuvFrame, dst: Rect) {
        val sx = dst.width / frame.width
        val sy = dst.height / frame.height
        val matrix = Matrix33.makeTranslate(dst.left, dst.top).makeConcat(Matrix33.makeScale(sx, sy))
        val builder = RuntimeShaderBuilder(effect)
        builder.uniform("bt709", if (frame.height >= 720) 1f else 0f)
        builder.child("yTex", frame.y.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null))
        builder.child("uTex", frame.u.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null))
        builder.child("vTex", frame.v.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null))
        Paint().use { paint ->
            paint.shader = builder.makeShader(matrix)
            canvas.drawRect(dst, paint)
        }
    }
}
