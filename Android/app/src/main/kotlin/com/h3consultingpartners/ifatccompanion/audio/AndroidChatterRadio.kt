package com.h3consultingpartners.ifatccompanion.audio

import com.h3consultingpartners.ifatccompanion.core.chatter.ChatterLine
import com.h3consultingpartners.ifatccompanion.core.chatter.ChatterRadio
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android side of the background-chatter audio port.
 *
 * It owns nothing: [RadioAudioEngine] already drives the static bed, the ducking and the
 * mic-key bursts, and [AndroidSpeechService] already renders speech through the radio
 * effect chain. This just satisfies the interface `AmbientChatterService` needs and picks
 * the voice for each line.
 *
 * **One deliberate divergence from iOS, and it matters.** On iOS the chatter's audio
 * session is what keeps the process alive in the background — the static bed is doing two
 * jobs. On Android it does exactly one: it is a feature the pilot turned on because they
 * want ambient radio. What keeps the flight running while backgrounded is the foreground
 * service (see `ActiveFlightService` and Docs/ANDROID_BACKGROUND_EXECUTION.md), which is
 * the sanctioned mechanism and needs no audio at all. That is why the Live Flight Update
 * and background chatter are independent settings here where iOS interlocks them: turning
 * chatter off on Android costs you ambient radio and nothing else.
 *
 * Playing silent or near-silent audio purely to stay alive would be exactly the kind of
 * work-around Play policy treats as abuse, so the app does not do it — the bed only ever
 * runs because chatter is on.
 */
class AndroidChatterRadio(
    private val engine: RadioAudioEngine,
    private val speech: AndroidSpeechService,
    private val settings: () -> AppSettings,
    private val random: Random = Random.Default,
) : ChatterRadio {

    override val isRunning: Boolean get() = engine.isRunning

    /**
     * On iOS this takes the shared `AVAudioSession`. Android has no equivalent step: audio
     * focus is requested by [RadioAudioEngine] when its track starts, and the process stays
     * alive through the foreground service rather than through the session. Kept as a
     * no-op so the shared service's call sites read the same on both platforms.
     */
    override fun activateSession() = Unit

    override fun start() = engine.start()

    override fun stop() = engine.stop()

    override fun setChatterLevel(level: Double) = engine.setChatterLevel(level.toFloat())

    override fun setDucked(ducked: Boolean) = engine.setDucked(ducked)

    override fun setTransmitting(transmitting: Boolean) = engine.setTransmitting(transmitting)

    /**
     * Voice selection mirrors iOS: a background **pilot** read-back draws a fresh voice per
     * transmission, because the other aircraft on the frequency are different stations and
     * hearing one voice fly the whole field is the thing that breaks the illusion. A
     * background **controller** line uses the same per-facility voice the user configured
     * for the real controllers, so the ambient Ground sounds like their Ground.
     */
    override suspend fun speak(line: ChatterLine, facility: ATCFacility) =
        // The chatter service is confined to the session dispatcher, which is the main
        // thread — the Android stand-in for iOS's @MainActor. Its audio leg has no business
        // being there: a chatter line arrives every few seconds for the whole flight, and
        // each one meant the copy, tanh saturation and band-pass over a whole utterance at
        // 44.1 kHz, plus two synchronous TextToSpeech binder round-trips, all on the UI
        // thread. Frames dropped whenever the map was being panned.
        //
        // Safe to move: everything below reads a StateFlow value or touches the speech
        // service's ConcurrentHashMap, AtomicLong and the TTS binder — the same objects the
        // ATC pump already drives from Dispatchers.Default. The chatter service's own
        // confined state (speechJob, activeFacility, the transmitting flag) stays on the
        // session dispatcher in its caller.
        withContext(Dispatchers.Default) {
            val configured = settings()
            val voiceId = if (line.isPilot) {
                randomPilotVoiceId(configured)
            } else {
                configured.controllerVoiceID(facility)
            }
            // The engine's own level, not the raw setting: RadioAudio.chatterLevels puts
            // the chatter voice at chatterLevel * 2.0, and at zero while a real call is
            // ducking it. Passing chatterVolume straight through played the ambient voice
            // about 6 dB under the iOS level — thin beneath its own static, which sits at
            // the iOS level — and, worse, left it talking straight through a controller
            // clearance even with the duck applied, because the duck only reached the bed.
            speech.speakChatter(line.spokenText, voiceId, engine.chatterSpeechLevel.toDouble())
        }

    /**
     * Scoped to the chatter's own line. This used to call the speech service's global
     * stop(), which drains the queue shared with real ATC and fails every pending
     * utterance — so toggling chatter off, pressing push-to-talk or ending a flight could
     * silently discard a controller clearance that happened to be in flight.
     */
    override fun stopSpeech() = speech.stopChatterSpeech()

    override fun playKeyClick() = engine.playKeyClick()

    override fun playSquelchTail() = engine.playSquelchTail()

    /**
     * A voice for one background aircraft. Drawn from the device's installed voices rather
     * than a curated list, since the set differs by device; the user's own pilot voice is
     * excluded so their aircraft still sounds distinct on the frequency.
     */
    private fun randomPilotVoiceId(configured: AppSettings): String {
        val options = speech.voiceOptions()
            .map { it.first }
            .filter { it != configured.voicePilot }
        if (options.isEmpty()) return ""
        return options[random.nextInt(options.size)]
    }
}
