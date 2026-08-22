package com.h3consultingpartners.ifatccompanion.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.h3consultingpartners.ifatccompanion.core.atis.AirportATIS
import com.h3consultingpartners.ifatccompanion.core.atis.ATISSession
import com.h3consultingpartners.ifatccompanion.core.audio.RadioAudio
import com.h3consultingpartners.ifatccompanion.core.chatter.MicKeyEvent
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Offline text-to-speech for the ATC radio — the Android counterpart of
 * `IFATCCompanion/Speech/SpeechService.swift`.
 *
 * Two playback paths, exactly as on iOS:
 *
 *  - **Clean path** (radio voice effect off): hand the text to [TextToSpeech.speak] and
 *    let the engine play it. Nothing is processed.
 *  - **Radio path** (radio voice effect on): render the utterance to PCM first, run it
 *    through the transmitter saturation and the comms band, and play it through
 *    [RadioAudioEngine], bracketing the pilot's own calls with the mic-key thump and the
 *    release squelch tail. iOS renders with `AVSpeechSynthesizer.write`; Android's
 *    equivalent is [TextToSpeech.synthesizeToFile], so the render goes via a cache WAV
 *    that is read back and deleted. Everything after the decode is identical.
 *
 * Calls are played strictly in order through a single queue, so a pilot read-back never
 * overlaps the controller call it answers, and the controller's reply never starts over
 * the pilot's release tail.
 */
class AndroidSpeechService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val radio: RadioAudioEngine,
    private val configuration: () -> SpeechConfiguration,
    /**
     * The mic-key bracket, routed through AmbientChatterService rather than poked straight
     * at the engine. That path (core AmbientChatterService.micKey) opens the bed if it is
     * closed, fires the burst, and arms an idle stop — the direct counterpart of the iOS
     * AppModel hook, already ported and tested.
     *
     * It matters because the two settings are independent: transmission static defaults ON
     * while background chatter defaults OFF, and the engine used to be reachable only via
     * the chatter service. On a default install the bed was therefore never running, so
     * every key click and squelch tail was dropped and every call came out as flat,
     * unprocessed speech — with the pilot's "transmission static" switch showing on.
     */
    private val micKey: (MicKeyEvent) -> Unit = {},
    /** Whether the chatter service owns the radio bed right now; it must not be torn down under it. */
    private val chatterOwnsRadio: () -> Boolean = { false },
) {

    /**
     * Everything the service reads from settings, snapshotted per call so a toggle takes
     * effect on the next transmission — matching the iOS service, which reads the effect
     * toggle live.
     */
    data class SpeechConfiguration(
        val voiceEnabled: Boolean,
        val radioEffectEnabled: Boolean,
        /** 0…1, mapped onto the engine's own rate scale. */
        val speechRate: Double,
        /** 0.5…2.0. */
        val speechPitch: Double,
        /** 0…1. */
        val voiceVolume: Double,
        val defaultVoiceId: String,
        val pilotVoiceId: String,
        val atisVoiceId: String,
        val controllerVoiceIds: Map<ATCFacility, String>,
        /**
         * Whether the ICAO phraseology pack is selected. Only the D-ATIS renderer reads it
         * — every other line arrives already rendered by the phraseology engine, which
         * applied the pack itself.
         */
        val icaoPhraseology: Boolean,
    )

    private data class QueuedCall(
        val text: String,
        val voiceId: String?,
        val isPilot: Boolean,
        val configuration: SpeechConfiguration,
    )

    private val queue = Channel<QueuedCall>(Channel.UNLIMITED)
    private var pumpJob: Job? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)

    /**
     * False until the platform engine has initialised, and false again if the device has
     * no usable engine at all. The app degrades to a silent transcript rather than
     * failing — the calls are still written, they simply are not spoken.
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val utteranceCompletions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val utteranceCounter = AtomicLong(0)

    /**
     * The id of the background-chatter utterance currently being rendered, if any.
     *
     * Chatter and real ATC share one TextToSpeech and one completion map, so "stop the
     * chatter" needs to name what it is stopping. Without this the only available stop was
     * the global one, which drained the ATC queue as well.
     */
    private val chatterUtteranceId = AtomicReference<String?>(null)

    private var tts: TextToSpeech? = null

    /**
     * Serializes "set the voice, then enqueue" against the engine.
     *
     * TextToSpeech carries voice, rate and pitch as *client-global* state: setVoice and
     * setSpeechRate mutate one parameter bundle, and speak/synthesizeToFile bind whatever
     * is current at the moment they are called. Chatter runs on the session dispatcher and
     * the ATC pump on Dispatchers.Default, and both applied a voice and then enqueued —
     * so a chatter line landing between the pump's applyVoice and its synthesizeToFile
     * made a real controller transmission come out in a randomly drawn background-aircraft
     * voice at the chatter's pinned 0.55 rate, and the reverse.
     *
     * Held across applyVoice and exactly one enqueue, and nothing that suspends. It is
     * deliberately NOT held across the completion await: that waits up to
     * RENDER_TIMEOUT_MILLIS, and blocking every other line behind one render for fifteen
     * seconds would be a worse bug than the one being fixed.
     */
    private val engineLock = Mutex()

    private suspend fun <T> withEngine(
        voiceId: String?,
        configuration: SpeechConfiguration,
        isPilot: Boolean = false,
        enqueue: (TextToSpeech) -> T,
    ): T? = engineLock.withLock {
        val engine = tts ?: return@withLock null
        applyVoice(engine, voiceId, configuration, isPilot)
        enqueue(engine)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { utteranceCompletions.remove(it)?.complete(true) }
        }

        /**
         * Without this override the platform forwards onStop to onDone, so an utterance
         * flushed by someone else's TextToSpeech.stop() completed as a *success* — and the
         * render then decoded a truncated or empty WAV and played it. Failing it instead
         * makes the caller fall back to plain speech, so the line is still heard.
         */
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { utteranceCompletions.remove(it)?.complete(false) }
        }

        // Abstract on UtteranceProgressListener and deprecated at the same time, so the
        // override is mandatory. Some engines still call it instead of the two-argument
        // form, so it has to keep completing the pending utterance.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { utteranceCompletions.remove(it)?.complete(false) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { utteranceCompletions.remove(it)?.complete(false) }
        }
    }

    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.language = Locale.US
                engine.setOnUtteranceProgressListener(progressListener)
                _isReady.value = true
                startPump()
            } else {
                _isReady.value = false
            }
        }
    }

    fun shutdown() {
        pumpJob?.cancel()
        pumpJob = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        _isReady.value = false
        _isSpeaking.value = false
    }

    /** The voices the Settings picker offers, English first — mirroring the iOS ordering. */
    fun availableVoices(): List<Voice> {
        val voices = tts?.voices ?: return emptyList()
        return voices.sortedWith(
            compareByDescending<Voice> { it.locale.language == Locale.ENGLISH.language }
                .thenBy { it.locale.toLanguageTag() }
                .thenBy { it.name },
        )
    }

    /** Queue a transmission. Returns immediately; playback is serialized by the pump. */
    fun speak(transmission: ATCTransmission) {
        val configuration = configuration()
        if (!configuration.voiceEnabled) return
        if (transmission.spokenText.isEmpty()) return

        val isPilot = transmission.sender == ATCTransmission.Sender.PILOT
        // ATIS is a one-way broadcast on its own configurable voice; otherwise the pilot
        // voice for own-ship calls, or the per-facility controller voice.
        val voiceId = when {
            transmission.isATISLine -> configuration.atisVoiceId
                .ifEmpty { configuration.defaultVoiceId }

            isPilot -> configuration.pilotVoiceId.ifEmpty { configuration.defaultVoiceId }
            else -> configuration.controllerVoiceIds[transmission.facility].orEmpty()
                .ifEmpty { configuration.defaultVoiceId }
        }

        queue.trySend(
            QueuedCall(transmission.spokenText, voiceId.ifEmpty { null }, isPilot, configuration),
        )
    }

    /**
     * The voices the Settings picker offers, as (id, title) pairs.
     *
     * The audio layer deliberately does not know the UI's option type — it hands back
     * plain data, and the screen decides how to render it.
     */
    fun voiceOptions(): List<Pair<String, String>> = availableVoices().map { voice ->
        val language = voice.locale.getDisplayName(Locale.US)
        voice.name to if (language.isEmpty()) voice.name else "$language — ${voice.name}"
    }

    /**
     * Speak a D-ATIS broadcast on the ATIS voice.
     *
     * The transmission is built by `:core`'s [ATISSession.atisTransmission], not here:
     * ATIS is not a facility (there is no such controller), it is a flag on a
     * transmission, and `:core` already encodes that — SYSTEM sender, Ground facility
     * whose transcript label is overridden to "ATIS", and `isATIS = true`, which is what
     * routes it to the configured broadcast voice and keeps it out of read-back and
     * hand-off bookkeeping.
     *
     * It goes through the same queue as everything else, so it never plays over a
     * controller call.
     */
    fun speakAtis(part: AirportATIS.Part, nowMillis: Long) {
        speak(
            ATISSession.atisTransmission(
                part = part,
                nowMillis = nowMillis,
                icao = configuration().icaoPhraseology,
            ),
        )
    }

    /**
     * Render one line at a fixed rate and play it through the radio chain, returning when
     * it has finished. This is the background-chatter path: it bypasses the transmission
     * queue on purpose, because chatter is ducked and cut by its own service rather than
     * ordered against the pilot's own calls, and because a queued chatter line would delay
     * a real controller call behind it.
     *
     * Cancelling the caller abandons the line rather than waiting it out, which is what
     * lets a frequency change or a PTT press cut the air immediately.
     */
    suspend fun speakChatter(text: String, voiceId: String, volume: Double) {
        if (text.isBlank()) return
        if (tts == null) return
        val base = configuration()
        // Chatter speaks at a fixed rate, independent of the user's own voice-rate
        // setting — matching iOS, where the chatter utterance is pinned at 0.55.
        val configuration = base.copy(speechRate = CHATTER_SPEECH_RATE, voiceVolume = volume)
        val resolvedVoice = voiceId.ifEmpty { configuration.defaultVoiceId }

        val call = QueuedCall(text, resolvedVoice, isPilot = false, configuration = configuration)
        try {
            val rendered = if (radio.isRunning) {
                render(call) { chatterUtteranceId.set(it) }
            } else {
                null
            }
            if (rendered != null && rendered.isNotEmpty()) {
                radio.playProcessed(rendered, volume.toFloat())
            } else {
                speakAndWait(text, resolvedVoice, configuration)
            }
        } finally {
            chatterUtteranceId.set(null)
        }
    }

    /**
     * Abandon the chatter line in flight, and nothing else.
     *
     * The chatter service calls this whenever the pilot switches chatter off, presses
     * push-to-talk, or the flight ends. It used to be routed to [stop], which drains the
     * shared queue and fails every pending utterance — so a real controller clearance that
     * happened to be queued or rendering at that moment was discarded and never spoken.
     * The transcript showed it; the pilot never heard it.
     *
     * Note there is no per-utterance cancel on the platform: TextToSpeech.stop() is
     * engine-global and would abort a concurrent ATC render too. It is not needed here —
     * what actually silences a chatter line is the chatter service cancelling its own
     * speech job and stopping the radio bed, both of which it already does. This just
     * releases the render so nothing is left awaiting it.
     */
    fun stopChatterSpeech() {
        chatterUtteranceId.getAndSet(null)
            ?.let { utteranceCompletions.remove(it)?.complete(false) }
    }

    /**
     * Speak a short sample so the pilot can audition a voice while picking one.
     *
     * It used to call TextToSpeech.stop() first, to cut an earlier preview short. But
     * stop() is engine-global: it also flushed whatever the ATC pump had in flight, and
     * since the platform forwards onStop to onDone that render completed as a success and
     * played a truncated file. QUEUE_FLUSH already replaces a queued preview, so the
     * explicit stop bought nothing and cost that. Goes through the engine lock like every
     * other enqueue, so auditioning a voice from Settings cannot repaint a controller call
     * mid-flight.
     */
    fun previewVoice(voiceId: String, sample: String = VOICE_SAMPLE_LINE) {
        val configuration = configuration()
        if (tts == null) return
        scope.launch {
            withEngine(voiceId.ifEmpty { configuration.defaultVoiceId }, configuration) {
                it.speak(
                    sample,
                    TextToSpeech.QUEUE_FLUSH,
                    volumeParams(configuration.voiceVolume),
                    "preview-${utteranceCounter.incrementAndGet()}",
                )
            }
        }
    }

    fun stop() {
        while (queue.tryReceive().isSuccess) Unit
        tts?.stop()
        // With the radio effect on, a call is already rendered samples in the radio
        // engine's queue by the time it is audible, so stopping TextToSpeech alone lets it
        // play out in full — the pilot presses Stop and nothing happens.
        radio.flushTransmissions()
        utteranceCompletions.values.forEach { it.complete(false) }
        utteranceCompletions.clear()
        _isSpeaking.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class) // Channel.isEmpty
    private fun startPump() {
        if (pumpJob != null) return
        pumpJob = scope.launch {
            var startedHere = false
            for (call in queue) {
                _isSpeaking.value = true

                // Open the bed for the duration of this burst if nothing else has. The
                // render below is a no-op without it, which is why the radio effect was
                // inert whenever background chatter was off — its default. Transient
                // rather than held for the flight: holding AUDIOFOCUS_GAIN gate-to-gate
                // would stop the pilot's music at pushback and never give it back, and a
                // permanently running near-silent bed is exactly the keep-alive trick
                // this app does not use.
                if (call.configuration.radioEffectEnabled && !radio.isRunning && !chatterOwnsRadio()) {
                    radio.start()
                    startedHere = true
                }

                play(call)

                if (queue.isEmpty) {
                    _isSpeaking.value = false
                    if (startedHere && !chatterOwnsRadio()) {
                        radio.stop()
                        startedHere = false
                    }
                }
            }
        }
    }

    private suspend fun play(call: QueuedCall) {
        if (tts == null) return

        // No applyVoice here any more: render() and speakAndWait() each apply the voice
        // inside the engine lock, immediately before their own enqueue. Applying it here
        // and enqueueing later is exactly the window another coroutine used to slip into.
        val rendered = if (call.configuration.radioEffectEnabled && radio.isRunning) {
            render(call)
        } else {
            null
        }

        // The key-down thump fires tight against the start of the voice — after the
        // render, not before it, so the synthesis delay never opens a gap.
        if (call.isPilot && call.configuration.radioEffectEnabled) micKey(MicKeyEvent.KEY_UP)

        if (rendered != null && rendered.isNotEmpty()) {
            radio.playProcessed(rendered, call.configuration.voiceVolume.toFloat())
        } else {
            // The engine couldn't render to a file (or the effect is off) — speak it
            // plainly so the call is never silent.
            speakAndWait(call.text, call.voiceId, call.configuration, isPilot = call.isPilot)
        }

        if (call.isPilot && call.configuration.radioEffectEnabled) {
            // Let the final syllable breathe, then fire the receiver-return squelch tail
            // and hold until it has finished — the controller's response must not begin
            // while the release tail is still playing.
            delay(RadioAudio.PTT_RELEASE_HANG_MILLIS)
            micKey(MicKeyEvent.KEY_DOWN)
            delay(RadioAudio.PTT_RELEASE_TAIL_MILLIS)
        }
    }

    /**
     * Render to a cache WAV, decode it to mono float samples, and delete the file.
     *
     * [onId] receives the utterance id as soon as it exists, so a caller that may need to
     * abandon this particular render later can name it. Defaulted, because only the
     * chatter path needs to.
     */
    private suspend fun render(
        call: QueuedCall,
        onId: (String) -> Unit = {},
    ): FloatArray? =
        withContext(Dispatchers.IO) {
            val id = "render-${utteranceCounter.incrementAndGet()}"
            onId(id)
            val file = File(context.cacheDir, "tts-$id.wav")
            val completion = CompletableDeferred<Boolean>()
            utteranceCompletions[id] = completion

            // Voice and enqueue together, under the lock — see [engineLock].
            val queued = withEngine(call.voiceId, call.configuration, isPilot = call.isPilot) {
                it.synthesizeToFile(call.text, null, file, id)
            } ?: TextToSpeech.ERROR
            if (queued != TextToSpeech.SUCCESS) {
                utteranceCompletions.remove(id)
                file.delete()
                return@withContext null
            }

            // Bounded like the iOS render's safety timeout, so a dropped completion can
            // never wedge the queue.
            val succeeded = withTimeoutOrNull(RENDER_TIMEOUT_MILLIS) { completion.await() } ?: false
            utteranceCompletions.remove(id)
            if (!succeeded) {
                file.delete()
                return@withContext null
            }

            val decoded = WavPcm.decode(file)
            file.delete()
            decoded?.let { WavPcm.toMono(it, RadioAudio.SAMPLE_RATE) }
        }

    /**
     * The fallback path, and the wider of the two race windows: it used to rely on an
     * applyVoice that could have run up to RENDER_TIMEOUT_MILLIS earlier, with anything at
     * all happening to the engine in between. It now re-applies the voice immediately
     * before the enqueue, under the lock.
     */
    private suspend fun speakAndWait(
        text: String,
        voiceId: String?,
        configuration: SpeechConfiguration,
        isPilot: Boolean = false,
    ) {
        val id = "speak-${utteranceCounter.incrementAndGet()}"
        val completion = CompletableDeferred<Boolean>()
        utteranceCompletions[id] = completion
        val queued = withEngine(voiceId, configuration, isPilot = isPilot) {
            it.speak(text, TextToSpeech.QUEUE_ADD, volumeParams(configuration.voiceVolume), id)
        } ?: TextToSpeech.ERROR
        if (queued != TextToSpeech.SUCCESS) {
            utteranceCompletions.remove(id)
            return
        }
        withTimeoutOrNull(SPEAK_TIMEOUT_MILLIS) { completion.await() }
        utteranceCompletions.remove(id)
    }

    /**
     * The pilot's Voice volume, as the engine's own per-utterance level.
     *
     * The radio path already applied it — `playProcessed` scales the rendered samples — so
     * the slider governed the radio only while "Transmission static" was on. On the clean
     * path, and on any call whose render failed and fell back to it, both `speak()` calls
     * passed a null params Bundle and the engine used its default of 1.0: the slider was
     * inert and every call came out at full volume. Setting it here makes the clean path
     * and the radio path agree, and it is the same level the chatter's own fallback then
     * plays at, since its configuration carries the chatter level rather than the voice one.
     */
    private fun volumeParams(volume: Double): Bundle = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0.0, 1.0).toFloat())
    }

    private fun applyVoice(
        engine: TextToSpeech,
        voiceId: String?,
        configuration: SpeechConfiguration,
        isPilot: Boolean = false,
    ) {
        val voice = voiceId
            ?.takeIf { it.isNotEmpty() }
            ?.let { id -> engine.voices?.firstOrNull { it.name == id } }
        if (voice != null) engine.voice = voice else engine.language = Locale.US

        // Android's setSpeechRate/setPitch are multipliers around 1.0, where iOS's
        // AVSpeechUtterance takes an absolute rate in its own 0…1 scale and a pitch
        // multiplier. The pitch carries across directly; the rate is mapped so the
        // setting's midpoint is the engine's natural speed.
        engine.setSpeechRate(androidRate(configuration.speechRate))
        // Own-ship calls sit 8% below the configured pitch, as iOS does. Both voices fall
        // back to defaultVoiceID unless the pilot has chosen a separate one, which is the
        // common case — so without this the pilot's read-backs come out identical to the
        // controller's calls and the transcript is the only way to tell who is speaking.
        val pitch = configuration.speechPitch * (if (isPilot) PILOT_PITCH_MULTIPLIER else 1.0)
        engine.setPitch(pitch.coerceIn(0.5, 2.0).toFloat())
    }

    companion object {
        /** Sample line spoken when auditioning a voice from Settings. */
        /** iOS pins the chatter utterance at 0.55, where 0.5 is natural. */
        const val CHATTER_SPEECH_RATE = 0.55

        const val VOICE_SAMPLE_LINE =
            "Companion one, radar contact, climb and maintain flight level two four zero."

        /**
         * Own-ship transmissions are spoken 8% below the configured pitch, so the pilot
         * stays audibly distinct from the controller even when both share a system voice.
         * iOS uses the same figure.
         */
        const val PILOT_PITCH_MULTIPLIER = 0.92

        private const val RENDER_TIMEOUT_MILLIS = 15_000L
        private const val SPEAK_TIMEOUT_MILLIS = 60_000L

        /**
         * iOS speech rate is an absolute value in `AVSpeechUtteranceMinimumSpeechRate` …
         * `Maximum`, whose default sits at 0.5. Android's is a multiplier where 1.0 is the
         * engine's natural speed. Mapping the iOS default onto 1.0 keeps a pilot who never
         * touches the slider hearing the same pace on both platforms, and the ends of the
         * slider stay usefully slower and faster.
         */
        fun androidRate(setting: Double): Float =
            (setting.coerceIn(0.0, 1.0) * 2.0).coerceIn(0.25, 2.0).toFloat()
    }
}
