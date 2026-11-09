package com.streamflixreborn.streamflix.fragments.player.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The strength the player uses right now; read on the audio thread, so a change applies at once. */
object AudioLeveling {
    const val OFF = 0
    const val LIGHT = 1
    const val MEDIUM = 2
    const val STRONG = 3

    @Volatile
    var level: Int = OFF

    /** What the playing leveler had settled on, so a player taking over starts from the same gain. */
    @Volatile
    var warm: AudioLeveler.Warm? = null
}

/**
 * Evens out film sound: quiet talking is raised and loud scenes are pulled down, so the volume
 * doesn't need riding. One gain follows the loudest channel, which keeps the stereo image, and
 * there is no look-ahead, so picture and sound stay in sync. A limiter catches what gets past it.
 */
class AudioLeveler(sampleRate: Int, private val channels: Int, warm: Warm? = null) {

    /** The state worth carrying to another leveler: the gain in force and the loudness it was reacting to. */
    class Warm(val gainDb: Float, val power: Float)

    private class Preset(val thresholdDb: Float, val ratio: Float, val makeupDb: Float) {
        fun gainDb(levelDb: Float): Float {
            val over = levelDb - thresholdDb
            val compression = (1f / ratio - 1f) * when {
                over <= -KNEE_DB / 2 -> 0f
                over >= KNEE_DB / 2 -> over
                else -> (over + KNEE_DB / 2).let { it * it / (2 * KNEE_DB) }
            }
            // Quiet sound is raised, but hiss and room tone are not: the boost fades in over a 14 dB range.
            val t = ((levelDb - GATE_BOTTOM_DB) / GATE_RANGE_DB).coerceIn(0f, 1f)
            return compression + makeupDb * t * t * (3f - 2f * t)
        }
    }

    private fun perFrame(seconds: Float, frames: Int) = exp(-frames / (seconds * sampleRateHz))

    private val sampleRateHz = sampleRate.toFloat()
    private val levelAttack = perFrame(0.005f, 1)
    private val levelRelease = perFrame(0.12f, 1)
    private val gainAttack = perFrame(0.01f, BLOCK_FRAMES)
    private val gainRelease = perFrame(0.4f, BLOCK_FRAMES)
    private val limiterRelease = perFrame(0.05f, 1)

    private var power = warm?.power ?: 0f
    private var gainDb = warm?.gainDb ?: 0f
    private var gain = 10f.pow(gainDb / 20f)
    private var limiter = 1f

    val state: Warm get() = Warm(gainDb, power)

    /** Rewrites [count] interleaved samples of [samples] in place. */
    fun process(samples: ShortArray, count: Int, level: Int) {
        val preset = PRESETS.getOrNull(level - 1)
        // Off and settled: the audio is passed on untouched.
        if (preset == null && abs(gainDb) < 0.01f && limiter >= 0.999f) {
            gainDb = 0f
            gain = 1f
            limiter = 1f
            return
        }
        var start = 0
        while (start < count) {
            val frames = min((count - start) / channels, BLOCK_FRAMES)
            if (frames == 0) return
            processBlock(samples, start, frames, preset)
            start += frames * channels
        }
    }

    private fun processBlock(samples: ShortArray, start: Int, frames: Int, preset: Preset?) {
        var index = start
        var level = power
        for (frame in 0 until frames) {
            val peak = framePeak(samples, index)
            val now = peak * peak
            level = (if (now > level) levelAttack else levelRelease).let { it * (level - now) + now }
            index += channels
        }
        power = level

        val target = preset?.gainDb(10f * log10(max(power, SILENCE_POWER))) ?: 0f
        // The smoothing was worked out for a whole block; the short last block of a buffer is rare enough.
        gainDb = target + (if (target < gainDb) gainAttack else gainRelease) * (gainDb - target)
        val end = 10f.pow(gainDb / 20f)
        val step = (end - gain) / frames

        index = start
        for (frame in 0 until frames) {
            gain += step
            val peak = framePeak(samples, index) * gain
            limiter = if (peak * limiter > CEILING) CEILING / peak else limiterRelease * (limiter - 1f) + 1f
            val total = gain * limiter
            for (channel in 0 until channels) {
                samples[index + channel] = (samples[index + channel] * total).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            index += channels
        }
        gain = end
    }

    private fun framePeak(samples: ShortArray, index: Int): Float {
        var peak = 0
        for (channel in 0 until channels) peak = max(peak, abs(samples[index + channel].toInt()))
        return peak / 32768f
    }

    private companion object {
        /** Gain is recalculated every 32 frames (under 1 ms), which keeps this cheap on the projector. */
        const val BLOCK_FRAMES = 32
        const val KNEE_DB = 6f
        const val GATE_BOTTOM_DB = -64f
        const val GATE_RANGE_DB = 14f
        const val SILENCE_POWER = 1e-10f
        /** About -1 dB. */
        const val CEILING = 0.89f

        /** Light, medium and strong. Strong squeezes the loud and quiet scenes to within a few dB. */
        val PRESETS = listOf(
            Preset(thresholdDb = -28f, ratio = 3.5f, makeupDb = 11f),
            Preset(thresholdDb = -32f, ratio = 6f, makeupDb = 16f),
            Preset(thresholdDb = -38f, ratio = 12f, makeupDb = 24f),
        )
    }
}
