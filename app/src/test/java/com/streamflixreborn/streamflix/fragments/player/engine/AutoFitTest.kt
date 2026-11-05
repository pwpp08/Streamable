package com.streamflixreborn.streamflix.fragments.player.engine

import com.streamflixreborn.streamflix.nextui.model.VideoContentScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFitTest {
    private val w = 256
    private val h = 144
    private val black = 0xFF000000.toInt()
    private val bright = 0xFFB0B0B0.toInt()

    /** A frame of [w] x [h] where only the rectangle inside the given bars is lit. */
    private fun frame(topBottom: Int = 0, leftRight: Int = 0, topOnly: Int = -1, litFill: Int = bright): IntArray {
        val px = IntArray(w * h) { black }
        val top = if (topOnly >= 0) topOnly else topBottom
        for (y in top until h - topBottom) for (x in leftRight until w - leftRight) {
            // Texture, so rows are not uniform.
            px[y * w + x] = if ((x + y) % 7 == 0) 0xFF404040.toInt() else litFill
        }
        return px
    }

    @Test
    fun letterboxedFilmIsMeasured() {
        // 2.39:1 inside a 16:9 frame: picture height = 1.778/2.39 = 74.4% -> bars of 12.8% each.
        val bars = BarDetector.analyze(frame(topBottom = 18), w, h, 1920, 1080)
        assertNotNull(bars)
        assertEquals(0.125f, bars!!.vertical, 0.015f)
        assertEquals(0f, bars.horizontal, 0.0001f)
    }

    @Test
    fun barsOnAllFourSidesAreMeasured() {
        // The first screenshot: picture inset on every side.
        val bars = BarDetector.analyze(frame(topBottom = 18, leftRight = 0), w, h, 1920, 1080)
        assertNotNull(bars)
        val both = BarDetector.analyze(frame(topBottom = 12, leftRight = 14), w, h, 1920, 1080)
        // 228 x 120 content = 1.9:1, a standard ratio.
        assertNotNull(both)
        assertTrue(both!!.vertical > 0.07f && both.horizontal > 0.05f)
    }

    @Test
    fun aFrameWithoutBarsHasNone() {
        val bars = BarDetector.analyze(frame(), w, h, 1920, 1080)
        assertEquals(BarDetector.Bars(0f, 0f), bars)
    }

    @Test
    fun aNearlyBlackFrameIsIgnored() {
        assertNull(BarDetector.analyze(IntArray(w * h) { black }, w, h, 1920, 1080))
    }

    @Test
    fun aDarkSceneWithOneSidedShadowDoesNotOverCrop() {
        // Black only along the top; the thinner (bottom) side wins, so nothing is cropped.
        val bars = BarDetector.analyze(frame(topBottom = 0, topOnly = 40), w, h, 1920, 1080)
        assertEquals(BarDetector.Bars(0f, 0f), bars)
    }

    @Test
    fun aLitAreaThatIsNotAStandardRatioIsNotTrusted() {
        // 60 black rows top and bottom: 256 x 24 content is far from any film ratio.
        assertNull(BarDetector.analyze(frame(topBottom = 60), w, h, 1920, 1080))
    }

    @Test
    fun estimatorWaitsForEnoughSamplesAndIgnoresOutliers() {
        val estimator = BarEstimator(capacity = 40, minSamples = 5)
        repeat(4) { estimator.add(BarDetector.Bars(0.125f, 0f)) }
        assertNull(estimator.estimate())
        repeat(20) { estimator.add(BarDetector.Bars(0.125f, 0f)) }
        // A few credit frames with thin bars and a few dark frames with thick ones.
        repeat(3) { estimator.add(BarDetector.Bars(0f, 0f)) }
        repeat(3) { estimator.add(BarDetector.Bars(0.3f, 0f)) }
        assertEquals(0.125f, estimator.estimate()!!.vertical, 0.0001f)
    }

    @Test
    fun autoFillsTheScreenWithTheRealPicture() {
        // 20:9 phone showing a 16:9 video that contains a 2.39:1 picture: the frame is fitted to the
        // height, the picture is 74% of it, so it can be scaled until it spans the width.
        val view = 2400f to 1080f
        val frame = 1920f to 1080f
        val bars = BarDetector.Bars(0.128f, 0f)
        val z = FitMath.zoom(VideoContentScale.AUTO, view.first, view.second, frame.first, frame.second, bars)
        assertEquals(1.25f, z, 0.02f)
    }

    @Test
    fun cropIsBasedOnThePictureNotTheFrame() {
        val view = 1920f to 1080f
        val frame = 1920f to 1080f
        val bars = BarDetector.Bars(0.128f, 0f)
        // Without bar detection crop would be 1.0 (the frame already fills the view). With it, the
        // 2.39:1 picture is widened to cover the 16:9 view.
        assertEquals(1f, FitMath.zoom(VideoContentScale.CROP, view.first, view.second, frame.first, frame.second, null), 0.001f)
        assertEquals(1.344f, FitMath.zoom(VideoContentScale.CROP, view.first, view.second, frame.first, frame.second, bars), 0.02f)
    }

    @Test
    fun bestFitAndStretchAreLeftAlone() {
        val bars = BarDetector.Bars(0.2f, 0.1f)
        assertEquals(1f, FitMath.zoom(VideoContentScale.BEST_FIT, 2400f, 1080f, 1920f, 1080f, bars), 0f)
        assertEquals(1f, FitMath.zoom(VideoContentScale.STRETCH, 2400f, 1080f, 1920f, 1080f, bars), 0f)
    }
}
