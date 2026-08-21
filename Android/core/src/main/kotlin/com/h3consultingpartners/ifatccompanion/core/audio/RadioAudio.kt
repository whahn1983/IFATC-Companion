package com.h3consultingpartners.ifatccompanion.core.audio

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The radio's sound, as pure sample arithmetic.
 *
 * This is the part of the audio stack that decides what a transmission actually sounds
 * like: the transmitter saturation, the comms-band filter, and the two mic-key bursts
 * that bracket the pilot's own calls. It is deliberately free of any audio framework —
 * on iOS the same maths lives inside `AVAudioEngine` nodes and `RadioSaturation.swift`;
 * here it produces plain float arrays that the Android layer hands to an `AudioTrack`.
 *
 * Keeping it here means every tuning value the iOS build arrived at is carried across
 * verbatim and can be asserted in a unit test, rather than being re-tuned by ear against
 * a different audio stack.
 *
 * Ported from `IFATCCompanion/Speech/RadioSaturation.swift` and the buffer-generating
 * half of `IFATCCompanion/Chatter/RadioAudioEngine.swift`.
 */
object RadioAudio {

    /** The internal format everything is rendered at, matching the iOS graph. */
    const val SAMPLE_RATE = 44_100

    // region Saturation

    /**
     * The drive/mix/output-gain the iOS build uses for every spoken transmission —
     * both the main calls and the ambient chatter.
     */
    const val SPEECH_DRIVE = 8.0f
    const val SPEECH_MIX = 0.7f
    const val SPEECH_OUTPUT_GAIN = 0.75f

    /**
     * A tube-style **soft-clip** (tanh waveshaper) applied in place — the driven "grit"
     * of a real radio transmitter.
     *
     * This is deliberately not a ring-modulator or decimator effect (which is what
     * Apple's `AVAudioUnitDistortion` speech presets are, and what Android's
     * `PresetReverb`/`Equalizer` effects would approximate): ring modulation produces a
     * metallic robot voice, while transmitter/tube saturation adds harmonic content and a
     * driven quality.
     *
     * The amount of grit is set almost entirely by [drive]: `tanh` is near-linear (so
     * nearly inaudible) at low drive, and only generates real harmonics once the signal
     * is pushed into its saturating region — so meaningful grit needs drive well above 1
     * (roughly 4–10 for speech). The saturated copy is blended with the clean signal
     * ([mix]) so consonants stay intelligible, and [outputGain] tames the loudness heavy
     * drive adds (heavy drive is also a compressor — it lifts the quiet parts).
     *
     * The blend is bounded to the input's range, so with `outputGain <= 1` it cannot push
     * the following band-pass into clipping.
     */
    fun applySaturation(
        samples: FloatArray,
        drive: Float = SPEECH_DRIVE,
        mix: Float = SPEECH_MIX,
        outputGain: Float = SPEECH_OUTPUT_GAIN,
    ) {
        val d = max(0.001f, drive)
        val m = min(1f, max(0f, mix))
        for (i in samples.indices) {
            val x = samples[i]
            val shaped = tanh(x * d)
            samples[i] = ((1 - m) * x + m * shaped) * outputGain
        }
    }

    // endregion

    // region Comms-band filter

    /**
     * The comms band the iOS graph's two-band EQ imposes on every transmission: a
     * high-pass at 320 Hz and a low-pass at 3300 Hz. Kept a touch wider than a pure
     * 300 Hz–3 kHz band so the saturation harmonics (the grit) survive and the calls stay
     * clearly intelligible.
     */
    const val BAND_PASS_LOW_HZ = 320.0
    const val BAND_PASS_HIGH_HZ = 3_300.0

    /**
     * Apply the comms band in place, as a cascade of two one-pole filters — the same
     * shape the noise generators below use, so the voice and the bursts share a timbre.
     *
     * A one-pole pair is a gentler slope than the EQ's biquads, which matters less than
     * it sounds: the audible character of a radio voice comes from the band being narrow
     * and from the saturation, not from the filter order.
     */
    fun applyCommsBand(samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        val aLow = onePoleCoefficient(BAND_PASS_HIGH_HZ, sampleRate)
        val aHigh = onePoleCoefficient(BAND_PASS_LOW_HZ, sampleRate)
        var low = 0f
        var lowLow = 0f
        for (i in samples.indices) {
            low += aLow * (samples[i] - low)
            lowLow += aHigh * (low - lowLow)
            samples[i] = low - lowLow
        }
    }

    /** The one-pole smoothing coefficient for a given corner frequency. */
    internal fun onePoleCoefficient(cornerHz: Double, sampleRate: Int): Float {
        val x = 2.0 * Math.PI * cornerHz / sampleRate
        return (x / (x + 1.0)).toFloat().coerceIn(0f, 1f)
    }

    // endregion

    // region Mic-key bursts

    /**
     * The **PTT key-down thump** (~32 ms): the dull contact sound when the pilot presses
     * the mic key.
     *
     * Band-limited noise — no oscillator, so there is no audible pitch or ring — gently
     * emphasised in the low-mids (~180–300 Hz, set by [KEY_CLICK_A_HIGH]) and rolled off
     * above ~1.7 kHz ([KEY_CLICK_A_LOW]), with a soft ~4 ms attack rather than an instant
     * transient and no static wash. Sits ~16 dB under the pilot voice, so it reads as a
     * muffled contact.
     *
     * Deliberately NOT a courtesy beep, a long walkie-talkie hiss, or a sharp digital
     * click.
     */
    fun keyClickSamples(sampleRate: Int = SAMPLE_RATE): FloatArray = burst(
        durationSeconds = KEY_CLICK_DURATION,
        aLow = KEY_CLICK_A_LOW,
        aHigh = KEY_CLICK_A_HIGH,
        attack = KEY_CLICK_ATTACK,
        decayPower = KEY_CLICK_DECAY_POWER,
        amplitude = KEY_CLICK_AMPLITUDE,
        sampleRate = sampleRate,
    )

    const val KEY_CLICK_DURATION = 0.032
    const val KEY_CLICK_A_LOW = 0.22f
    const val KEY_CLICK_A_HIGH = 0.026f
    const val KEY_CLICK_ATTACK = 0.12f
    const val KEY_CLICK_DECAY_POWER = 2.5f
    const val KEY_CLICK_AMPLITUDE = 0.30f

    /**
     * The **PTT-release squelch tail** (~140 ms): the receiver-return burst you hear when
     * the pilot un-keys.
     *
     * Band-limited static (≈350 Hz–3 kHz) with a fast ~2 ms attack and ~40 ms of
     * open-squelch noise that then decays rapidly to complete silence, plus a few sparse,
     * irregular crackles poking through the decay. No beep, chirp, or lingering hiss; its
     * initial level sits ~12 dB under the pilot voice.
     */
    fun squelchTailSamples(sampleRate: Int = SAMPLE_RATE): FloatArray {
        val frames = (sampleRate * SQUELCH_DURATION).toInt()
        if (frames <= 0) return FloatArray(0)
        val out = FloatArray(frames)
        val fs = sampleRate.toFloat()
        val durationF = SQUELCH_DURATION.toFloat()

        var state = 0x2B7E_1516u
        var low = 0f
        var lowLow = 0f
        for (i in 0 until frames) {
            state = xorshift(state)
            val white = whiteFrom(state)
            low += SQUELCH_A_LOW * (white - low) // low-pass (upper edge)
            lowLow += SQUELCH_A_HIGH * (low - lowLow) // low-frequency tracker
            val band = low - lowLow // band-pass
            val ts = i / fs

            // Body: attack ramp, short plateau, then a rapid decay to silence.
            val body = when {
                ts < SQUELCH_ATTACK -> ts / SQUELCH_ATTACK
                ts < SQUELCH_BODY_END -> 1f
                else -> exp(-(ts - SQUELCH_BODY_END) / SQUELCH_DECAY_TAU)
            }

            // Crackles layered on top of the body envelope.
            var crackle = 0f
            for (c in CRACKLE_CENTERS.indices) {
                val dt = ts - CRACKLE_CENTERS[c]
                if (dt < -CRACKLE_ATTACK) continue
                crackle += if (dt < 0) {
                    CRACKLE_PEAKS[c] * (dt + CRACKLE_ATTACK) / CRACKLE_ATTACK
                } else {
                    CRACKLE_PEAKS[c] * exp(-dt / CRACKLE_TAU)
                }
            }

            var env = body + crackle
            // Guarantee a clean, click-free finish into complete silence.
            if (ts > durationF - SQUELCH_FADE) {
                env *= max(0f, (durationF - ts) / SQUELCH_FADE)
            }
            out[i] = band * env * SQUELCH_AMPLITUDE
        }
        return out
    }

    const val SQUELCH_DURATION = 0.140

    /** ≈3 kHz low-pass (upper edge). */
    const val SQUELCH_A_LOW = 0.35f

    /** ≈350 Hz high-pass (lower edge). */
    const val SQUELCH_A_HIGH = 0.049f

    /** Initial level ≈12 dB below the pilot voice. */
    const val SQUELCH_AMPLITUDE = 0.47f

    const val SQUELCH_ATTACK = 0.002f
    const val SQUELCH_BODY_END = 0.042f
    const val SQUELCH_DECAY_TAU = 0.020f
    const val SQUELCH_FADE = 0.003f

    /** Seconds, irregular spacing. */
    private val CRACKLE_CENTERS = floatArrayOf(0.052f, 0.083f, 0.121f)

    /** Trailing off. */
    private val CRACKLE_PEAKS = floatArrayOf(0.50f, 0.40f, 0.30f)

    private const val CRACKLE_ATTACK = 0.0004f
    private const val CRACKLE_TAU = 0.0018f

    /** Build a band-limited noise burst with an attack ramp and a power-curve decay. */
    private fun burst(
        durationSeconds: Double,
        aLow: Float,
        aHigh: Float,
        attack: Float,
        decayPower: Float,
        amplitude: Float,
        sampleRate: Int,
    ): FloatArray {
        val frames = (sampleRate * durationSeconds).toInt()
        if (frames <= 0) return FloatArray(0)
        val out = FloatArray(frames)
        var state = 0x1234_5678u
        var low = 0f
        var lowLow = 0f
        for (i in 0 until frames) {
            state = xorshift(state)
            val white = whiteFrom(state)
            low += aLow * (white - low) // low-pass
            lowLow += aHigh * (low - lowLow) // low-frequency tracker
            val band = low - lowLow // band-pass
            val t = i.toFloat() / frames
            val env = if (t < attack) {
                t / attack
            } else {
                (1 - (t - attack) / (1 - attack)).pow(decayPower)
            }
            out[i] = band * env * amplitude
        }
        return out
    }

    // endregion

    // region Static bed

    /**
     * One block of the ambient static bed: xorshift32 white noise through a one-pole
     * low-pass for a warmer hiss, mixed 35 % white / 65 % filtered. [state] and [lowpass]
     * carry across calls so consecutive blocks are continuous — the bed is generated
     * block by block as the track drains, exactly as the iOS source node does.
     *
     * Returns the updated generator state.
     */
    fun fillStaticBed(out: FloatArray, generator: BedGenerator, gain: Float): BedGenerator {
        var state = generator.state
        var lowpass = generator.lowpass
        for (i in out.indices) {
            state = xorshift(state)
            val white = whiteFrom(state)
            lowpass += BED_LOWPASS_COEFFICIENT * (white - lowpass)
            out[i] = (white * BED_WHITE_MIX + lowpass * BED_FILTERED_MIX) * gain
        }
        return BedGenerator(state, lowpass)
    }

    /** The continuing state of the static-bed generator between blocks. */
    data class BedGenerator(val state: UInt = 0x9E3779B9u, val lowpass: Float = 0f)

    const val BED_LOWPASS_COEFFICIENT = 0.06f
    const val BED_WHITE_MIX = 0.35f
    const val BED_FILTERED_MIX = 0.65f

    /**
     * The bed and voice levels the iOS mixer applies. The chatter voice sits well above
     * the static so the calls read clearly. The static bed is kept much lower than the
     * voice and — like a real squelch — only "opens up" while a transmission is playing,
     * falling to near-silent in the gaps between calls. The squelch path is never ducked.
     */
    fun chatterLevels(chatterLevel: Float, ducked: Boolean, transmitting: Boolean): Levels {
        val voice = if (ducked) 0f else chatterLevel * 2.0f
        val bed = when {
            ducked -> chatterLevel * 0.05f // faint hiss under a real ATC call
            transmitting -> chatterLevel * 0.35f // static wraps the active chatter call
            else -> chatterLevel * 0.04f // almost inaudible between calls
        }
        return Levels(
            voice = voice.coerceIn(0f, 1f),
            bed = bed.coerceIn(0f, 1f),
        )
    }

    data class Levels(val voice: Float, val bed: Float)

    /**
     * The mic-key bursts sit well below the spoken calls — they bracket the pilot's own
     * (full-volume) transmissions, so they should sit under the voice, not compete.
     */
    const val SQUELCH_MIXER_LEVEL = 0.40f

    // endregion

    // region PTT timing

    /**
     * Silence held after the pilot's final syllable before the PTT-release squelch tail
     * fires — a short "release hang" so the tail doesn't clip the last word.
     */
    const val PTT_RELEASE_HANG_MILLIS = 45L

    /**
     * Length of the receiver-return squelch tail. The controller's response is held off
     * until this has elapsed, so it never starts over the tail.
     */
    const val PTT_RELEASE_TAIL_MILLIS = 140L

    // endregion

    private fun xorshift(seed: UInt): UInt {
        var state = seed
        state = state xor (state shl 13)
        state = state xor (state shr 17)
        state = state xor (state shl 5)
        return state
    }

    /** The xorshift state read as a signed 32-bit value scaled to −1…1, as on iOS. */
    private fun whiteFrom(state: UInt): Float =
        state.toInt().toFloat() / Int.MAX_VALUE.toFloat()
}
