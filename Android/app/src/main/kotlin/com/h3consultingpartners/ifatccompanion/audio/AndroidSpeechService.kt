package com.h3consultingpartners.ifatccompanion.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.h3consultingpartners.ifatccompanion.core.atis.AirportATIS
import com.h3consultingpartners.ifatccompanion.core.atis.ATISSession
import com.h3consultingpartners.ifatccompanion.core.audio.RadioAudio
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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

    private var tts: TextToSpeech? = null

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { utteranceCompletions.remove(it)?.complete(true) }
        }

        @Deprecated("Superseded by onError(String, int)", ReplaceWith(""))
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

    /** Speak a short sample so the pilot can audition a voice while picking one. */
    fun previewVoice(voiceId: String, sample: String = VOICE_SAMPLE_LINE) {
        val configuration = configuration()
        val engine = tts ?: return
        // Cut off any in-flight preview so rapid taps audition the latest pick immediately
        // instead of queueing up behind earlier ones.
        engine.stop()
        applyVoice(engine, voiceId.ifEmpty { configuration.defaultVoiceId }, configuration)
        engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview-${utteranceCounter.incrementAndGet()}")
    }

    fun stop() {
        while (queue.tryReceive().isSuccess) Unit
        tts?.stop()
        utteranceCompletions.values.forEach { it.complete(false) }
        utteranceCompletions.clear()
        _isSpeaking.value = false
    }

    private fun startPump() {
        if (pumpJob != null) return
        pumpJob = scope.launch {
            for (call in queue) {
                _isSpeaking.value = true
                play(call)
                if (queue.isEmpty) _isSpeaking.value = false
            }
        }
    }

    private suspend fun play(call: QueuedCall) {
        val engine = tts ?: return
        applyVoice(engine, call.voiceId, call.configuration)

        val rendered = if (call.configuration.radioEffectEnabled && radio.isRunning) {
            render(engine, call)
        } else {
            null
        }

        // The key-down thump fires tight against the start of the voice — after the
        // render, not before it, so the synthesis delay never opens a gap.
        if (call.isPilot && call.configuration.radioEffectEnabled) radio.playKeyClick()

        if (rendered != null && rendered.isNotEmpty()) {
            radio.playProcessed(rendered, call.configuration.voiceVolume.toFloat())
        } else {
            // The engine couldn't render to a file (or the effect is off) — speak it
            // plainly so the call is never silent.
            speakAndWait(engine, call.text)
        }

        if (call.isPilot && call.configuration.radioEffectEnabled) {
            // Let the final syllable breathe, then fire the receiver-return squelch tail
            // and hold until it has finished — the controller's response must not begin
            // while the release tail is still playing.
            delay(RadioAudio.PTT_RELEASE_HANG_MILLIS)
            radio.playSquelchTail()
            delay(RadioAudio.PTT_RELEASE_TAIL_MILLIS)
        }
    }

    /** Render to a cache WAV, decode it to mono float samples, and delete the file. */
    private suspend fun render(engine: TextToSpeech, call: QueuedCall): FloatArray? =
        withContext(Dispatchers.IO) {
            val id = "render-${utteranceCounter.incrementAndGet()}"
            val file = File(context.cacheDir, "tts-$id.wav")
            val completion = CompletableDeferred<Boolean>()
            utteranceCompletions[id] = completion

            val queued = engine.synthesizeToFile(call.text, null, file, id)
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

    private suspend fun speakAndWait(engine: TextToSpeech, text: String) {
        val id = "speak-${utteranceCounter.incrementAndGet()}"
        val completion = CompletableDeferred<Boolean>()
        utteranceCompletions[id] = completion
        val queued = engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (queued != TextToSpeech.SUCCESS) {
            utteranceCompletions.remove(id)
            return
        }
        withTimeoutOrNull(SPEAK_TIMEOUT_MILLIS) { completion.await() }
        utteranceCompletions.remove(id)
    }

    private fun applyVoice(
        engine: TextToSpeech,
        voiceId: String?,
        configuration: SpeechConfiguration,
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
        engine.setPitch(configuration.speechPitch.coerceIn(0.5, 2.0).toFloat())
    }

    companion object {
        /** Sample line spoken when auditioning a voice from Settings. */
        /** iOS pins the chatter utterance at 0.55, where 0.5 is natural. */
        const val CHATTER_SPEECH_RATE = 0.55

        const val VOICE_SAMPLE_LINE =
            "Companion one, radar contact, climb and maintain flight level two four zero."

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
