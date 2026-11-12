package com.streamflixreborn.streamflix.fragments.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Runs decoded audio through [AudioLeveler]. Stays active when off, so it can be switched on mid-film. */
@OptIn(UnstableApi::class)
class LevelingAudioProcessor : BaseAudioProcessor() {

    private var leveler: AudioLeveler? = null
    private var levelerFormat = AudioFormat.NOT_SET
    private var samples = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: androidx.media3.common.audio.AudioProcessor.StreamMetadata) {
        // A seek keeps the current gain, so the sound doesn't jump; a new format starts over.
        val format = inputAudioFormat
        if (format.channelCount > 0 && format != levelerFormat) {
            leveler = AudioLeveler(format.sampleRate, format.channelCount, AudioLeveling.warm)
            levelerFormat = format
        }
    }

    override fun onReset() {
        leveler = null
        levelerFormat = AudioFormat.NOT_SET
        samples = ShortArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val count = bytes / 2
        if (samples.size < count) samples = ShortArray(count)

        inputBuffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().get(samples, 0, count)
        inputBuffer.position(inputBuffer.limit())

        leveler?.let {
            it.process(samples, count, AudioLeveling.level)
            AudioLeveling.warm = it.state
        }

        // The view leaves the buffer at position 0, ready to be read.
        replaceOutputBuffer(bytes).asShortBuffer().put(samples, 0, count)
    }
}
