package com.streamflixreborn.streamflix.fragments.player.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

class AudioLevelerTest {

    private val rate = 48_000

    /** Stereo 440 Hz sine, [seconds] long, peaking at [db] dBFS. */
    private fun tone(db: Double, seconds: Double = 2.0): ShortArray {
        val amplitude = 32767 * 10.0.pow(db / 20)
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i -> (amplitude * sin(2 * PI * 440 * (i / 2) / rate)).toInt().toShort() }
    }

    private fun peakDb(samples: ShortArray, from: Int = 0) =
        20 * log10(samples.drop(from).maxOf { abs(it.toInt()) }.coerceAtLeast(1) / 32768.0)

    /** Level of [samples] after the leveler has settled on them. */
    private fun settled(db: Double, level: Int): Double {
        val audio = tone(db)
        val leveler = AudioLeveler(rate, 2)
        // Handed over in player-sized buffers.
        var start = 0
        while (start < audio.size) {
            val size = minOf(4096, audio.size - start)
            val part = audio.copyOfRange(start, start + size)
            leveler.process(part, size, level)
            part.copyInto(audio, start)
            start += size
        }
        return peakDb(audio, audio.size / 2)
    }

    @Test
    fun offLeavesTheAudioUntouched() {
        val audio = tone(-12.0)
        val copy = audio.copyOf()
        AudioLeveler(rate, 2).process(audio, audio.size, AudioLeveling.OFF)
        assertArrayEquals(copy, audio)
    }

    @Test
    fun theGapBetweenTalkingAndGunfireShrinks() {
        val original = 24.0
        var last = original
        for (level in listOf(AudioLeveling.LIGHT, AudioLeveling.MEDIUM, AudioLeveling.STRONG)) {
            val gap = settled(-3.0, level) - settled(-27.0, level)
            assertTrue("level $level leaves a gap of $gap dB", gap < last - 1.5)
            last = gap
        }
    }

    @Test
    fun quietTalkingComesUp() {
        assertTrue(settled(-30.0, AudioLeveling.MEDIUM) > -24.0)
    }

    @Test
    fun gunfireGoesDown() {
        assertTrue(settled(-3.0, AudioLeveling.MEDIUM) < -6.0)
    }

    @Test
    fun aSuddenFullScaleBangAfterSilenceDoesNotClip() {
        val quiet = tone(-40.0, 1.0)
        val bang = ShortArray(9_600) { i -> if (i % 2 == 0) 32767 else -32768 }
        val audio = quiet + bang
        AudioLeveler(rate, 2).process(audio, audio.size, AudioLeveling.STRONG)
        assertTrue(peakDb(audio) <= -0.9)
    }

    @Test
    fun hissIsNotBoosted() {
        assertEquals(-70.0, settled(-70.0, AudioLeveling.STRONG), 1.0)
    }

    @Test
    fun switchingOffFadesBackToUntouchedAudio() {
        val leveler = AudioLeveler(rate, 2)
        val first = tone(-30.0)
        leveler.process(first, first.size, AudioLeveling.STRONG)
        val fading = tone(-30.0, 4.0)
        leveler.process(fading, fading.size, AudioLeveling.OFF)
        val later = tone(-30.0, 0.5)
        val copy = later.copyOf()
        leveler.process(later, later.size, AudioLeveling.OFF)
        assertArrayEquals(copy, later)
    }

    @Test
    fun aShortLastBufferIsHandled() {
        val audio = tone(-20.0, 0.001)
        AudioLeveler(rate, 2).process(audio, audio.size - 2, AudioLeveling.MEDIUM)
    }

    @Test
    fun strongLeavesOnlyAFewDbBetweenTalkingAndGunfire() {
        val gap = settled(-3.0, AudioLeveling.STRONG) - settled(-27.0, AudioLeveling.STRONG)
        assertTrue("gap $gap dB", gap < 4.0)
    }

    @Test
    fun aLevelerThatTakesOverStartsFromTheGainInForce() {
        val first = AudioLeveler(rate, 2)
        val talk = tone(-30.0)
        first.process(talk, talk.size, AudioLeveling.MEDIUM)
        val warmed = AudioLeveler(rate, 2, first.state)
        val next = tone(-30.0, 0.05)
        warmed.process(next, next.size, AudioLeveling.MEDIUM)
        // Right from the first samples the boost is already there (a cold start begins at 0 dB).
        assertTrue(peakDb(next) > -22.0)
    }
}
