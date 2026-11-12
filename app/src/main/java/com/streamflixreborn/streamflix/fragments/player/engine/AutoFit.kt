package com.streamflixreborn.streamflix.fragments.player.engine

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import com.streamflixreborn.streamflix.nextui.model.VideoContentScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds black bars that are part of the video itself (letterbox / pillarbox baked into the file).
 * Pure functions on a small grab of the frame, so they are easy to test.
 */
object BarDetector {
    /** Bar thickness as a fraction of the frame height ([vertical]) and width ([horizontal]); symmetric. */
    data class Bars(val vertical: Float, val horizontal: Float)

    private const val LUMA_BLACK = 28
    private const val MIN_LIT_FRACTION = 0.02f
    private const val ROW_LIT_FRACTION = 0.012f
    private const val NO_BAR = 0.006f
    private const val MAX_BAR = 0.4f
    private const val ASPECT_TOLERANCE = 0.03f

    /** Aspect ratios films and video are actually mastered in. Anything else is more likely a dark scene. */
    private val STANDARD_ASPECTS = floatArrayOf(
        1.0f, 1.33f, 1.37f, 1.43f, 1.5f, 1.6f, 1.66f, 1.78f, 1.85f, 1.9f, 2.0f, 2.2f, 2.35f, 2.39f, 2.4f, 2.55f, 2.76f,
    )

    /**
     * Bars in a grab of [width] x [height] ARGB pixels of a [videoWidth] x [videoHeight] video, or null
     * when the frame cannot be trusted (nearly black, or the picture area is not a known aspect ratio).
     */
    fun analyze(pixels: IntArray, width: Int, height: Int, videoWidth: Int, videoHeight: Int): Bars? {
        if (width < 8 || height < 8 || videoWidth <= 0 || videoHeight <= 0 || pixels.size < width * height) return null

        val rowLit = IntArray(height)
        val colLit = IntArray(width)
        var lit = 0
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val p = pixels[base + x]
                val luma = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                if (luma > LUMA_BLACK) {
                    rowLit[y]++
                    colLit[x]++
                    lit++
                }
            }
        }
        if (lit < MIN_LIT_FRACTION * width * height) return null

        val rowMin = max(2, (width * ROW_LIT_FRACTION).toInt())
        val colMin = max(2, (height * ROW_LIT_FRACTION).toInt())
        val top = rowLit.indexOfFirst { it >= rowMin }
        val bottom = rowLit.indexOfLast { it >= rowMin }
        val left = colLit.indexOfFirst { it >= colMin }
        val right = colLit.indexOfLast { it >= colMin }
        if (top < 0 || left < 0 || bottom <= top || right <= left) return null

        // Only the thinner side counts: the picture stays centred and a one-sided dark scene cannot over-crop.
        var vertical = min(top, height - 1 - bottom).toFloat() / height
        var horizontal = min(left, width - 1 - right).toFloat() / width
        if (vertical > MAX_BAR || horizontal > MAX_BAR) return null
        // One grab row/column of margin: the scaled frame blurs the boundary by about that much.
        vertical = if (vertical < NO_BAR) 0f else vertical + 1f / height
        horizontal = if (horizontal < NO_BAR) 0f else horizontal + 1f / width

        if (vertical > 0f || horizontal > 0f) {
            val contentAspect = videoWidth * (1f - 2f * horizontal) / (videoHeight * (1f - 2f * vertical))
            if (!isStandard(contentAspect)) return null
        }
        return Bars(vertical, horizontal)
    }

    private fun isStandard(aspect: Float) = STANDARD_ASPECTS.any { abs(aspect - it) / it <= ASPECT_TOLERANCE }
}

/**
 * Collects bar samples and answers with a steady value. A low quantile is used: a few frames with
 * thinner bars (credits, logos) cannot switch the crop off, and a few dark frames that look like
 * thicker bars (always the larger values) cannot over-crop.
 */
class BarEstimator(private val capacity: Int = 20, private val minSamples: Int = 5) {
    private val vertical = ArrayDeque<Float>()
    private val horizontal = ArrayDeque<Float>()

    val size: Int get() = vertical.size

    fun add(bars: BarDetector.Bars) {
        if (vertical.size == capacity) {
            vertical.removeFirst()
            horizontal.removeFirst()
        }
        vertical.addLast(bars.vertical)
        horizontal.addLast(bars.horizontal)
    }

    fun clear() {
        vertical.clear()
        horizontal.clear()
    }

    /** Null until enough samples agree there is something to report. */
    fun estimate(): BarDetector.Bars? {
        if (vertical.size < minSamples) return null
        return BarDetector.Bars(quantile(vertical), quantile(horizontal))
    }

    private fun quantile(values: Collection<Float>): Float {
        val sorted = values.sorted()
        return sorted[(sorted.size * 0.25f).toInt().coerceIn(0, sorted.lastIndex)]
    }
}

/** How much the video surface is scaled, about its centre, for a content scale and the detected bars. */
object FitMath {
    private const val MAX_ZOOM = 4f

    /**
     * [viewWidth] x [viewHeight] is the player view; [frameWidth] x [frameHeight] the video as laid out
     * in it with FIT (the picture including its own bars). Auto fills the view with the real picture
     * without cutting any of it; Crop fills the view and cuts what overflows; the rest are left alone.
     */
    fun zoom(
        scale: VideoContentScale,
        viewWidth: Float,
        viewHeight: Float,
        frameWidth: Float,
        frameHeight: Float,
        bars: BarDetector.Bars?,
    ): Float {
        if (viewWidth <= 0f || viewHeight <= 0f || frameWidth <= 0f || frameHeight <= 0f) return 1f
        val contentWidth = frameWidth * (1f - 2f * (bars?.horizontal ?: 0f))
        val contentHeight = frameHeight * (1f - 2f * (bars?.vertical ?: 0f))
        val byWidth = viewWidth / contentWidth
        val byHeight = viewHeight / contentHeight
        return when (scale) {
            VideoContentScale.AUTO -> min(byWidth, byHeight)
            VideoContentScale.CROP -> max(byWidth, byHeight)
            else -> 1f
        }.coerceIn(1f, MAX_ZOOM)
    }
}

/**
 * Drives the video surface of a [PlayerView] for Auto fit / Crop: samples the frame now and then,
 * works out the baked-in bars, and scales the surface so the real picture fills the screen. Pinch zoom
 * from the controls multiplies on top. Fit and Stretch are untouched.
 */
class AutoFitController(
    private val playerView: PlayerView,
    private val scope: CoroutineScope,
    private val player: () -> Player?,
) {
    private var scale = VideoContentScale.AUTO
    private var userZoom = 1f
    private var userOffsetX = 0f
    private var userOffsetY = 0f

    private val estimator = BarEstimator()
    private var shownZoom = 1f
    private var animator: ValueAnimator? = null
    private var job: Job? = null
    private var lastAspect = 0f
    private var grab: Bitmap? = null

    /**
     * Once enough frames agree the zoom is fixed for the title: re-measuring all film long let dark
     * scenes and credits nudge the estimate, and the picture visibly zoomed in and out.
     */
    private var locked = false

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val aspect = if (videoSize.height > 0) videoSize.width.toFloat() / videoSize.height else 0f
            if (abs(aspect - lastAspect) > 0.01f) {
                lastAspect = aspect
                reset()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = reset()
    }

    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) apply(animate = false)
    }

    private var attachedPlayer: Player? = null

    fun start() {
        if (job != null) return
        // The scaled surface may be larger than the frame it sits in; the player view itself still clips it.
        var parent = playerView.videoSurfaceView?.parent as? ViewGroup
        while (parent != null) {
            parent.clipChildren = false
            parent.clipToPadding = false
            if (parent === playerView) break
            parent = parent.parent as? ViewGroup
        }
        playerView.addOnLayoutChangeListener(layoutListener)
        playerView.videoSurfaceView?.addOnLayoutChangeListener(layoutListener)
        job = scope.launch { sampleLoop() }
        apply(animate = false)
    }

    fun stop() {
        job?.cancel()
        job = null
        animator?.cancel()
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = null
        playerView.removeOnLayoutChangeListener(layoutListener)
        playerView.videoSurfaceView?.removeOnLayoutChangeListener(layoutListener)
        grab?.recycle()
        grab = null
    }

    fun setScale(newScale: VideoContentScale) {
        scale = newScale
        userZoom = 1f
        userOffsetX = 0f
        userOffsetY = 0f
        apply(animate = true)
    }

    /** Pinch zoom / pan from the controls, on top of the content scale. */
    fun setUserTransform(zoom: Float, offsetX: Float, offsetY: Float) {
        userZoom = zoom
        userOffsetX = offsetX
        userOffsetY = offsetY
        apply(animate = false)
    }

    /** Forget what was learned about the bars, e.g. for a new title. Keeps the current picture until new data arrives. */
    fun reset() {
        estimator.clear()
        locked = false
        apply(animate = true)
    }

    private fun targetZoom(): Float {
        val surface = playerView.videoSurfaceView ?: return 1f
        return FitMath.zoom(
            scale = scale,
            viewWidth = playerView.width.toFloat(),
            viewHeight = playerView.height.toFloat(),
            frameWidth = surface.width.toFloat(),
            frameHeight = surface.height.toFloat(),
            bars = estimator.estimate(),
        )
    }

    private fun apply(animate: Boolean) {
        val surface = playerView.videoSurfaceView ?: return
        val target = targetZoom()
        animator?.cancel()
        if (animate && abs(target - shownZoom) > 0.005f && surface.width > 0) {
            animator = ValueAnimator.ofFloat(shownZoom, target).apply {
                duration = 300
                addUpdateListener {
                    shownZoom = it.animatedValue as Float
                    write(surface)
                }
                start()
            }
        } else {
            shownZoom = target
            write(surface)
        }
    }

    private fun write(surface: View) {
        surface.pivotX = surface.width / 2f
        surface.pivotY = surface.height / 2f
        surface.scaleX = shownZoom * userZoom
        surface.scaleY = shownZoom * userZoom
        surface.translationX = userOffsetX
        surface.translationY = userOffsetY
    }

    private suspend fun sampleLoop() {
        var taken = 0
        while (scope.isActive) {
            delay(if (taken < FAST_SAMPLES) FAST_INTERVAL_MS else SLOW_INTERVAL_MS)
            val current = player() ?: continue
            if (attachedPlayer !== current) {
                attachedPlayer?.removeListener(playerListener)
                current.addListener(playerListener)
                attachedPlayer = current
            }
            if (scale != VideoContentScale.AUTO && scale != VideoContentScale.CROP) continue
            if (locked || !current.isPlaying) continue
            val size = current.videoSize
            val texture = playerView.videoSurfaceView as? TextureView ?: continue
            if (size.width <= 0 || size.height <= 0 || !texture.isAvailable) continue

            val bitmap = grab ?: Bitmap.createBitmap(GRAB_WIDTH, GRAB_HEIGHT, Bitmap.Config.ARGB_8888).also { grab = it }
            if (texture.getBitmap(bitmap) == null) continue
            val pixels = IntArray(GRAB_WIDTH * GRAB_HEIGHT)
            bitmap.getPixels(pixels, 0, GRAB_WIDTH, 0, 0, GRAB_WIDTH, GRAB_HEIGHT)
            val bars = withContext(Dispatchers.Default) {
                BarDetector.analyze(pixels, GRAB_WIDTH, GRAB_HEIGHT, size.width, size.height)
            } ?: continue
            taken++
            estimator.add(bars)
            // One decision, one animation: nothing moves until the samples are in.
            if (estimator.size >= LOCK_SAMPLES) {
                locked = true
                apply(animate = true)
            }
        }
    }

    private companion object {
        const val GRAB_WIDTH = 256
        const val GRAB_HEIGHT = 144
        const val FAST_SAMPLES = 12
        const val LOCK_SAMPLES = 12
        const val FAST_INTERVAL_MS = 500L
        const val SLOW_INTERVAL_MS = 1_500L
    }
}
