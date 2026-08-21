package com.h3consultingpartners.ifatccompanion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.h3consultingpartners.ifatccompanion.core.audio.RadioAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The radio itself: the static bed, the mic-key bursts, and processed speech playback.
 *
 * The iOS build wires an `AVAudioEngine` graph — a source node generating static into a
 * bed mixer, a player node for speech through a band-pass EQ, and a third player for the
 * squelch bursts. Android has no equivalent node graph, so the mixing happens here in
 * floats. One render loop owns the main `AudioTrack` and is the only thing that writes to
 * it: each block it generates the static bed, mixes in whatever transmission is playing,
 * and writes once. A second, separate track carries the mic-key bursts, which must be
 * able to fire on top of a transmission and are never ducked — the same separation the
 * iOS graph gets from its third player node.
 *
 * All of the *sound* — the saturation curve, the comms band, the burst envelopes and the
 * mixer levels — comes from `RadioAudio` in :core, so the tuning the iOS build arrived at
 * is carried across exactly rather than re-tuned against a different audio stack.
 */
class RadioAudioEngine(private val scope: CoroutineScope) {

    private val sampleRate = RadioAudio.SAMPLE_RATE

    private val audioAttributes = AudioAttributes.Builder()
        // The spoken calls are the app's content: USAGE_MEDIA is what lets them play over
        // a Bluetooth headset and be governed by the media volume the pilot expects.
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    /** One transmission waiting to be mixed into the render loop's output. */
    private class Transmission(val samples: FloatArray) {
        var offset = 0
        val finished = CompletableDeferred<Unit>()
    }

    private val pending = ConcurrentLinkedQueue<Transmission>()

    private var renderTrack: AudioTrack? = null
    private var burstTrack: AudioTrack? = null
    private var renderJob: Job? = null

    private val running = AtomicBoolean(false)

    @Volatile private var ducked = false
    @Volatile private var transmitting = false
    @Volatile private var chatterLevel = 0f
    @Volatile private var bedGain = 0f

    val isRunning: Boolean get() = running.get()

    /**
     * Start the engine. Safe to call repeatedly. The caller takes audio focus first — the
     * flight service does that once for the whole session.
     */
    fun start() {
        if (running.getAndSet(true)) return

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(BLOCK_FRAMES * BYTES_PER_FLOAT * 4)

        renderTrack = buildTrack(minBuffer).also { it.play() }
        burstTrack = buildTrack(minBuffer).also { it.play() }
        applyLevels()

        renderJob = scope.launch(Dispatchers.Default) {
            var generator = RadioAudio.BedGenerator()
            val block = FloatArray(BLOCK_FRAMES)
            var current: Transmission? = null

            while (isActive && running.get()) {
                generator = RadioAudio.fillStaticBed(block, generator, bedGain)

                if (current == null) current = pending.poll()
                val playing = current
                if (playing != null) {
                    val remaining = playing.samples.size - playing.offset
                    val count = minOf(BLOCK_FRAMES, remaining)
                    for (i in 0 until count) {
                        block[i] = (block[i] + playing.samples[playing.offset + i]).coerceIn(-1f, 1f)
                    }
                    playing.offset += count
                    if (playing.offset >= playing.samples.size) {
                        playing.finished.complete(Unit)
                        current = null
                    }
                }

                // A blocking write paces the loop against the track's own consumption, so
                // audio is generated exactly as fast as it is heard — the Android
                // counterpart of the iOS source node being pulled by the render thread.
                val written = renderTrack?.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                if (written == null || written < 0) break
            }

            // Never leave a caller awaiting a transmission that will now never play.
            current?.finished?.complete(Unit)
            while (true) (pending.poll() ?: break).finished.complete(Unit)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        renderJob?.cancel()
        renderJob = null
        renderTrack?.release()
        renderTrack = null
        burstTrack?.release()
        burstTrack = null
        while (true) (pending.poll() ?: break).finished.complete(Unit)
    }

    /** The pilot's chatter volume setting, 0…1. */
    fun setChatterLevel(level: Float) {
        chatterLevel = level.coerceIn(0f, 1f)
        applyLevels()
    }

    /** Duck the chatter (voice + bed) under a real ATC call, or restore it. */
    fun setDucked(value: Boolean) {
        ducked = value
        applyLevels()
    }

    /**
     * Raise the static bed while a chatter transmission is playing, and drop it back to
     * near-silent between calls.
     */
    fun setTransmitting(value: Boolean) {
        transmitting = value
        applyLevels()
    }

    /** The current chatter speech level, honouring ducking. */
    @Volatile
    var chatterSpeechLevel = 0f
        private set

    private fun applyLevels() {
        val levels = RadioAudio.chatterLevels(chatterLevel, ducked, transmitting)
        bedGain = levels.bed
        chatterSpeechLevel = levels.voice
    }

    /** Fire the dull PTT key-down "thump" (pilot presses the mic key). */
    fun playKeyClick() = playBurst(keyClick)

    /** Fire the PTT-release squelch tail (pilot un-keys — the receiver-return burst). */
    fun playSquelchTail() = playBurst(squelchTail)

    private fun playBurst(samples: FloatArray) {
        if (!running.get() || samples.isEmpty()) return
        val track = burstTrack ?: return
        val scaled = FloatArray(samples.size) { samples[it] * RadioAudio.SQUELCH_MIXER_LEVEL }
        scope.launch(Dispatchers.Default) {
            track.write(scaled, 0, scaled.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    /**
     * Play a rendered transmission through the radio chain — saturation, then the comms
     * band — and suspend until the render loop has finished playing it.
     *
     * [volume] is the caller's level for this call. The main ATC and pilot calls play at
     * the pilot's voice volume and are never ducked (this is the real call, not the
     * ambient chatter); chatter passes [chatterSpeechLevel] instead.
     */
    suspend fun playProcessed(samples: FloatArray, volume: Float) {
        if (!running.get() || samples.isEmpty()) return

        val processed = samples.copyOf()
        RadioAudio.applySaturation(processed)
        RadioAudio.applyCommsBand(processed, sampleRate)
        val level = volume.coerceIn(0f, 1f)
        for (i in processed.indices) processed[i] *= level

        val transmission = Transmission(processed)
        pending.add(transmission)
        transmission.finished.await()
    }

    private fun buildTrack(bufferSize: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(audioAttributes)
        .setAudioFormat(audioFormat)
        .setBufferSizeInBytes(bufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val keyClick: FloatArray by lazy { RadioAudio.keyClickSamples(sampleRate) }
    private val squelchTail: FloatArray by lazy { RadioAudio.squelchTailSamples(sampleRate) }

    companion object {
        /**
         * ~23 ms of audio per write. Small enough that a level change (ducking under a
         * controller call) is heard immediately, large enough not to spin the writer.
         */
        private const val BLOCK_FRAMES = 1024
        private const val BYTES_PER_FLOAT = 4
    }
}
