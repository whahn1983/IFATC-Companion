package com.h3consultingpartners.ifatccompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
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
class RadioAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * Told when the system takes audio away for good, and when it gives it back. Wired to
     * `AmbientChatterService.onInterruptionBegan/Ended` — without it those hooks, which
     * `:core` already implements and tests, would never fire on Android.
     */
    private val onInterruption: (began: Boolean) -> Unit = {},
    /**
     * Told when the audio route changes — headphones in or out, a Bluetooth headset
     * connecting. Wired to `AmbientChatterService.onAudioRouteChanged`, which bounces the
     * engine so the graph re-forms against the new device and re-applies the duck.
     */
    private val onRouteChanged: () -> Unit = {},
) {

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

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null

    /**
     * Handle the system taking audio away.
     *
     * A transient loss ducks rather than stops, which is what a radio should do under a
     * navigation prompt; a permanent loss stops and gives the focus back, because the user
     * started something else and holding focus after that is exactly the behaviour Play
     * penalises in a mediaPlayback service.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                onInterruption(true)
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> setDucked(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                setDucked(false)
                onInterruption(false)
            }
        }
    }

    /** Take audio focus. Returns false when the system refuses — then nothing is played. */
    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            // The app ducks other audio rather than pausing it: a pilot may well be
            // listening to something else, and ATC over the top is the point.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        audioManager.abandonAudioFocusRequest(request)
    }

    /**
     * Watches for the output device changing under a running graph.
     *
     * An `AudioTrack` is built against the device that was current when it was created, so
     * unplugging headphones mid-flight leaves the track writing into a device that is gone
     * — the write starts returning an error and the loop tears the whole bed down for
     * good. Bouncing and rebuilding is what iOS does on its own configuration-change
     * notification, and it is what keeps the radio on the new device.
     *
     * Registered only while the graph is running, so an idle app is not woken by every
     * headset the user connects.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        // Non-null parameters: the platform annotates both arrays @NonNull, and an
        // override that widens them to nullable does not match the base signature.
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = routeChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = routeChanged()

        private fun routeChanged() {
            if (!running.get()) return
            onRouteChanged()
        }
    }

    private var deviceCallbackRegistered = false

    /**
     * Start the engine. Safe to call repeatedly.
     *
     * Audio focus is taken here rather than by the caller: the engine is the thing that
     * knows when sound is actually being produced, and it runs for background chatter with
     * no foreground service involved at all.
     */
    @Synchronized
    fun start() {
        // compareAndSet, not get-then-set: two callers racing here — the chatter service
        // and a transmission arriving at the same moment — would otherwise both pass the
        // check and build a second render loop over the one track. Synchronized as well,
        // so building a generation cannot interleave with stop() tearing one down.
        if (!running.compareAndSet(false, true)) return
        if (!requestFocus()) {
            running.set(false)
            return
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(BLOCK_FRAMES * BYTES_PER_FLOAT * 4)

        renderTrack = buildTrack(minBuffer).also { it.play() }
        burstTrack = buildTrack(minBuffer).also { it.play() }
        applyLevels()
        registerDeviceCallback()

        renderJob = scope.launch(Dispatchers.Default) {
            var generator = RadioAudio.BedGenerator()
            val block = FloatArray(BLOCK_FRAMES)
            var current: Transmission? = null

            // Bind to this generation's track. A loop that wakes from a blocked write
            // after a later start() has installed new tracks must not write into — or
            // tear down — that newer generation.
            val track = renderTrack ?: return@launch

            while (isActive && running.get()) {
                generator = RadioAudio.fillStaticBed(block, generator, bedGain)

                // The pilot asked for silence. Abandon whatever is on the air and
                // everything queued behind it — completing each so no caller is left
                // awaiting a transmission that will never finish.
                if (flushRequested.getAndSet(false)) {
                    current?.finished?.complete(Unit)
                    current = null
                    while (true) (pending.poll() ?: break).finished.complete(Unit)
                }

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
                val written = track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    // ERROR_DEAD_OBJECT after an audioserver restart, or
                    // ERROR_INVALID_OPERATION when the output device changes — a Bluetooth
                    // headset dropping, headphones unplugged.
                    //
                    // Tear down properly rather than just clearing `running`. Leaving that
                    // to stop() meant every later stop() short-circuited on its own guard,
                    // so audio focus was held for the rest of the process (the pilot's
                    // music stayed ducked and could not recover) and both AudioTracks were
                    // orphaned — once per chatter start/stop cycle. Only if this is still
                    // the installed generation, so a stale loop cannot release live tracks.
                    if (renderTrack === track) stop() else running.set(false)
                    break
                }
            }

            // Never leave a caller awaiting a transmission that will now never play.
            current?.finished?.complete(Unit)
            while (true) (pending.poll() ?: break).finished.complete(Unit)
        }
    }

    /**
     * Unconditional and idempotent. It used to return early unless `running` was still
     * set, which meant a render loop that had already exited on a write error left focus
     * held and both tracks leaked forever. Every statement below is null-guarded and safe
     * to repeat — AudioTrack.release() on a released track, Job.cancel() on a finished
     * job, abandonFocus() with no request — so the guard bought nothing and cost that.
     *
     * Synchronized because the render thread can now enter it at the same time as a caller.
     */
    @Synchronized
    fun stop() {
        running.set(false)
        unregisterDeviceCallback()
        abandonFocus()
        renderJob?.cancel()
        renderJob = null
        renderTrack?.release()
        renderTrack = null
        burstTrack?.release()
        burstTrack = null
        while (true) (pending.poll() ?: break).finished.complete(Unit)
    }

    private fun registerDeviceCallback() {
        if (deviceCallbackRegistered) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        // Null handler: the platform posts to the main looper, which is where every other
        // callback in this class already lands.
        runCatching { manager.registerAudioDeviceCallback(deviceCallback, null) }
            .onSuccess { deviceCallbackRegistered = true }
    }

    private fun unregisterDeviceCallback() {
        if (!deviceCallbackRegistered) return
        deviceCallbackRegistered = false
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { manager.unregisterAudioDeviceCallback(deviceCallback) }
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

    /**
     * Drop the transmission on the air and everything queued behind it.
     *
     * The Stop control: with the radio effect on, a rendered call is already samples in
     * this engine's queue by the time it is audible, so stopping TextToSpeech alone leaves
     * it playing out in full. The static bed is left running — the radio is still on, the
     * pilot only wanted the talking to stop.
     */
    fun flushTransmissions() {
        flushRequested.set(true)
    }

    private val flushRequested = AtomicBoolean(false)

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
        // The loop may have exited between the check above and this enqueue. Re-check and
        // settle the transmission ourselves rather than awaiting a consumer that is gone —
        // that await had no timeout and would have wedged the speech pump permanently.
        if (!running.get()) {
            pending.remove(transmission)
            transmission.finished.complete(Unit)
        }
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
