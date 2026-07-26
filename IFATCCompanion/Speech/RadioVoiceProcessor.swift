import Foundation
import AVFoundation

/// Applies a VHF-radio effect to the **main** ATC/pilot calls so the same iOS voices
/// sound like they're coming over the radio, the way a flight sim does it. This is *not*
/// an echo/reverb (that sounds like a room, not a radio) — the effect is the same one
/// real radios impose: a **band-pass filter to the comms band** plus a **little
/// distortion**. The band-pass is what strips the fullness and gives the tinny,
/// boxed-in radio timbre; the distortion adds the gritty "through a speaker" edge.
///
/// Synthesized speech buffers are scheduled through `player → EQ → distortion → mixer`
/// and played at full voice volume (this is the real call, not the ambient chatter, so
/// it is never ducked). The band-pass is kept a touch wider than a pure 300 Hz–3 kHz
/// comms band, and the distortion mix low, so the calls stay clearly intelligible.
///
/// Driven from the main actor (its `play`/`stop` are awaited by `SpeechService`); the
/// only off-main code is the buffer-completion callback, which hops back to main.
@MainActor
final class RadioVoiceProcessor {

    /// Fixed internal format everything is converted to before it hits the graph.
    let commonFormat = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)!

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let eq = AVAudioUnitEQ(numberOfBands: 2)
    private let distortion = AVAudioUnitDistortion()
    private let mixer = AVAudioMixerNode()

    private var converters: [AVAudioFormat: AVAudioConverter] = [:]
    private var built = false
    private(set) var isRunning = false

    /// The continuation for the call currently playing, so `stop()` can unblock a caller
    /// that is awaiting `play(...)`.
    private var currentPlay: ResumeOnceVoice?

    // MARK: - Graph

    private func buildGraph() {
        guard !built else { return }
        built = true

        // Band-pass ~300 Hz–3.4 kHz (a little wider than a pure comms band so the ATC
        // calls stay intelligible).
        eq.bands[0].filterType = .highPass
        eq.bands[0].frequency = 300
        eq.bands[0].bypass = false
        eq.bands[1].filterType = .lowPass
        eq.bands[1].frequency = 3_400
        eq.bands[1].bypass = false
        eq.globalGain = 3

        // Light radio-tower distortion — enough for the "over the air" edge without
        // hurting readability.
        distortion.loadFactoryPreset(.speechRadioTower)
        distortion.wetDryMix = 12

        for node in [player as AVAudioNode, eq, distortion, mixer] { engine.attach(node) }
        engine.connect(player, to: eq, format: commonFormat)
        engine.connect(eq, to: distortion, format: commonFormat)
        engine.connect(distortion, to: mixer, format: commonFormat)
        engine.connect(mixer, to: engine.mainMixerNode, format: commonFormat)
    }

    // MARK: - Lifecycle

    func start() {
        buildGraph()
        guard !engine.isRunning else { isRunning = true; return }
        engine.prepare()
        do {
            try engine.start()
            player.play()
            isRunning = true
        } catch {
            isRunning = false
        }
    }

    func stop() {
        guard built else { isRunning = false; return }
        player.stop()
        engine.stop()
        isRunning = false
        // Unblock anyone awaiting the current playback.
        currentPlay?.resume()
        currentPlay = nil
    }

    // MARK: - Playback

    /// Play `buffers` through the radio chain at `volume` (0…1), resolving when the last
    /// buffer has played (or on a safety timeout / `stop()`).
    func play(_ buffers: [AVAudioPCMBuffer], volume: Float) async {
        guard isRunning else { return }
        let converted = buffers.compactMap(convertToCommon)
        guard !converted.isEmpty else { return }
        mixer.outputVolume = max(0, min(1, volume))
        if !player.isPlaying { player.play() }

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let box = ResumeOnceVoice(continuation)
            currentPlay = box
            let total = converted.count
            let lock = NSLock()
            var done = 0
            for buffer in converted {
                player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in
                    lock.lock(); done += 1; let finished = done >= total; lock.unlock()
                    if finished { DispatchQueue.main.async { box.resume() } }
                }
            }
            // Safety timeout sized to the audio length so the caller never hangs if a
            // completion is dropped (e.g. the player is stopped mid-buffer).
            let frames = converted.reduce(AVAudioFrameCount(0)) { $0 + $1.frameLength }
            let seconds = Double(frames) / commonFormat.sampleRate + 5
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { box.resume() }
        }
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
            if supplied { statusOut.pointee = .noDataNow; return nil }
            supplied = true
            statusOut.pointee = .haveData
            return buffer
        }
        var error: NSError?
        let status = converter.convert(to: output, error: &error, withInputFrom: inputBlock)
        guard status != .error, output.frameLength > 0 else { return nil }
        return output
    }
}

/// Guarantees a `CheckedContinuation` is resumed exactly once, from any thread.
private final class ResumeOnceVoice {
    private let lock = NSLock()
    private var resumed = false
    private let continuation: CheckedContinuation<Void, Never>

    init(_ continuation: CheckedContinuation<Void, Never>) {
        self.continuation = continuation
    }

    func resume() {
        lock.lock()
        let shouldResume = !resumed
        resumed = true
        lock.unlock()
        if shouldResume { continuation.resume() }
    }
}
