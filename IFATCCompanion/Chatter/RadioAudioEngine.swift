import Foundation
import AVFoundation

/// The audio graph behind the background radio chatter and the mic-key static effects.
///
/// ```
/// bedSource (generated static) ─► bedMixer ───────────────┐
/// speechPlayer (soft-clipped voice) ─► EQ(band-pass) ─► speechMixer ─► mainMixer ─► out
/// squelchPlayer (mic-key burst) ─► squelchMixer ──────────┘
/// ```
///
/// * The **static bed** is generated on the fly (a filtered-noise `AVAudioSourceNode`),
///   so there is no bundled audio asset and it can hiss continuously — which is exactly
///   what keeps the app alive in the background (see `AmbientChatterService`).
/// * The **chatter voice** buffers are given a gentle soft-clip saturation
///   (`applyRadioSaturation`) and then band-passed, so a synthesized call sounds like a
///   real, half-readable transmission buried in the static — without the robotic ring-mod
///   artifact of `AVAudioUnitDistortion`'s speech presets.
/// * The **squelch** player fires short static bursts to bracket the pilot's own
///   transmissions (mic key / un-key), and is deliberately *not* ducked so it stays
///   audible over a real call.
///
/// This class owns only audio nodes (thread-agnostic); drive it from the main actor.
/// The one exception is the source-node render block, which runs on the realtime audio
/// thread and touches nothing but a heap-allocated gain value.
final class RadioAudioEngine {

    /// Fixed internal format everything is converted to before it hits the graph.
    let commonFormat = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)!

    private let engine = AVAudioEngine()
    private let bedMixer = AVAudioMixerNode()
    private let speechMixer = AVAudioMixerNode()
    private let squelchMixer = AVAudioMixerNode()
    private let speechPlayer = AVAudioPlayerNode()
    private let squelchPlayer = AVAudioPlayerNode()
    private let eq = AVAudioUnitEQ(numberOfBands: 2)
    private var bedSource: AVAudioSourceNode!

    /// Heap-held so the realtime render block can read the current static level without
    /// capturing `self`. Written from the main actor, read on the audio thread — a
    /// benign, tear-free race for a single `Float` (standard for an audio gain).
    private let bedGain = UnsafeMutablePointer<Float>.allocate(capacity: 1)

    /// Dull contact "thump" for keying the mic (pilot presses PTT).
    private var keyClickBuffer: AVAudioPCMBuffer?
    /// Receiver-return squelch tail after un-keying (the burst before the receiver mutes).
    private var squelchTailBuffer: AVAudioPCMBuffer?
    private var converters: [AVAudioFormat: AVAudioConverter] = [:]
    private var built = false
    private(set) var isRunning = false

    /// Target (un-ducked) chatter level, remembered so un-ducking restores it.
    private var chatterLevel: Float = 0.16
    /// Ducked under a real ATC call.
    private var ducked = false
    /// A chatter transmission is currently playing — the static "opens up" for it and
    /// falls back to near-silent between calls (radio squelch behaviour).
    private var transmitting = false

    init() {
        bedGain.initialize(to: 0)
    }

    deinit {
        bedGain.deallocate()
    }

    // MARK: - Graph

    private func buildGraph() {
        guard !built else { return }
        built = true

        var rngState: UInt32 = 0x9E3779B9
        var lowpass: Float = 0
        let gainPtr = bedGain
        bedSource = AVAudioSourceNode(format: commonFormat) { _, _, frameCount, ablPointer -> OSStatus in
            let abl = UnsafeMutableAudioBufferListPointer(ablPointer)
            let gain = gainPtr.pointee
            for frame in 0..<Int(frameCount) {
                // xorshift32 white noise, then a one-pole low-pass for a warmer "hiss".
                rngState ^= rngState << 13
                rngState ^= rngState >> 17
                rngState ^= rngState << 5
                let white = Float(Int32(bitPattern: rngState)) / Float(Int32.max)
                lowpass += 0.06 * (white - lowpass)
                let sample = (white * 0.35 + lowpass * 0.65) * gain
                for buffer in abl {
                    guard let data = buffer.mData else { continue }
                    data.assumingMemoryBound(to: Float.self)[frame] = sample
                }
            }
            return noErr
        }

        // Band-pass the chatter voice to the "comms band". The radio grit comes from a
        // gentle soft-clip on the buffers (`applyRadioSaturation` in `scheduleSpeech`),
        // not a ring-modulator distortion unit — so it sounds like a driven radio rather
        // than a robot.
        eq.bands[0].filterType = .highPass
        eq.bands[0].frequency = 320
        eq.bands[0].bypass = false
        eq.bands[1].filterType = .lowPass
        eq.bands[1].frequency = 3_300   // a bit wider so the saturation harmonics (grit) survive
        eq.bands[1].bypass = false
        eq.globalGain = 1

        for node in [bedSource as AVAudioNode, speechPlayer, squelchPlayer, eq, bedMixer, speechMixer, squelchMixer] {
            engine.attach(node)
        }

        engine.connect(bedSource, to: bedMixer, format: commonFormat)
        engine.connect(bedMixer, to: engine.mainMixerNode, format: commonFormat)

        engine.connect(speechPlayer, to: eq, format: commonFormat)
        engine.connect(eq, to: speechMixer, format: commonFormat)
        engine.connect(speechMixer, to: engine.mainMixerNode, format: commonFormat)

        engine.connect(squelchPlayer, to: squelchMixer, format: commonFormat)
        engine.connect(squelchMixer, to: engine.mainMixerNode, format: commonFormat)

        // Kept well below the spoken calls — the mic-key burst brackets the pilot's own
        // (full-volume) transmissions, so it should sit under the voice, not compete with it.
        // Subtle — the mic click / squelch tail sit under the voice, not over it.
        squelchMixer.outputVolume = 0.40
        applyLevels()
        keyClickBuffer = makeKeyClickBuffer()
        squelchTailBuffer = makeSquelchTailBuffer()
    }

    // MARK: - Lifecycle

    /// Start the engine and the player nodes. Safe to call repeatedly. The caller is
    /// responsible for configuring/activating the shared `AVAudioSession` first.
    func start() {
        buildGraph()
        guard !engine.isRunning else { isRunning = true; return }
        engine.prepare()
        do {
            try engine.start()
            speechPlayer.play()
            squelchPlayer.play()
            isRunning = true
        } catch {
            isRunning = false
        }
    }

    func stop() {
        guard built else { return }
        speechPlayer.stop()
        squelchPlayer.stop()
        engine.stop()
        isRunning = false
        // Clear the squelch state so a restart doesn't come back with the bed held open.
        transmitting = false
    }

    /// Cut any chatter speech that is currently playing — e.g. the pilot tuned away
    /// mid-call — while leaving the static bed and the engine running. The speech player is
    /// immediately re-armed so the next `scheduleSpeech` plays normally, and the bed drops
    /// back to its near-silent between-calls level.
    func stopSpeech() {
        guard built, isRunning else { return }
        speechPlayer.stop()
        speechPlayer.play()
        transmitting = false
        applyLevels()
    }

    // MARK: - Levels

    /// Set the un-ducked chatter loudness (0…1) for both the static bed and the voice.
    func setChatterLevel(_ level: Float) {
        chatterLevel = max(0, min(1, level))
        applyLevels()
    }

    /// Duck the chatter (voice + bed) under a real ATC call, or restore it. The squelch
    /// path is never ducked.
    func setDucked(_ ducked: Bool) {
        self.ducked = ducked
        applyLevels()
    }

    /// Raise the static bed while a chatter transmission is playing, and drop it back to
    /// near-silent between calls.
    func setTransmitting(_ transmitting: Bool) {
        self.transmitting = transmitting
        applyLevels()
    }

    private func applyLevels() {
        // The chatter voice sits well above the static so the calls read clearly. The
        // static bed is kept much lower than the voice, and — like a real squelch — it
        // only "opens up" while a transmission is playing, falling to near-silent in the
        // gaps between calls.
        let voice: Float = ducked ? 0 : chatterLevel * 2.0
        let bed: Float
        if ducked {
            bed = chatterLevel * 0.05          // faint hiss under a real ATC call
        } else if transmitting {
            bed = chatterLevel * 0.35          // static wraps the active chatter call
        } else {
            bed = chatterLevel * 0.04          // almost inaudible between calls
        }
        speechMixer.outputVolume = max(0, min(1, voice))
        bedGain.pointee = max(0, min(1, bed))
        bedMixer.outputVolume = 1
    }

    // MARK: - Playback

    /// Schedule synthesized chatter (any PCM format) through the radio voice chain.
    /// `completion` fires on the main queue once the last buffer has played.
    func scheduleSpeech(_ buffers: [AVAudioPCMBuffer], completion: @escaping () -> Void) {
        guard isRunning, !buffers.isEmpty else { completion(); return }
        let converted = buffers.compactMap(convertToCommon)
        guard !converted.isEmpty else { completion(); return }
        // Radio saturation (soft-clip) before the band-pass EQ — driven fairly hard, since
        // the chatter sits behind static and can take the extra grit. `drive` is the main
        // grit knob (tanh is near-linear, and inaudible, below ~4).
        for buffer in converted { applyRadioSaturation(to: buffer, drive: 8, mix: 0.7, outputGain: 0.75) }
        if !speechPlayer.isPlaying { speechPlayer.play() }

        let total = converted.count
        let lock = NSLock()
        var done = 0
        for buffer in converted {
            speechPlayer.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in
                lock.lock()
                done += 1
                let finished = done >= total
                lock.unlock()
                if finished { DispatchQueue.main.async(execute: completion) }
            }
        }
    }

    /// Fire the dull PTT key-down "thump" (pilot presses the mic key).
    func playKeyClick() {
        play(squelch: keyClickBuffer)
    }

    /// Fire the PTT-release squelch tail (pilot un-keys — the receiver-return burst).
    func playSquelchTail() {
        play(squelch: squelchTailBuffer)
    }

    private func play(squelch buffer: AVAudioPCMBuffer?) {
        guard isRunning, let buffer else { return }
        if !squelchPlayer.isPlaying { squelchPlayer.play() }
        squelchPlayer.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack, completionHandler: nil)
    }

    // MARK: - Helpers

    private func convertToCommon(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard buffer.frameLength > 0 else { return nil }
        if buffer.format == commonFormat { return buffer }

        let converter: AVAudioConverter
        if let cached = converters[buffer.format] {
            converter = cached
        } else if let made = AVAudioConverter(from: buffer.format, to: commonFormat) {
            converters[buffer.format] = made
            converter = made
        } else {
            return nil
        }

        let ratio = commonFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 2_048
        guard let output = AVAudioPCMBuffer(pcmFormat: commonFormat, frameCapacity: capacity) else { return nil }

        var supplied = false
        let inputBlock: AVAudioConverterInputBlock = { _, statusOut in
            if supplied {
                statusOut.pointee = .noDataNow
                return nil
            }
            supplied = true
            statusOut.pointee = .haveData
            return buffer
        }
        var error: NSError?
        let status = converter.convert(to: output, error: &error, withInputFrom: inputBlock)
        guard status != .error, output.frameLength > 0 else { return nil }
        return output
    }

    /// The **PTT key-down thump** (~32 ms): a subtle, dull contact "thump" as the pilot
    /// presses the mic key — deliberately *not* static and *not* a sharp button click.
    /// Band-limited noise (no oscillator, so there's no audible pitch or ring), gently
    /// emphasized in the low-mids (~180–300 Hz, set by `aHigh`) and rolled off above
    /// ~1.7 kHz (`aLow`), with a soft ~4 ms attack rather than an instant transient and
    /// no static wash. Sits ~16 dB under the pilot voice, so it reads as a muffled contact.
    private func makeKeyClickBuffer() -> AVAudioPCMBuffer? {
        makeBurst(duration: 0.032, aLow: 0.22, aHigh: 0.026, attack: 0.12, decayPower: 2.5, amplitude: 0.30)
    }

    /// The **PTT-release squelch tail** (~140 ms): the receiver-return burst you hear when
    /// the pilot un-keys. Band-limited static (≈350 Hz–3 kHz) with a fast ~2 ms attack and
    /// ~40 ms of open-squelch noise that then decays rapidly to complete silence, plus a
    /// few sparse, irregular crackles poking through the decay. No beep, chirp, or lingering
    /// hiss; its initial level sits ~12 dB under the pilot voice.
    private func makeSquelchTailBuffer() -> AVAudioPCMBuffer? {
        let duration = 0.140
        let frames = AVAudioFrameCount(commonFormat.sampleRate * duration)
        guard frames > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: commonFormat, frameCapacity: frames),
              let channels = buffer.floatChannelData else { return nil }
        buffer.frameLength = frames
        let n = Int(frames)
        let fs = Float(commonFormat.sampleRate)
        let durationF = Float(duration)

        // Band-pass ≈350 Hz–3 kHz via two cascaded one-pole filters (see `makeBurst`).
        let aLow: Float = 0.35       // ≈3 kHz low-pass (upper edge)
        let aHigh: Float = 0.049     // ≈350 Hz high-pass (lower edge)
        let amplitude: Float = 0.47  // initial level ≈12 dB below the pilot voice

        // Envelope (seconds): fast attack → a short body of open-squelch noise → a rapid
        // exponential collapse to silence.
        let attack: Float = 0.002    // 2 ms attack
        let bodyEnd: Float = 0.042   // ~40 ms of noise, then decay
        let decayTau: Float = 0.020  // rapid decay time constant

        // One to three sparse, irregular crackles: brief spikes that poke above the
        // decaying noise at uneven spacings and levels. Baked into the buffer (it is
        // generated once); each is a fast attack/decay bump modulating the same noise.
        let crackleCenters: [Float] = [0.052, 0.083, 0.121]  // seconds, irregular spacing
        let cracklePeaks: [Float]   = [0.50, 0.40, 0.30]     // trailing off
        let crackleAttack: Float = 0.0004
        let crackleTau: Float = 0.0018

        var state: UInt32 = 0x2B7E_1516
        var low: Float = 0
        var lowLow: Float = 0
        for i in 0..<n {
            state ^= state << 13; state ^= state >> 17; state ^= state << 5
            let white = Float(Int32(bitPattern: state)) / Float(Int32.max)
            low += aLow * (white - low)          // low-pass (upper edge)
            lowLow += aHigh * (low - lowLow)     // low-frequency tracker
            let band = low - lowLow              // band-pass
            let ts = Float(i) / fs

            // Body: attack ramp, short plateau, then a rapid decay to silence.
            let body: Float
            if ts < attack {
                body = ts / attack
            } else if ts < bodyEnd {
                body = 1
            } else {
                body = expf(-(ts - bodyEnd) / decayTau)
            }

            // Crackles layered on top of the body envelope.
            var crackle: Float = 0
            for (center, peak) in zip(crackleCenters, cracklePeaks) {
                let dt = ts - center
                if dt < -crackleAttack { continue }
                crackle += dt < 0 ? peak * (dt + crackleAttack) / crackleAttack
                                  : peak * expf(-dt / crackleTau)
            }

            var env = body + crackle
            // Guarantee a clean, click-free finish into complete silence.
            let fade: Float = 0.003
            if ts > durationF - fade { env *= max(0, (durationF - ts) / fade) }

            let sample = band * env * amplitude
            for channel in 0..<Int(commonFormat.channelCount) {
                channels[channel][i] = sample
            }
        }
        return buffer
    }

    /// Build a band-limited noise burst with an attack ramp and a power-curve decay.
    private func makeBurst(duration: Double, aLow: Float, aHigh: Float,
                           attack: Float, decayPower: Float, amplitude: Float) -> AVAudioPCMBuffer? {
        let frames = AVAudioFrameCount(commonFormat.sampleRate * duration)
        guard frames > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: commonFormat, frameCapacity: frames),
              let channels = buffer.floatChannelData else { return nil }
        buffer.frameLength = frames
        var state: UInt32 = 0x1234_5678
        var low: Float = 0
        var lowLow: Float = 0
        let n = Int(frames)
        for i in 0..<n {
            state ^= state << 13; state ^= state >> 17; state ^= state << 5
            let white = Float(Int32(bitPattern: state)) / Float(Int32.max)
            low += aLow * (white - low)         // low-pass
            lowLow += aHigh * (low - lowLow)    // low-frequency tracker
            let band = low - lowLow             // band-pass
            let t = Float(i) / Float(n)
            let env: Float = t < attack ? (t / attack) : powf(1 - (t - attack) / (1 - attack), decayPower)
            let sample = band * env * amplitude
            for channel in 0..<Int(commonFormat.channelCount) {
                channels[channel][i] = sample
            }
        }
        return buffer
    }
}
