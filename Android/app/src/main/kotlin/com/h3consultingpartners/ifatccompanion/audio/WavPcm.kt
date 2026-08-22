package com.h3consultingpartners.ifatccompanion.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reading for the text-to-speech radio-effect path.
 *
 * iOS renders an utterance straight to PCM buffers with `AVSpeechSynthesizer.write`.
 * Android's nearest equivalent is `TextToSpeech.synthesizeToFile`, which writes a WAV,
 * so the pipeline gains one step: synthesize to a cache file, read the PCM back, run
 * the same saturation and comms-band filter over it, and play it through an
 * `AudioTrack`. Everything downstream of the decode is identical to iOS.
 *
 * Android's TTS engines write 16-bit PCM, mono or stereo, at whatever rate the voice
 * uses; this handles that shape and ignores any extra chunks (`LIST`, `fact`) an engine
 * may include.
 */
object WavPcm {

    data class Decoded(
        /** Interleaved samples normalised to −1…1. */
        val samples: FloatArray,
        val sampleRate: Int,
        val channelCount: Int,
    ) {
        val frameCount: Int get() = if (channelCount == 0) 0 else samples.size / channelCount

        override fun equals(other: Any?) = other is Decoded &&
            samples.contentEquals(other.samples) &&
            sampleRate == other.sampleRate &&
            channelCount == other.channelCount

        override fun hashCode() =
            (samples.contentHashCode() * 31 + sampleRate) * 31 + channelCount
    }

    /** Decode a WAV file, or null when it is not a shape we understand. */
    fun decode(file: File): Decoded? = runCatching { decode(file.readBytes()) }.getOrNull()

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 44) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (readTag(buffer, 0) != "RIFF" || readTag(buffer, 8) != "WAVE") return null

        var offset = 12
        var sampleRate = 0
        var channelCount = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLength = 0

        while (offset + 8 <= bytes.size) {
            val chunkId = readTag(buffer, offset)
            val chunkSize = buffer.getInt(offset + 4)
            if (chunkSize < 0) return null
            val body = offset + 8
            when (chunkId) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    channelCount = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                    bitsPerSample = buffer.getShort(body + 14).toInt()
                }

                "data" -> {
                    dataOffset = body
                    dataLength = minOf(chunkSize, bytes.size - body)
                }
            }
            // Chunks are word-aligned: an odd size is followed by a pad byte.
            offset = body + chunkSize + (chunkSize and 1)
        }

        if (dataOffset < 0 || sampleRate <= 0 || channelCount <= 0) return null
        if (bitsPerSample != 16) return null

        val sampleCount = dataLength / 2
        val samples = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            samples[i] = buffer.getShort(dataOffset + i * 2) / 32_768f
        }
        return Decoded(samples, sampleRate, channelCount)
    }

    /**
     * Down-mix to mono and resample (linear) to [targetRate]. The radio chain runs at a
     * single fixed rate so the bed, the bursts and the voice can share one track, exactly
     * as the iOS graph converts everything to one common format first.
     */
    fun toMono(decoded: Decoded, targetRate: Int): FloatArray {
        val frames = decoded.frameCount
        if (frames == 0) return FloatArray(0)

        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until decoded.channelCount) {
                sum += decoded.samples[frame * decoded.channelCount + channel]
            }
            mono[frame] = sum / decoded.channelCount
        }
        if (decoded.sampleRate == targetRate) return mono

        val ratio = targetRate.toDouble() / decoded.sampleRate
        val outFrames = (frames * ratio).toInt()
        if (outFrames <= 0) return FloatArray(0)
        val out = FloatArray(outFrames)
        for (i in 0 until outFrames) {
            val source = i / ratio
            val index = source.toInt()
            val fraction = (source - index).toFloat()
            val a = mono[index.coerceIn(0, frames - 1)]
            val b = mono[(index + 1).coerceIn(0, frames - 1)]
            out[i] = a + (b - a) * fraction
        }
        return out
    }

    private fun readTag(buffer: ByteBuffer, offset: Int): String =
        String(
            byteArrayOf(
                buffer.get(offset),
                buffer.get(offset + 1),
                buffer.get(offset + 2),
                buffer.get(offset + 3),
            ),
            Charsets.US_ASCII,
        )
}
