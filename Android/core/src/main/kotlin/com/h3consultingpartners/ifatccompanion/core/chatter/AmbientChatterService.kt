package com.h3consultingpartners.ifatccompanion.core.chatter

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.settings.ChatterDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * The radio side of the ambient chatter: the static bed, the VHF effect chain, the
 * text-to-speech render, and the pilot mic-key sounds.
 *
 * This is the whole platform surface [AmbientChatterService] needs. The audio graph itself
 * is already ported (`core.audio.RadioAudio` holds the pure DSP; `:app`'s
 * `RadioAudioEngine` drives AudioTrack and the TTS engine), so the service takes this
 * interface rather than an engine: it decides *when* to transmit and *what* is said, and
 * hands each line over to be rendered and played.
 *
 * Ported from the `RadioAudioEngine` / `AVSpeechSynthesizer` calls in
 * `IFATCCompanion/Chatter/AmbientChatterService.swift`.
 */
interface ChatterRadio {

    /** Whether the audio graph is currently running. */
    val isRunning: Boolean

    /**
     * Take the audio session (iOS: `.playback` / `.spokenAudio` / `.duckOthers`, which is
     * what lets the app keep running — and stay audible over the silent switch — while
     * backgrounded). Called before every start, PTT resume, mic-key burst, and interruption
     * recovery.
     */
    fun activateSession()

    /** Start the static bed and the effect chain. Idempotent. */
    fun start()

    /** Stop the graph and release the hardware. */
    fun stop()

    /** The user's chatter volume (`AppSettings.chatterVolume`). */
    fun setChatterLevel(level: Double)

    /** Duck the chatter (voice to silence, bed to a faint hiss) under a real ATC call. */
    fun setDucked(ducked: Boolean)

    /**
     * Open the static bed for the duration of a transmission (squelch), then let it fall
     * back to near-silent in the gap.
     */
    fun setTransmitting(transmitting: Boolean)

    /**
     * Render [line] with the voice for [facility] and play it through the radio effect
     * chain, returning when playback finishes.
     *
     * Voice selection belongs to the implementation, and mirrors iOS: a background **pilot**
     * read-back is a fresh random pick from the curated chatter pool per transmission (other
     * aircraft are different stations), while a **controller** line uses the same
     * per-facility voice the user configured for the real controllers, so the background
     * Ground sounds like Ground. The chatter speaks at a **fixed** rate independent of the
     * user's main voice-rate setting (iOS uses an `AVSpeechUtterance` rate of 0.55, where
     * 0.5 is natural).
     *
     * Cancelling the calling coroutine must abandon the call rather than wait it out.
     */
    suspend fun speak(line: ChatterLine, facility: ATCFacility)

    /** Cut the call currently on the air immediately (frequency switch, PTT, stop). */
    fun stopSpeech()

    /** The dull PTT key-down thump (~32 ms) that opens the pilot's own transmission. */
    fun playKeyClick()

    /** The release squelch tail (~140 ms) that closes it. */
    fun playSquelchTail()
}

/**
 * Drives the ambient background radio chatter: it decides *when* to transmit (paced by the
 * chosen density), asks [ChatterScriptGenerator] for a frequency-appropriate exchange, and
 * hands each line to [ChatterRadio] to be rendered and played.
 *
 * It is also the app's **background-audio anchor**: while it is running it keeps an audio
 * session active and a continuous static bed hissing, so the OS keeps the process alive —
 * which is what lets the Infinite Flight poll loop and the live notification keep updating
 * while the app is backgrounded. See `docs/BackgroundChatter.md`.
 *
 * The service ducks the chatter under real ATC calls, pauses for push-to-talk, and also
 * provides the mic-key thump and release squelch tail that bracket the pilot's own
 * transmissions.
 *
 * Swift makes this `@MainActor`; here every method must be called on [scope]'s dispatcher
 * (the app's main one), which gives the same single-threaded confinement.
 *
 * Ported from `IFATCCompanion/Chatter/AmbientChatterService.swift`. The audio-graph half of
 * that file (`RadioAudioEngine`, `AVSpeechSynthesizer`, `VoiceCatalog`, `AVAudioSession`) is
 * behind [ChatterRadio]; the interruption/route-change handlers stay here as
 * [onInterruptionBegan] / [onInterruptionEnded] / [onAudioRouteChanged], for `:app` to call
 * from the platform's own audio-focus callbacks.
 */
class AmbientChatterService(
    private val radio: ChatterRadio,
    private val scope: CoroutineScope,
    /** Injected so the pacing is deterministic under test, as the generator's draws are. */
    private val random: Random = Random.Default,
) {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var settings: AppSettings? = null
    private val generator = ChatterScriptGenerator()

    /**
     * The frequency the pilot is tuned to right now — supplied by the coordinator so the
     * chatter always matches the position.
     */
    private var facilityProvider: () -> ATCFacility = { ATCFacility.CENTER }

    /**
     * The runways for the airport the chatter should reference right now — the origin field
     * pre-departure/climb, the destination once descending/arriving — supplied by the
     * coordinator from the field's ATIS (active departure/arrival runways) and the loaded OSM
     * surface. All-empty when nothing is loaded yet, which lets the generator fall back to
     * random.
     */
    private var runwaysProvider: () -> ChatterRunwayContext = { ChatterRunwayContext() }

    private var loopJob: Job? = null
    private var idleStopJob: Job? = null
    private var speechJob: Job? = null

    private var ducked = false
    private var pausedForPTT = false

    /**
     * The facility the exchange currently being spoken is for; null in the gap between
     * exchanges. Lets a mid-exchange frequency switch be detected so the current call — and
     * any read-back tied to it — is dropped in favour of chatter for the new frequency.
     */
    private var activeFacility: ATCFacility? = null

    /**
     * Set when a mid-exchange frequency switch means the rest of the current exchange (its
     * pending read-back) must be abandoned and a fresh exchange started for the new facility.
     */
    private var exchangeInterrupted = false

    // region Setup

    fun configure(settings: AppSettings) {
        this.settings = settings
        refreshConfig()
    }

    /**
     * Supply the live context (called once from the coordinator): the tuned facility and the
     * runways of the airport the chatter is currently simulating.
     */
    fun bindContext(
        facility: () -> ATCFacility,
        runways: () -> ChatterRunwayContext = { ChatterRunwayContext() },
    ) {
        facilityProvider = facility
        runwaysProvider = runways
    }

    /** Pull volume/density/phraseology from settings. Call when they change. */
    fun refreshConfig() {
        val settings = this.settings ?: return
        generator.mode = settings.phraseologyMode
        generator.digitStyle = settings.digitStyle
        radio.setChatterLevel(settings.chatterVolume)
    }

    // endregion

    // region Lifecycle

    /** Start the continuous chatter (the background anchor). Idempotent. */
    fun start() {
        if (_isRunning.value) return
        idleStopJob?.cancel()
        idleStopJob = null
        radio.activateSession()
        refreshConfig()
        radio.start()
        radio.setDucked(ducked)
        _isRunning.value = true
        loopJob?.cancel()
        loopJob = scope.launch { runLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        idleStopJob?.cancel()
        idleStopJob = null
        // The player is stopped, so its completion may never fire — cut the call on the air
        // and release the awaiting `speak()` now rather than leaving it on the safety timeout.
        radio.stopSpeech()
        speechJob?.cancel()
        radio.stop()
        _isRunning.value = false
        activeFacility = null
    }

    /** Duck (or restore) the chatter under a real ATC call. */
    fun setDucked(ducked: Boolean) {
        this.ducked = ducked
        radio.setDucked(ducked)
    }

    /**
     * Called whenever the tuned facility changes, with the **new** facility. If the chatter
     * is mid-exchange on the previous frequency, end that call immediately (cutting its audio
     * and dropping any pending read-back) so the loop can start chatter appropriate for the
     * newly-tuned frequency. A switch during the gap between exchanges needs no action — the
     * next cycle already reads the current facility.
     *
     * The new facility is passed in rather than re-read from the provider: iOS drives this
     * from a `@Published` observer, which fires in `willSet` (before the stored
     * `currentFacility` is updated), so re-reading it there would still see the old value.
     * The Android coordinator emits the new value the same way.
     */
    fun facilityDidChange(facility: ATCFacility) {
        if (!_isRunning.value || pausedForPTT) return
        if (!shouldAbandonExchange(activeFacility, facility)) return
        abandonCurrentExchange()
    }

    /**
     * End the exchange currently on the air: mark it interrupted so the loop drops any
     * pending read-back, cut the playing call's audio, and cancel the awaiting `speak()` so
     * the loop can immediately start fresh chatter for the new frequency.
     */
    private fun abandonCurrentExchange() {
        exchangeInterrupted = true
        radio.stopSpeech()
        speechJob?.cancel()
    }

    /**
     * Pause/resume around push-to-talk so the chatter never bleeds into the mic and the
     * recording session can take over the audio route.
     */
    fun pauseForPTT() {
        pausedForPTT = true
        radio.stopSpeech()
        speechJob?.cancel()
        radio.stop()
    }

    fun resumeAfterPTT() {
        pausedForPTT = false
        if (!_isRunning.value) return
        radio.activateSession()
        radio.start()
        radio.setDucked(ducked)
    }

    // endregion

    // region Transmission static (mic key / un-key)

    /**
     * Play the mic key-up click or the un-key squelch tail to bracket the pilot's own
     * transmission. Works even when the continuous chatter is off — the engine is started
     * transiently and stopped again after a short idle window.
     */
    fun micKey(event: MicKeyEvent) {
        if (_isRunning.value) {
            fire(event)
            return
        }
        if (pausedForPTT) return
        radio.activateSession()
        if (!radio.isRunning) radio.start()
        fire(event)
        armIdleStop()
    }

    private fun fire(event: MicKeyEvent) {
        when (event) {
            MicKeyEvent.KEY_UP -> radio.playKeyClick()
            MicKeyEvent.KEY_DOWN -> radio.playSquelchTail()
        }
    }

    private fun armIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = scope.launch {
            delay(IDLE_STOP_MILLIS)
            if (_isRunning.value) return@launch
            radio.stop()
        }
    }

    // endregion

    // region Audio-session events (driven by `:app`)

    /** An interruption began (a call, another app taking the route): drop the graph. */
    fun onInterruptionBegan() {
        radio.stop()
    }

    /** The interruption ended: retake the session and bring the bed back up. */
    fun onInterruptionEnded() {
        if (!_isRunning.value || pausedForPTT) return
        radio.activateSession()
        radio.start()
        radio.setDucked(ducked)
    }

    /**
     * The audio route changed (headphones, etc.); bounce the engine so the graph re-forms
     * against the new hardware format.
     */
    fun onAudioRouteChanged() {
        if (!_isRunning.value || pausedForPTT) return
        radio.stop()
        radio.start()
        radio.setDucked(ducked)
    }

    // endregion

    // region Scheduling loop

    private suspend fun runLoop() {
        while (scope.isActive && _isRunning.value) {
            if (pausedForPTT) {
                delay(PTT_POLL_MILLIS)
                continue
            }
            val facility = facilityProvider()
            exchangeInterrupted = false
            activeFacility = facility
            // Refresh the runway pools each cycle so they track the airport in play (origin on
            // departure, destination on arrival) and its current ATIS as the flight progresses.
            val runways = runwaysProvider()
            generator.runwayIdents = runways.all
            generator.departureRunwayIdents = runways.departures
            generator.arrivalRunwayIdents = runways.arrivals
            val lines = generator.exchange(facility, random)
            for (line in lines) {
                if (!scope.isActive || !_isRunning.value || pausedForPTT || exchangeInterrupted) break
                speak(line, facility)
                if (!scope.isActive || exchangeInterrupted) break
                delay(millis(random.nextDouble(INTER_LINE_GAP_MIN_SECONDS, INTER_LINE_GAP_MAX_SECONDS)))
            }
            activeFacility = null
            if (exchangeInterrupted) {
                // A mid-exchange frequency switch cut this exchange short: settle briefly, then
                // the next iteration starts fresh chatter for the newly-tuned facility rather
                // than waiting out the full inter-exchange gap.
                delay(millis(random.nextDouble(SETTLE_GAP_MIN_SECONDS, SETTLE_GAP_MAX_SECONDS)))
                continue
            }
            val gap = (settings?.chatterDensity ?: ChatterDensity.MODERATE).gapRange
            delay(millis(random.nextDouble(gap.start, gap.endInclusive)))
        }
    }

    private suspend fun speak(line: ChatterLine, facility: ATCFacility) {
        // Open the static bed for the duration of the transmission (squelch), then let it fall
        // back to near-silent in the gap.
        radio.setTransmitting(true)
        try {
            // Never hang the loop: a stopped player (PTT / interruption / a mid-exchange
            // frequency switch) may drop its completion, so the safety timeout also releases
            // it. The job handle lets `abandonCurrentExchange()` cut the call immediately.
            val job = scope.launch { radio.speak(line, facility) }
            speechJob = job
            withTimeoutOrNull(SPEECH_SAFETY_TIMEOUT_MILLIS) {
                try {
                    job.join()
                } catch (e: CancellationException) {
                    throw e
                }
            }
            job.cancel()
        } finally {
            radio.setTransmitting(false)
            speechJob = null
        }
    }

    private fun millis(seconds: Double): Long = (seconds * 1000).toLong()

    // endregion

    companion object {
        /** How long the loop naps while push-to-talk holds the audio route (0.4 s). */
        const val PTT_POLL_MILLIS = 400L

        /** The pause between the controller line and its read-back (0.3–1.1 s). */
        const val INTER_LINE_GAP_MIN_SECONDS = 0.3
        const val INTER_LINE_GAP_MAX_SECONDS = 1.1

        /**
         * The short settle after a mid-exchange frequency switch (0.4–0.8 s) before chatter
         * for the new frequency starts — shorter than a full inter-exchange gap on purpose.
         */
        const val SETTLE_GAP_MIN_SECONDS = 0.4
        const val SETTLE_GAP_MAX_SECONDS = 0.8

        /** How long the engine lingers after a bare mic-key burst before releasing (2.5 s). */
        const val IDLE_STOP_MILLIS = 2_500L

        /** Backstop so a dropped playback callback can never hang the loop (25 s). */
        const val SPEECH_SAFETY_TIMEOUT_MILLIS = 25_000L

        /**
         * Whether an exchange being spoken for [active] should be abandoned because the pilot
         * has tuned to [current]. Only a mid-exchange ([active] non-null) switch to a
         * *different* facility interrupts; a switch in the gap ([active] null) or back to the
         * same facility does not. Extracted as a pure decision for testing.
         */
        fun shouldAbandonExchange(active: ATCFacility?, current: ATCFacility): Boolean {
            if (active == null) return false
            return active != current
        }
    }
}
